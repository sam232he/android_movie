import assert from "node:assert/strict";
import test from "node:test";
import {
  DRIVE_PRESETS,
  FAT32_MAX_FILE_BYTES,
  FOUR_GIB_BYTES,
  OTHER_STICK_URI,
  STAGE_RAW,
  assessDestination,
  destinationUriToUse,
  markDisconnected,
  markRevoked,
  missingDestinationMessage,
} from "./destination.ts";

const fat32 = DRIVE_PRESETS.find((preset) => preset.id === "fat32")!.facts;
const exfat = DRIVE_PRESETS.find((preset) => preset.id === "exfat")!.facts;
const ntfs = DRIVE_PRESETS.find((preset) => preset.id === "ntfs")!.facts;

test("FAT32 blocks one byte past the limit and exFAT does not", () => {
  assert.equal(FAT32_MAX_FILE_BYTES, FOUR_GIB_BYTES - 1);
  const legal = assessDestination(fat32, [{ id: "ok", fileName: "Ok.mp4", sizeBytes: FAT32_MAX_FILE_BYTES }]);
  assert.equal(legal.trusted, true);
  const over = assessDestination(fat32, [STAGE_RAW]);
  assert.equal(over.trusted, false);
  assert.equal(over.warnings.some((warning) => warning.code === "FAT32_FILE_TOO_LARGE"), true);
  const wide = assessDestination(exfat, [STAGE_RAW]);
  assert.equal(wide.trusted, true);
  assert.equal(wide.warnings.some((warning) => warning.code === "FAT32_FILE_TOO_LARGE"), false);
});

test("NTFS without a probe is blocked and a revoked drive is not replaced", () => {
  const blocked = assessDestination(ntfs, []);
  assert.equal(blocked.trusted, false);
  assert.equal(blocked.warnings.some((warning) => warning.code === "WRITE_UNCONFIRMED"), true);
  const revoked = assessDestination(markRevoked(exfat), []);
  assert.equal(revoked.warnings[0]?.code, "PERMISSION_REVOKED");
  const gone = assessDestination(markDisconnected(fat32), [STAGE_RAW]);
  assert.equal(gone.warnings[0]?.code, "DISCONNECTED");
  assert.equal(gone.warnings.some((warning) => warning.code === "FAT32_FILE_TOO_LARGE"), false);
});

test("another plugged-in stick is ignored", () => {
  const chosen = { treeUri: fat32.treeUri, displayName: fat32.displayName };
  assert.equal(destinationUriToUse(chosen, [OTHER_STICK_URI]), null);
  assert.equal(destinationUriToUse(chosen, [fat32.treeUri, OTHER_STICK_URI]), fat32.treeUri);
  assert.match(missingDestinationMessage(fat32.displayName), /will not be chosen/);
});
