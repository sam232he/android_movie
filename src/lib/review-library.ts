export type SortKey = "NAME" | "SIZE" | "DATE_ADDED" | "DURATION";
export type SortDirection = "ASC" | "DESC";
export type LibraryViewMode = "GRID" | "LIST";

export type LibraryQuery = {
  search: string;
  sortKey: SortKey;
  direction: SortDirection;
};

export type VideoItem = {
  id: string;
  fileName: string;
  sizeBytes: number | null;
  durationMs: number | null;
  width: number | null;
  height: number | null;
  dateAddedEpochSec: number | null;
  relativePath: string | null;
  accessible: boolean;
  metadataWarning: string | null;
  possibleDuplicate: boolean;
};

export const SAMPLE_VIDEOS: VideoItem[] = [
  {
    id: "vid-1",
    fileName: "VID_20240312_101500.mp4",
    sizeBytes: 842 * 1024 * 1024,
    durationMs: 72_000,
    width: 1920,
    height: 1080,
    dateAddedEpochSec: 1_710_234_900,
    relativePath: "DCIM/Camera/",
    accessible: true,
    metadataWarning: null,
    possibleDuplicate: false,
  },
  {
    id: "beach-a",
    fileName: "Beach.mkv",
    sizeBytes: 1_400_000_000,
    durationMs: 842_000,
    width: 1280,
    height: 720,
    dateAddedEpochSec: 1_712_000_000,
    relativePath: "Movies/Trips/",
    accessible: true,
    metadataWarning: null,
    possibleDuplicate: true,
  },
  {
    id: "beach-b",
    fileName: "Beach.mkv",
    sizeBytes: 1_400_000_000,
    durationMs: 842_000,
    width: 1280,
    height: 720,
    dateAddedEpochSec: 1_712_000_300,
    relativePath: "Movies/Trips/",
    accessible: true,
    metadataWarning: null,
    possibleDuplicate: true,
  },
  {
    id: "silent",
    fileName: "Silent_room.mov",
    sizeBytes: 220 * 1024 * 1024,
    durationMs: 220_000,
    width: 854,
    height: 480,
    dateAddedEpochSec: 1_700_000_000,
    relativePath: "DCIM/Camera/",
    accessible: true,
    metadataWarning: null,
    possibleDuplicate: false,
  },
  {
    id: "portrait",
    fileName: "Portrait_door.mp4",
    sizeBytes: 96 * 1024 * 1024,
    durationMs: 42_000,
    width: 1080,
    height: 1920,
    dateAddedEpochSec: 1_715_000_000,
    relativePath: "DCIM/Camera/",
    accessible: true,
    metadataWarning: null,
    possibleDuplicate: false,
  },
  {
    id: "broken",
    fileName: "Broken_index.avi",
    sizeBytes: null,
    durationMs: null,
    width: null,
    height: null,
    dateAddedEpochSec: 1_690_000_000,
    relativePath: "Movies/",
    accessible: false,
    metadataWarning: "Could not read metadata.",
    possibleDuplicate: false,
  },
  {
    id: "pending",
    fileName: "Still_saving.mp4",
    sizeBytes: 12 * 1024 * 1024,
    durationMs: 0,
    width: 640,
    height: 480,
    dateAddedEpochSec: 1_718_000_000,
    relativePath: "DCIM/Camera/",
    accessible: false,
    metadataWarning: "Still being saved by another app.",
    possibleDuplicate: false,
  },
  {
    id: "lecture",
    fileName: "Lecture_notes.mp4",
    sizeBytes: 3_300_000_000,
    durationMs: 5_400_000,
    width: 1280,
    height: 720,
    dateAddedEpochSec: 1_711_000_000,
    relativePath: "Movies/Lectures/",
    accessible: true,
    metadataWarning: null,
    possibleDuplicate: false,
  },
];

export function applyLibraryQuery(items: VideoItem[], query: LibraryQuery): VideoItem[] {
  const needle = query.search.trim().toLowerCase();
  const filtered = needle
    ? items.filter((item) => item.fileName.toLowerCase().includes(needle))
    : items.slice();
  filtered.sort((a, b) => {
    const compared = compareItems(a, b, query);
    if (compared !== 0) return compared;
    if (a.id < b.id) return -1;
    if (a.id > b.id) return 1;
    return 0;
  });
  return filtered;
}

export function toggleSelection(selected: string[], id: string, item: VideoItem | undefined): string[] {
  if (!item || item.id !== id || !item.accessible) return selected;
  return selected.includes(id) ? selected.filter((value) => value !== id) : [...selected, id];
}

export function selectAllVisible(selected: string[], visible: VideoItem[]): string[] {
  const next = new Set(selected);
  for (const item of visible) {
    if (item.accessible) next.add(item.id);
  }
  return [...next];
}

export function deselectAll(): string[] {
  return [];
}

export function formatBytes(bytes: number | null): string {
  if (bytes == null || bytes < 0) return "—";
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes;
  let unit = -1;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  const text = value >= 100 || value === Math.trunc(value) ? String(Math.trunc(value)) : value.toFixed(1);
  return `${text} ${units[unit]}`;
}

export function formatDuration(durationMs: number | null): string {
  if (durationMs == null || durationMs < 0) return "—";
  const totalSeconds = Math.floor(durationMs / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const pad = (value: number) => String(value).padStart(2, "0");
  if (hours > 0) return `${hours}:${pad(minutes)}:${pad(seconds)}`;
  return `${minutes}:${pad(seconds)}`;
}

export function resolutionLabel(item: VideoItem): string {
  if (item.width == null || item.height == null) return "—";
  return `${item.width}×${item.height}`;
}

function compareItems(a: VideoItem, b: VideoItem, query: LibraryQuery): number {
  if (query.sortKey === "NAME") {
    const compared = a.fileName.localeCompare(b.fileName, "en", { sensitivity: "base" });
    return query.direction === "DESC" ? -compared : compared;
  }
  const av = numberField(a, query.sortKey);
  const bv = numberField(b, query.sortKey);
  if (av == null && bv == null) return 0;
  if (av == null) return 1;
  if (bv == null) return -1;
  return query.direction === "ASC" ? av - bv : bv - av;
}

function numberField(item: VideoItem, key: SortKey): number | null {
  if (key === "SIZE") return item.sizeBytes;
  if (key === "DATE_ADDED") return item.dateAddedEpochSec;
  if (key === "DURATION") return item.durationMs;
  return null;
}
