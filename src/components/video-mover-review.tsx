import { useMemo, useState } from "react";
import { LayoutGrid, List, Search } from "lucide-react";
import {
  DRIVE_PRESETS,
  OTHER_STICK_URI,
  STAGE_RAW,
  assessDestination,
  destinationUriToUse,
  markDisconnected,
  markRevoked,
  missingDestinationMessage,
  type DrivePreset,
} from "@/lib/destination";
import {
  SAMPLE_VIDEOS,
  applyLibraryQuery,
  deselectAll,
  formatBytes,
  formatDuration,
  resolutionLabel,
  selectAllVisible,
  toggleSelection,
  type LibraryViewMode,
  type SortDirection,
  type SortKey,
  type VideoItem,
} from "@/lib/review-library";

type Tab = "library" | "usb" | "compat" | "pipeline";

const SORTS: { key: SortKey; label: string }[] = [
  { key: "NAME", label: "Name" },
  { key: "SIZE", label: "Size" },
  { key: "DATE_ADDED", label: "Date" },
  { key: "DURATION", label: "Duration" },
];

export function VideoMoverReview() {
  const [tab, setTab] = useState<Tab>("library");

  return (
    <main className="mx-auto min-h-screen w-full max-w-5xl px-4 py-6 sm:px-6">
      <header className="mb-6 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="font-mono text-xs tracking-widest text-muted uppercase">Phases 0–3</p>
          <h1 className="text-balance text-3xl font-semibold tracking-tight">Video Mover</h1>
          <p className="mt-1 max-w-xl text-pretty text-muted">
            Browse, then choose one USB folder. Nothing on this page moves or deletes a file.
          </p>
        </div>
        <p className="font-mono text-xs text-muted">Move only · 480p or 720p · No APK built</p>
      </header>

      <div className="mb-6 flex gap-2" role="tablist" aria-label="Review sections">
        <TabButton id="library" current={tab} onSelect={setTab} label="Library" />
        <TabButton id="usb" current={tab} onSelect={setTab} label="USB" />
        <TabButton id="compat" current={tab} onSelect={setTab} label="Compatibility" />
        <TabButton id="pipeline" current={tab} onSelect={setTab} label="Pipeline" />
      </div>

      {tab === "library" ? <LibraryTab /> : null}
      {tab === "usb" ? <UsbTab /> : null}
      {tab === "compat" ? <CompatTab /> : null}
      {tab === "pipeline" ? <PipelineTab /> : null}
    </main>
  );
}

function TabButton({
  id,
  current,
  onSelect,
  label,
}: {
  id: Tab;
  current: Tab;
  onSelect: (tab: Tab) => void;
  label: string;
}) {
  const selected = current === id;
  return (
    <button
      type="button"
      role="tab"
      aria-selected={selected}
      onClick={() => onSelect(id)}
      className={`h-11 rounded-sm px-4 text-sm font-medium ${selected ? "bg-signal text-signal-ink" : "bg-panel text-ink"}`}
    >
      {label}
    </button>
  );
}

function LibraryTab() {
  const [search, setSearch] = useState("");
  const [sortKey, setSortKey] = useState<SortKey>("DATE_ADDED");
  const [direction, setDirection] = useState<SortDirection>("DESC");
  const [mode, setMode] = useState<LibraryViewMode>("GRID");
  const [selected, setSelected] = useState<string[]>([]);

  const visible = useMemo(
    () => applyLibraryQuery(SAMPLE_VIDEOS, { search, sortKey, direction }),
    [search, sortKey, direction],
  );
  const hiddenSelected = selected.filter((id) => !visible.some((item) => item.id === id)).length;

  function chooseSort(key: SortKey) {
    if (key === sortKey) {
      setDirection((value) => (value === "ASC" ? "DESC" : "ASC"));
      return;
    }
    setSortKey(key);
    setDirection(key === "NAME" ? "ASC" : "DESC");
  }

  return (
    <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,24rem)_minmax(0,1fr)]">
      <section className="rounded-md border border-line bg-panel p-3" aria-label="Sample video library">
        <p className="mb-3 px-1 text-sm text-muted">
          Sample roll, so this page can be tried without a phone. The Android screen reads MediaStore instead.
        </p>
        <label className="mb-3 flex h-11 items-center gap-2 rounded-sm border border-line bg-paper px-3">
          <Search className="size-4 text-muted" aria-hidden="true" />
          <span className="sr-only">Search filenames</span>
          <input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search filenames"
            className="w-full bg-transparent text-sm text-ink outline-none placeholder:text-muted"
          />
        </label>
        <div className="mb-3 flex gap-2 overflow-x-auto">
          {SORTS.map((sort) => {
            const active = sort.key === sortKey;
            const arrow = active ? (direction === "ASC" ? " ↑" : " ↓") : "";
            return (
              <button
                key={sort.key}
                type="button"
                onClick={() => chooseSort(sort.key)}
                className={`h-11 shrink-0 rounded-sm px-3 text-sm ${active ? "bg-signal text-signal-ink" : "bg-paper text-ink"}`}
              >
                {sort.label}
                {arrow}
              </button>
            );
          })}
        </div>
        <div className="mb-2 flex flex-wrap items-center gap-1">
          <button type="button" className="h-11 shrink-0 whitespace-nowrap px-2 text-sm" onClick={() => setSelected(selectAllVisible(selected, visible))}>
            Select all
          </button>
          <button type="button" className="h-11 shrink-0 whitespace-nowrap px-2 text-sm" onClick={() => setSelected(deselectAll())}>
            Deselect all
          </button>
          <span className="ml-auto font-mono text-xs text-muted tabular-nums">{selected.length} selected</span>
          <button
            type="button"
            aria-label="Grid"
            aria-pressed={mode === "GRID"}
            className={`grid size-11 place-items-center rounded-sm ${mode === "GRID" ? "text-signal" : "text-muted"}`}
            onClick={() => setMode("GRID")}
          >
            <LayoutGrid className="size-4" />
          </button>
          <button
            type="button"
            aria-label="List"
            aria-pressed={mode === "LIST"}
            className={`grid size-11 place-items-center rounded-sm ${mode === "LIST" ? "text-signal" : "text-muted"}`}
            onClick={() => setMode("LIST")}
          >
            <List className="size-4" />
          </button>
        </div>
        {hiddenSelected > 0 ? (
          <p className="mb-2 px-1 text-xs text-muted">Some selected videos are hidden by this search.</p>
        ) : null}
        {visible.length === 0 ? (
          <p className="px-1 py-8 text-sm text-muted">No filenames match that search.</p>
        ) : mode === "GRID" ? (
          <ul className="grid grid-cols-2 gap-2">
            {visible.map((item) => (
              <li key={item.id}>
                <VideoCard item={item} selected={selected.includes(item.id)} layout="grid" onToggle={() => setSelected(toggleSelection(selected, item.id, item))} />
              </li>
            ))}
          </ul>
        ) : (
          <ul className="flex flex-col gap-2">
            {visible.map((item) => (
              <li key={item.id}>
                <VideoCard item={item} selected={selected.includes(item.id)} layout="list" onToggle={() => setSelected(toggleSelection(selected, item.id, item))} />
              </li>
            ))}
          </ul>
        )}
      </section>
      <aside className="space-y-4 text-sm text-pretty text-muted">
        <h2 className="text-lg font-semibold text-ink">What this screen does</h2>
        <p>Search looks at the filename only. A folder such as DCIM will not match unless the name itself contains those letters.</p>
        <p>Select all adds the videos currently listed and skips inaccessible ones. A video hidden by search stays selected until Deselect all.</p>
        <p>Unknown size, date, or duration sorts last in both directions. Duplicate MediaStore rows with the same URI collapse before this list. Two different URIs with the same name, size, and folder are marked Possible duplicate and are not merged.</p>
        <p>Thumbnails on the phone are one decoded frame, 256 pixels on the long side, never the whole file. These plates stand in for that frame.</p>
      </aside>
    </div>
  );
}

function VideoCard({
  item,
  selected,
  layout,
  onToggle,
}: {
  item: VideoItem;
  selected: boolean;
  layout: "grid" | "list";
  onToggle: () => void;
}) {
  const plate = (
    <div className={`relative overflow-hidden rounded-sm bg-paper ${layout === "grid" ? "aspect-video" : "h-14 w-24 shrink-0"}`}>
      <div className={`absolute inset-x-0 bottom-0 bg-signal ${item.id.length % 2 === 0 ? "h-2" : "h-8"} opacity-80`} />
      <span className="absolute right-1 bottom-1 font-mono text-xs text-ink tabular-nums">{formatDuration(item.durationMs)}</span>
    </div>
  );
  return (
    <button
      type="button"
      disabled={!item.accessible}
      aria-pressed={selected}
      onClick={onToggle}
      className={`w-full rounded-sm bg-paper p-2 text-left disabled:cursor-not-allowed ${layout === "list" ? "flex min-h-16 items-center gap-3" : ""} ${selected ? "outline outline-2 outline-signal" : ""}`}
    >
      {plate}
      <span className={layout === "grid" ? "mt-2 block" : "block min-w-0"}>
        <span className="block truncate text-sm text-ink">{item.fileName}</span>
        <span className="block font-mono text-xs text-muted tabular-nums">
          {formatBytes(item.sizeBytes)} · {formatDuration(item.durationMs)}
        </span>
        <span className="block font-mono text-xs text-muted tabular-nums">{resolutionLabel(item)}</span>
        {item.relativePath ? <span className="block truncate text-xs text-muted">{item.relativePath}</span> : null}
        {item.metadataWarning ? <span className="block text-xs text-signal">{item.metadataWarning}</span> : null}
        {item.possibleDuplicate ? <span className="block text-xs text-signal">Possible duplicate</span> : null}
        {!item.accessible ? <span className="block text-xs text-signal">Inaccessible</span> : null}
      </span>
    </button>
  );
}

function UsbTab() {
  const [presetId, setPresetId] = useState<string | null>(null);
  const [included, setIncluded] = useState<string[]>(["silent"]);
  const [link, setLink] = useState<"connected" | "unplugged" | "revoked">("connected");
  const [otherOffered, setOtherOffered] = useState(false);
  const preset = DRIVE_PRESETS.find((item) => item.id === presetId) ?? null;
  const files = [...SAMPLE_VIDEOS.filter((item) => item.accessible), STAGE_RAW];
  const selected = files.filter((item) => included.includes(item.id));
  const facts = preset ? applyLink(preset, link) : null;
  const assessment = assessDestination(
    facts,
    selected.map((item) => ({ id: item.id, fileName: item.fileName, sizeBytes: item.sizeBytes })),
  );
  const usedUri = preset
    ? destinationUriToUse(
        { treeUri: preset.facts.treeUri, displayName: preset.facts.displayName },
        link === "connected" ? [preset.facts.treeUri, OTHER_STICK_URI] : [OTHER_STICK_URI],
      )
    : null;

  function choosePreset(next: DrivePreset) {
    setPresetId(next.id);
    setLink("connected");
    setOtherOffered(false);
  }

  return (
    <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,20rem)]">
      <section className="rounded-md border border-line bg-panel p-3" aria-label="USB destination">
        <p className="mb-3 px-1 text-sm text-muted">
          Sample drives, so this page can be tried without a stick. The phone uses the system folder picker and keeps that one folder.
        </p>
        <div className="mb-3 flex gap-2 overflow-x-auto">
          {DRIVE_PRESETS.map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => choosePreset(item)}
              aria-pressed={presetId === item.id}
              className={`h-11 shrink-0 whitespace-nowrap rounded-sm px-3 text-sm ${presetId === item.id ? "bg-signal text-signal-ink" : "bg-paper text-ink"}`}
            >
              {item.label}
            </button>
          ))}
        </div>
        <div className="mb-3 flex flex-wrap gap-2">
          <button type="button" className="h-11 whitespace-nowrap px-2 text-sm disabled:opacity-40" disabled={!preset || link !== "connected"} onClick={() => setLink("unplugged")}>
            Unplug
          </button>
          <button type="button" className="h-11 whitespace-nowrap px-2 text-sm disabled:opacity-40" disabled={!preset || link === "connected"} onClick={() => setLink("connected")}>
            Plug back in
          </button>
          <button type="button" className="h-11 whitespace-nowrap px-2 text-sm disabled:opacity-40" disabled={!preset || link !== "connected"} onClick={() => setLink("revoked")}>
            Revoke permission
          </button>
          <button type="button" className="h-11 whitespace-nowrap px-2 text-sm disabled:opacity-40" disabled={!preset} onClick={() => setOtherOffered(true)}>
            Another stick is plugged in
          </button>
        </div>
        <p className="mb-2 px-1 text-sm text-muted">Files to check against this folder</p>
        <ul className="flex flex-col gap-1">
          {files.map((item) => {
            const checked = included.includes(item.id);
            return (
              <li key={item.id}>
                <label className="flex min-h-11 items-center gap-3 rounded-sm bg-paper px-3 text-sm">
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={() =>
                      setIncluded((current) =>
                        current.includes(item.id) ? current.filter((id) => id !== item.id) : [...current, item.id],
                      )
                    }
                  />
                  <span className="min-w-0 flex-1 truncate">{item.fileName}</span>
                  <span className="font-mono text-xs text-muted tabular-nums">{formatBytes(item.sizeBytes)}</span>
                </label>
              </li>
            );
          })}
        </ul>
      </section>
      <aside className="space-y-3 rounded-md border border-line bg-panel p-4 text-sm">
        <h2 className="text-lg font-semibold text-ink">{preset ? preset.facts.displayName : "No folder chosen"}</h2>
        <p className="font-mono text-xs text-muted">
          {assessment.status === "NONE"
            ? "None"
            : assessment.status === "READY"
              ? "Ready"
              : assessment.status === "WARNING"
                ? "Ready, with a warning"
                : "Blocked"}
          {facts ? ` · ${filesystemLabel(assessment.filesystem)}` : ""}
        </p>
        <p className="text-muted">
          Free space: {facts && link === "connected" ? formatBytes(facts.freeBytes) : "not reported"}
          {facts?.totalBytes != null && link === "connected" ? ` of ${formatBytes(facts.totalBytes)}` : ""}
        </p>
        {assessment.warnings.length === 0 ? (
          <p className="text-muted">No warnings. This folder can be used. Nothing has been copied.</p>
        ) : (
          <ul className="space-y-2">
            {assessment.warnings.map((warning) => (
              <li key={`${warning.code}-${warning.fileId ?? ""}`} className={warning.blocking ? "text-signal" : "text-muted"}>
                {warning.message}
              </li>
            ))}
          </ul>
        )}
        {otherOffered && preset ? (
          <p className="text-pretty text-ink">
            {usedUri
              ? `Still using ${preset.facts.displayName}. The other stick was not selected.`
              : missingDestinationMessage(preset.facts.displayName)}
          </p>
        ) : null}
      </aside>
    </div>
  );
}

function applyLink(preset: DrivePreset, link: "connected" | "unplugged" | "revoked") {
  if (link === "unplugged") return markDisconnected(preset.facts);
  if (link === "revoked") return markRevoked(preset.facts);
  return preset.facts;
}

function filesystemLabel(kind: string): string {
  if (kind === "FAT32") return "FAT32";
  if (kind === "EXFAT") return "exFAT";
  if (kind === "NTFS") return "NTFS";
  if (kind === "EXT4") return "ext4";
  if (kind === "OTHER") return "Unrecognized";
  return "Not reported";
}

function CompatTab() {
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <article className="rounded-md border border-line bg-panel p-4">
        <h2 className="text-lg font-semibold">Build</h2>
        <p className="mt-2 text-sm text-pretty text-muted">
          No APK. JDK 17 is present. The Android SDK, a Kotlin compiler on the path, and Gradle on the path were not. Gradle 8.11.1 was downloaded and the wrapper was generated. The phone UI has not been compiled.
        </p>
      </article>
      <article className="rounded-md border border-line bg-panel p-4">
        <h2 className="text-lg font-semibold">Phones</h2>
        <p className="mt-2 text-sm text-pretty text-muted">
          Galaxy A72: Android 11 through 14, last OS update 6 January 2024, Snapdragon 720G. Galaxy A30s: Android 9 through 11. The phone was not confirmed, so minSdk is 28 and targetSdk is 35.
        </p>
      </article>
      <article className="rounded-md border border-line bg-panel p-4">
        <h2 className="text-lg font-semibold">Deleting the original</h2>
        <p className="mt-2 text-sm text-pretty text-muted">
          From Android 11 on, deleting a camera video shows the system dialog from MediaStore.createDeleteRequest. A background job cannot tap that dialog. The delete is armed once in the database first. A restart must not arm it again. If it fails, both copies stay.
        </p>
      </article>
      <article className="rounded-md border border-line bg-panel p-4">
        <h2 className="text-lg font-semibold">Folders and USB</h2>
        <p className="mt-2 text-sm text-pretty text-muted">
          On Android 11 and later the storage-access picker will not grant the internal root, a reliable SD card root, Download, or Android/data. The USB step stores one tree URI and will not switch to a different volume if that one disappears. FAT32 rejects a file over 4,294,967,295 bytes. exFAT does not. NTFS is not treated as writable unless a write test on that stick succeeds. Phone storage is refused.
        </p>
      </article>
      <article className="rounded-md border border-line bg-panel p-4 lg:col-span-2">
        <h2 className="text-lg font-semibold">Tests</h2>
        <p className="mt-2 text-sm text-pretty text-muted">
          Gradle compiled the core module and ran its tests: 38 tests, 0 failures. That covers selection, sorting, the rule that a source delete cannot run twice, and the USB warnings: FAT32 size, NTFS, read-only, disconnect, and refusing a different stick. Nothing has run on a Galaxy phone. The folder picker has not been opened on a device.
        </p>
      </article>
    </div>
  );
}

const STATES = [
  { id: "PENDING", next: "Start transfer → TRANSFERRING. Cancel → CANCELLED." },
  { id: "TRANSFERRING", next: "Checksum match → TRANSFER_VERIFIED. Failure keeps the original → FAILED. A repeat start does not delete anything." },
  { id: "TRANSFER_VERIFIED", next: "The only state that may arm a source delete. The arm flips once. A second arm does nothing." },
  { id: "SOURCE_DELETED", next: "Conversion may start. Retry after a later conversion failure returns here, not to a new transfer." },
  { id: "CONVERTING", next: "Validated output → CONVERTED. Failure → FAILED. The USB original is not deleted." },
  { id: "CONVERTED", next: "Finished. Cancel is rejected." },
  { id: "NEEDS_ATTENTION", next: "Pauses that file only and remembers the state to resume. Used for a denied delete, a missing USB drive, or a folder the app cannot write." },
  { id: "FAILED", next: "Retry goes to PENDING if the phone still has the file, or SOURCE_DELETED if the phone copy is already gone." },
  { id: "CANCELLED", next: "Does not delete the phone file, the USB copy, or a finished MP4." },
];

function PipelineTab() {
  const [active, setActive] = useState("TRANSFER_VERIFIED");
  const current = STATES.find((state) => state.id === active) ?? STATES[0];
  return (
    <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,18rem)]">
      <div>
        <p className="mb-3 max-w-2xl text-sm text-pretty text-muted">
          One file at a time. Media3 Transformer will do the later MP4 step: H.264, AAC when the source has audio, 480p or 720p, aspect kept, no upscale. FFmpegKit is retired and is not linked. A temp file on USB is never treated as a finished move.
        </p>
        <ol className="grid gap-2 sm:grid-cols-3">
          {STATES.map((state) => (
            <li key={state.id}>
              <button
                type="button"
                onClick={() => setActive(state.id)}
                aria-pressed={active === state.id}
                className={`h-11 w-full rounded-sm px-2 font-mono text-xs ${active === state.id ? "bg-signal text-signal-ink" : "bg-panel text-ink"}`}
              >
                {state.id}
              </button>
            </li>
          ))}
        </ol>
      </div>
      <aside className="rounded-md border border-line bg-panel p-4">
        <h2 className="font-mono text-sm">{current.id}</h2>
        <p className="mt-2 text-sm text-pretty text-muted">{current.next}</p>
      </aside>
    </div>
  );
}
