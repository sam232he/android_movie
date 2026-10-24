import assert from "node:assert/strict";
import test from "node:test";
import {
  SAMPLE_VIDEOS,
  applyLibraryQuery,
  deselectAll,
  formatBytes,
  formatDuration,
  selectAllVisible,
  toggleSelection,
} from "./review-library.ts";

test("search is filename only", () => {
  const found = applyLibraryQuery(SAMPLE_VIDEOS, {
    search: " beach ",
    sortKey: "NAME",
    direction: "ASC",
  });
  assert.deepEqual(found.map((item) => item.id), ["beach-a", "beach-b"]);
  const byFolder = applyLibraryQuery(SAMPLE_VIDEOS, {
    search: "DCIM",
    sortKey: "NAME",
    direction: "ASC",
  });
  assert.equal(byFolder.length, 0);
});

test("unknown size stays last either way", () => {
  const items = SAMPLE_VIDEOS.filter((item) => item.id === "silent" || item.id === "broken");
  for (const direction of ["ASC", "DESC"] as const) {
    const sorted = applyLibraryQuery(items, { search: "", sortKey: "SIZE", direction });
    assert.equal(sorted.at(-1)?.id, "broken");
  }
});

test("select all skips inaccessible and keeps a hidden id", () => {
  const visible = applyLibraryQuery(SAMPLE_VIDEOS, {
    search: "",
    sortKey: "NAME",
    direction: "ASC",
  });
  const selected = selectAllVisible(["hidden"], visible);
  assert.ok(selected.includes("hidden"));
  assert.ok(selected.includes("silent"));
  assert.equal(selected.includes("broken"), false);
  assert.equal(selected.includes("pending"), false);
  assert.deepEqual(deselectAll(), []);
});

test("toggle refuses an inaccessible row", () => {
  const blocked = SAMPLE_VIDEOS.find((item) => item.id === "broken");
  assert.deepEqual(toggleSelection(["silent"], "broken", blocked), ["silent"]);
});

test("formats match the phone helpers", () => {
  assert.equal(formatBytes(1024), "1 KB");
  assert.equal(formatBytes(1536), "1.5 KB");
  assert.equal(formatBytes(null), "—");
  assert.equal(formatDuration(65_000), "1:05");
  assert.equal(formatDuration(3_661_000), "1:01:01");
  assert.equal(formatDuration(null), "—");
});
