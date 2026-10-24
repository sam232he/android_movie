export const FAT32_MAX_FILE_BYTES = 4_294_967_295;
export const FOUR_GIB_BYTES = 4 * 1024 * 1024 * 1024;

export type FilesystemKind = "FAT32" | "EXFAT" | "NTFS" | "EXT4" | "OTHER" | "UNKNOWN";
export type Writability = "WRITABLE" | "READ_ONLY" | "UNSUPPORTED" | "UNCONFIRMED";
export type DestinationStatus = "NONE" | "READY" | "WARNING" | "BLOCKED";

export type DestinationWarningCode =
  | "NO_DESTINATION"
  | "DISCONNECTED"
  | "PERMISSION_REVOKED"
  | "INTERNAL_STORAGE"
  | "NO_WRITE_PERMISSION"
  | "PERMISSION_NOT_PERSISTED"
  | "PERMISSION_NOT_SAVED"
  | "READ_ONLY"
  | "WRITE_UNCONFIRMED"
  | "NTFS_WRITE_CONFIRMED"
  | "FAT32_FILE_TOO_LARGE"
  | "FAT32_SIZE_UNKNOWN"
  | "UNKNOWN_FILESYSTEM_LARGE_FILE"
  | "UNKNOWN_SIZE_UNCHECKED"
  | "NOT_ENOUGH_SPACE"
  | "SPACE_NOT_FULLY_CHECKED"
  | "CAPACITY_UNKNOWN";

export type DestinationWarning = {
  code: DestinationWarningCode;
  message: string;
  blocking: boolean;
  fileId?: string;
};

export type VolumeFacts = {
  treeUri: string;
  displayName: string;
  grantedRead: boolean;
  grantedWrite: boolean;
  persisted: boolean;
  persistable: boolean;
  volumePresent: boolean;
  permissionRevoked: boolean;
  internalStorage: boolean;
  filesystemRaw: string | null;
  canWriteFlag: boolean | null;
  writeProbeSucceeded: boolean | null;
  freeBytes: number | null;
  totalBytes: number | null;
};

export type SelectedFileSize = {
  id: string;
  fileName: string;
  sizeBytes: number | null;
};

export type DestinationAssessment = {
  status: DestinationStatus;
  filesystem: FilesystemKind;
  writability: Writability;
  warnings: DestinationWarning[];
  trusted: boolean;
  blockedFileIds: string[];
};

export type RememberedDestination = {
  treeUri: string;
  displayName: string;
};

const CONCRETE = new Set([
  "vfat",
  "fat32",
  "msdos",
  "exfat",
  "sdfat",
  "ntfs",
  "ext4",
  "ext3",
  "ext2",
  "f2fs",
]);

export function classifyFilesystem(raw: string | null): FilesystemKind {
  if (!raw || !raw.trim()) return "UNKNOWN";
  const token = raw.trim().toLowerCase();
  if (token === "vfat" || token === "fat32" || token === "msdos") return "FAT32";
  if (token === "exfat" || token === "sdfat") return "EXFAT";
  if (token === "ntfs") return "NTFS";
  if (token === "ext4" || token === "ext3" || token === "ext2" || token === "f2fs") return "EXT4";
  return "OTHER";
}

export function assessDestination(
  facts: VolumeFacts | null,
  selected: SelectedFileSize[],
): DestinationAssessment {
  if (!facts) {
    return {
      status: "NONE",
      filesystem: "UNKNOWN",
      writability: "UNCONFIRMED",
      warnings: [
        {
          code: "NO_DESTINATION",
          message: "No destination has been chosen.",
          blocking: true,
        },
      ],
      trusted: false,
      blockedFileIds: [],
    };
  }

  const filesystem = classifyFilesystem(facts.filesystemRaw);
  const warnings: DestinationWarning[] = [];
  let writability: Writability;

  if (facts.permissionRevoked || (!facts.grantedRead && !facts.grantedWrite)) {
    writability = "UNCONFIRMED";
    warnings.push({
      code: "PERMISSION_REVOKED",
      message: `Permission for ${facts.displayName} was revoked. Another destination will not be used.`,
      blocking: true,
    });
  } else if (!facts.volumePresent) {
    writability = "UNCONFIRMED";
    warnings.push({
      code: "DISCONNECTED",
      message: `${facts.displayName} is not connected. Another destination will not be used.`,
      blocking: true,
    });
  } else if (facts.internalStorage) {
    writability = "UNCONFIRMED";
    warnings.push({
      code: "INTERNAL_STORAGE",
      message:
        "This folder is on internal storage, not a removable drive. It will not be used as the USB destination.",
      blocking: true,
    });
  } else if (!facts.grantedWrite) {
    writability = "UNCONFIRMED";
    warnings.push({
      code: "NO_WRITE_PERMISSION",
      message: "This folder was opened without write access.",
      blocking: true,
    });
  } else {
    writability = decideWritability(facts, filesystem);
    warnings.push(...writabilityWarnings(filesystem, writability));
    if (!facts.persisted) {
      warnings.push(
        facts.persistable
          ? {
              code: "PERMISSION_NOT_SAVED",
              message: "Write access is granted for this session and still needs to be saved.",
              blocking: false,
            }
          : {
              code: "PERMISSION_NOT_PERSISTED",
              message:
                "Write access was granted, but Android will not keep it. The folder has to be chosen again next time.",
              blocking: false,
            },
      );
    }
    warnings.push(...fileWarnings(filesystem, selected));
    warnings.push(...spaceWarnings(facts, selected));
  }

  const blocking = warnings.some((warning) => warning.blocking);
  const status: DestinationStatus = blocking ? "BLOCKED" : warnings.length > 0 ? "WARNING" : "READY";
  return {
    status,
    filesystem,
    writability,
    warnings,
    trusted: !blocking,
    blockedFileIds: warnings.filter((warning) => warning.blocking && warning.fileId).map((warning) => warning.fileId!),
  };
}

export function markDisconnected(facts: VolumeFacts): VolumeFacts {
  return {
    ...facts,
    volumePresent: false,
    freeBytes: null,
    totalBytes: null,
    canWriteFlag: null,
    writeProbeSucceeded: null,
  };
}

export function markRevoked(facts: VolumeFacts): VolumeFacts {
  return {
    ...facts,
    grantedRead: false,
    grantedWrite: false,
    persisted: false,
    permissionRevoked: true,
    canWriteFlag: null,
    writeProbeSucceeded: null,
    freeBytes: null,
    totalBytes: null,
  };
}

export function destinationUriToUse(
  remembered: RememberedDestination | null,
  connectedTreeUris: string[],
): string | null {
  if (!remembered) return null;
  return connectedTreeUris.includes(remembered.treeUri) ? remembered.treeUri : null;
}

export function missingDestinationMessage(name: string): string {
  return `${name} is not available. Another folder will not be chosen.`;
}

export type DrivePreset = {
  id: string;
  label: string;
  facts: VolumeFacts;
};

const GiB = 1024 * 1024 * 1024;

function drive(partial: Partial<VolumeFacts> & Pick<VolumeFacts, "displayName" | "filesystemRaw">): VolumeFacts {
  return {
    treeUri: "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3A",
    grantedRead: true,
    grantedWrite: true,
    persisted: true,
    persistable: true,
    volumePresent: true,
    permissionRevoked: false,
    internalStorage: false,
    canWriteFlag: true,
    writeProbeSucceeded: true,
    freeBytes: 90 * GiB,
    totalBytes: 128 * GiB,
    ...partial,
  };
}

export const DRIVE_PRESETS: DrivePreset[] = [
  {
    id: "exfat",
    label: "exFAT",
    facts: drive({ displayName: "USB exFAT", filesystemRaw: "exfat" }),
  },
  {
    id: "fat32",
    label: "FAT32",
    facts: drive({
      displayName: "USB FAT32",
      filesystemRaw: "vfat",
      treeUri: "content://com.android.externalstorage.documents/tree/AAAA-1111%3A",
      freeBytes: 20 * GiB,
      totalBytes: 32 * GiB,
    }),
  },
  {
    id: "ntfs",
    label: "NTFS",
    facts: drive({
      displayName: "USB NTFS",
      filesystemRaw: "ntfs",
      treeUri: "content://com.android.externalstorage.documents/tree/BBBB-2222%3A",
      writeProbeSucceeded: null,
      freeBytes: 40 * GiB,
    }),
  },
  {
    id: "readonly",
    label: "Read-only",
    facts: drive({
      displayName: "USB read-only",
      filesystemRaw: "vfat",
      treeUri: "content://com.android.externalstorage.documents/tree/CCCC-3333%3A",
      canWriteFlag: false,
      writeProbeSucceeded: null,
      freeBytes: 8 * GiB,
    }),
  },
  {
    id: "unknown",
    label: "Unknown",
    facts: drive({
      displayName: "USB unknown",
      filesystemRaw: null,
      treeUri: "content://com.android.externalstorage.documents/tree/DDDD-4444%3A",
      freeBytes: 64 * GiB,
    }),
  },
  {
    id: "internal",
    label: "Phone storage",
    facts: drive({
      displayName: "Internal storage",
      filesystemRaw: "ext4",
      treeUri: "content://com.android.externalstorage.documents/tree/primary%3A",
      internalStorage: true,
      freeBytes: 12 * GiB,
    }),
  },
];

export const OTHER_STICK_URI = "content://com.android.externalstorage.documents/tree/EEEE-5555%3A";

export const STAGE_RAW: SelectedFileSize = {
  id: "stage",
  fileName: "Stage_raw.mov",
  sizeBytes: 4_600_000_000,
};

function decideWritability(facts: VolumeFacts, filesystem: FilesystemKind): Writability {
  if (facts.canWriteFlag === false || facts.writeProbeSucceeded === false) {
    return filesystem === "NTFS" && facts.writeProbeSucceeded !== true ? "UNSUPPORTED" : "READ_ONLY";
  }
  if (facts.writeProbeSucceeded === true) return "WRITABLE";
  return filesystem === "NTFS" ? "UNSUPPORTED" : "UNCONFIRMED";
}

function writabilityWarnings(filesystem: FilesystemKind, writability: Writability): DestinationWarning[] {
  if (writability === "WRITABLE") {
    return filesystem === "NTFS"
      ? [
          {
            code: "NTFS_WRITE_CONFIRMED",
            message: "A write test succeeded. NTFS is still not assumed on the next drive.",
            blocking: false,
          },
        ]
      : [];
  }
  if (writability === "READ_ONLY") {
    return [{ code: "READ_ONLY", message: "This drive is read-only.", blocking: true }];
  }
  if (writability === "UNSUPPORTED") {
    return [
      {
        code: "WRITE_UNCONFIRMED",
        message: "NTFS is not assumed to be writable. A write test has not succeeded on this drive.",
        blocking: true,
      },
    ];
  }
  return [
    {
      code: "WRITE_UNCONFIRMED",
      message: "Writability has not been tested. exFAT, FAT32, and NTFS are not assumed to be writable.",
      blocking: true,
    },
  ];
}

function fileWarnings(filesystem: FilesystemKind, selected: SelectedFileSize[]): DestinationWarning[] {
  const warnings: DestinationWarning[] = [];
  let unknownOnUnknownFs = false;
  for (const file of selected) {
    const size = file.sizeBytes;
    if (filesystem === "FAT32") {
      if (size == null || size < 0) {
        warnings.push({
          code: "FAT32_SIZE_UNKNOWN",
          message: `${file.fileName} has no size yet. On FAT32 a file over 4 GiB would fail, so it is blocked.`,
          blocking: true,
          fileId: file.id,
        });
      } else if (size > FAT32_MAX_FILE_BYTES) {
        warnings.push({
          code: "FAT32_FILE_TOO_LARGE",
          message: `${file.fileName} is ${size} bytes. FAT32 cannot store a file over ${FAT32_MAX_FILE_BYTES} bytes.`,
          blocking: true,
          fileId: file.id,
        });
      }
    } else if (filesystem === "UNKNOWN" || filesystem === "OTHER") {
      if (size != null && size >= FOUR_GIB_BYTES) {
        warnings.push({
          code: "UNKNOWN_FILESYSTEM_LARGE_FILE",
          message: `The filesystem was not identified. ${file.fileName} is at least 4 GiB, and this drive is not assumed to be exFAT.`,
          blocking: true,
          fileId: file.id,
        });
      } else if (size == null || size < 0) {
        unknownOnUnknownFs = true;
      }
    }
  }
  if (unknownOnUnknownFs) {
    warnings.push({
      code: "UNKNOWN_SIZE_UNCHECKED",
      message:
        "Some selected files have no size. The filesystem is also unknown, so a 4 GiB limit was not checked for them.",
      blocking: false,
    });
  }
  return warnings;
}

function spaceWarnings(facts: VolumeFacts, selected: SelectedFileSize[]): DestinationWarning[] {
  const free = facts.freeBytes;
  if (free == null || free < 0) {
    return [
      {
        code: "CAPACITY_UNKNOWN",
        message: "Android did not report free space for this folder.",
        blocking: false,
      },
    ];
  }
  let known = 0;
  let unknown = 0;
  for (const file of selected) {
    if (file.sizeBytes == null || file.sizeBytes < 0) unknown += 1;
    else known += file.sizeBytes;
  }
  const warnings: DestinationWarning[] = [];
  if (known > free) {
    warnings.push({
      code: "NOT_ENOUGH_SPACE",
      message: `The selected files need more than the ${free} bytes this drive reports free.`,
      blocking: true,
    });
  }
  if (unknown > 0) {
    warnings.push({
      code: "SPACE_NOT_FULLY_CHECKED",
      message: "At least one selected file has no size, so free space was not fully checked.",
      blocking: false,
    });
  }
  return warnings;
}
