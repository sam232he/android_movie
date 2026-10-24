import { i as __toESM } from "../_runtime.mjs";
import { K as require_react, b as require_jsx_runtime } from "../_libs/@tanstack/react-router+[...].mjs";
import { i as LayoutGrid, n as Search, r as List } from "../_libs/lucide-react.mjs";
//#region node_modules/.nitro/vite/services/ssr/assets/routes-CtCQL-ib.js
var import_react = /* @__PURE__ */ __toESM(require_react());
var import_jsx_runtime = require_jsx_runtime();
var SAMPLE_VIDEOS = [
	{
		id: "vid-1",
		fileName: "VID_20240312_101500.mp4",
		sizeBytes: 882900992,
		durationMs: 72e3,
		width: 1920,
		height: 1080,
		dateAddedEpochSec: 1710234900,
		relativePath: "DCIM/Camera/",
		accessible: true,
		metadataWarning: null,
		possibleDuplicate: false
	},
	{
		id: "beach-a",
		fileName: "Beach.mkv",
		sizeBytes: 14e8,
		durationMs: 842e3,
		width: 1280,
		height: 720,
		dateAddedEpochSec: 1712e6,
		relativePath: "Movies/Trips/",
		accessible: true,
		metadataWarning: null,
		possibleDuplicate: true
	},
	{
		id: "beach-b",
		fileName: "Beach.mkv",
		sizeBytes: 14e8,
		durationMs: 842e3,
		width: 1280,
		height: 720,
		dateAddedEpochSec: 1712000300,
		relativePath: "Movies/Trips/",
		accessible: true,
		metadataWarning: null,
		possibleDuplicate: true
	},
	{
		id: "silent",
		fileName: "Silent_room.mov",
		sizeBytes: 230686720,
		durationMs: 22e4,
		width: 854,
		height: 480,
		dateAddedEpochSec: 17e8,
		relativePath: "DCIM/Camera/",
		accessible: true,
		metadataWarning: null,
		possibleDuplicate: false
	},
	{
		id: "portrait",
		fileName: "Portrait_door.mp4",
		sizeBytes: 100663296,
		durationMs: 42e3,
		width: 1080,
		height: 1920,
		dateAddedEpochSec: 1715e6,
		relativePath: "DCIM/Camera/",
		accessible: true,
		metadataWarning: null,
		possibleDuplicate: false
	},
	{
		id: "broken",
		fileName: "Broken_index.avi",
		sizeBytes: null,
		durationMs: null,
		width: null,
		height: null,
		dateAddedEpochSec: 169e7,
		relativePath: "Movies/",
		accessible: false,
		metadataWarning: "Could not read metadata.",
		possibleDuplicate: false
	},
	{
		id: "pending",
		fileName: "Still_saving.mp4",
		sizeBytes: 12582912,
		durationMs: 0,
		width: 640,
		height: 480,
		dateAddedEpochSec: 1718e6,
		relativePath: "DCIM/Camera/",
		accessible: false,
		metadataWarning: "Still being saved by another app.",
		possibleDuplicate: false
	},
	{
		id: "lecture",
		fileName: "Lecture_notes.mp4",
		sizeBytes: 33e8,
		durationMs: 54e5,
		width: 1280,
		height: 720,
		dateAddedEpochSec: 1711e6,
		relativePath: "Movies/Lectures/",
		accessible: true,
		metadataWarning: null,
		possibleDuplicate: false
	}
];
function applyLibraryQuery(items, query) {
	const needle = query.search.trim().toLowerCase();
	const filtered = needle ? items.filter((item) => item.fileName.toLowerCase().includes(needle)) : items.slice();
	filtered.sort((a, b) => {
		const compared = compareItems(a, b, query);
		if (compared !== 0) return compared;
		if (a.id < b.id) return -1;
		if (a.id > b.id) return 1;
		return 0;
	});
	return filtered;
}
function toggleSelection(selected, id, item) {
	if (!item || item.id !== id || !item.accessible) return selected;
	return selected.includes(id) ? selected.filter((value) => value !== id) : [...selected, id];
}
function selectAllVisible(selected, visible) {
	const next = new Set(selected);
	for (const item of visible) if (item.accessible) next.add(item.id);
	return [...next];
}
function deselectAll() {
	return [];
}
function formatBytes(bytes) {
	if (bytes == null || bytes < 0) return "—";
	if (bytes < 1024) return `${bytes} B`;
	const units = [
		"KB",
		"MB",
		"GB",
		"TB"
	];
	let value = bytes;
	let unit = -1;
	while (value >= 1024 && unit < units.length - 1) {
		value /= 1024;
		unit += 1;
	}
	return `${value >= 100 || value === Math.trunc(value) ? String(Math.trunc(value)) : value.toFixed(1)} ${units[unit]}`;
}
function formatDuration(durationMs) {
	if (durationMs == null || durationMs < 0) return "—";
	const totalSeconds = Math.floor(durationMs / 1e3);
	const hours = Math.floor(totalSeconds / 3600);
	const minutes = Math.floor(totalSeconds % 3600 / 60);
	const seconds = totalSeconds % 60;
	const pad = (value) => String(value).padStart(2, "0");
	if (hours > 0) return `${hours}:${pad(minutes)}:${pad(seconds)}`;
	return `${minutes}:${pad(seconds)}`;
}
function resolutionLabel(item) {
	if (item.width == null || item.height == null) return "—";
	return `${item.width}×${item.height}`;
}
function compareItems(a, b, query) {
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
function numberField(item, key) {
	if (key === "SIZE") return item.sizeBytes;
	if (key === "DATE_ADDED") return item.dateAddedEpochSec;
	if (key === "DURATION") return item.durationMs;
	return null;
}
var SORTS = [
	{
		key: "NAME",
		label: "Name"
	},
	{
		key: "SIZE",
		label: "Size"
	},
	{
		key: "DATE_ADDED",
		label: "Date"
	},
	{
		key: "DURATION",
		label: "Duration"
	}
];
function VideoMoverReview() {
	const [tab, setTab] = (0, import_react.useState)("library");
	return /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("main", {
		className: "mx-auto min-h-screen w-full max-w-5xl px-4 py-6 sm:px-6",
		children: [
			/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("header", {
				className: "mb-6 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between",
				children: [/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", { children: [
					/* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
						className: "font-mono text-xs tracking-widest text-muted uppercase",
						children: "Phases 0–2"
					}),
					/* @__PURE__ */ (0, import_jsx_runtime.jsx)("h1", {
						className: "text-balance text-3xl font-semibold tracking-tight",
						children: "Video Mover"
					}),
					/* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
						className: "mt-1 max-w-xl text-pretty text-muted",
						children: "Browse and select. Nothing on this page moves, copies, or deletes a file."
					})
				] }), /* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
					className: "font-mono text-xs text-muted",
					children: "Move only · 480p or 720p · No APK built"
				})]
			}),
			/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", {
				className: "mb-6 flex gap-2",
				role: "tablist",
				"aria-label": "Review sections",
				children: [
					/* @__PURE__ */ (0, import_jsx_runtime.jsx)(TabButton, {
						id: "library",
						current: tab,
						onSelect: setTab,
						label: "Library"
					}),
					/* @__PURE__ */ (0, import_jsx_runtime.jsx)(TabButton, {
						id: "compat",
						current: tab,
						onSelect: setTab,
						label: "Compatibility"
					}),
					/* @__PURE__ */ (0, import_jsx_runtime.jsx)(TabButton, {
						id: "pipeline",
						current: tab,
						onSelect: setTab,
						label: "Pipeline"
					})
				]
			}),
			tab === "library" ? /* @__PURE__ */ (0, import_jsx_runtime.jsx)(LibraryTab, {}) : null,
			tab === "compat" ? /* @__PURE__ */ (0, import_jsx_runtime.jsx)(CompatTab, {}) : null,
			tab === "pipeline" ? /* @__PURE__ */ (0, import_jsx_runtime.jsx)(PipelineTab, {}) : null
		]
	});
}
function TabButton({ id, current, onSelect, label }) {
	const selected = current === id;
	return /* @__PURE__ */ (0, import_jsx_runtime.jsx)("button", {
		type: "button",
		role: "tab",
		"aria-selected": selected,
		onClick: () => onSelect(id),
		className: `h-11 rounded-sm px-4 text-sm font-medium ${selected ? "bg-signal text-signal-ink" : "bg-panel text-ink"}`,
		children: label
	});
}
function LibraryTab() {
	const [search, setSearch] = (0, import_react.useState)("");
	const [sortKey, setSortKey] = (0, import_react.useState)("DATE_ADDED");
	const [direction, setDirection] = (0, import_react.useState)("DESC");
	const [mode, setMode] = (0, import_react.useState)("GRID");
	const [selected, setSelected] = (0, import_react.useState)([]);
	const visible = (0, import_react.useMemo)(() => applyLibraryQuery(SAMPLE_VIDEOS, {
		search,
		sortKey,
		direction
	}), [
		search,
		sortKey,
		direction
	]);
	const hiddenSelected = selected.filter((id) => !visible.some((item) => item.id === id)).length;
	function chooseSort(key) {
		if (key === sortKey) {
			setDirection((value) => value === "ASC" ? "DESC" : "ASC");
			return;
		}
		setSortKey(key);
		setDirection(key === "NAME" ? "ASC" : "DESC");
	}
	return /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", {
		className: "grid items-start gap-6 lg:grid-cols-[minmax(0,24rem)_minmax(0,1fr)]",
		children: [/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("section", {
			className: "rounded-md border border-line bg-panel p-3",
			"aria-label": "Sample video library",
			children: [
				/* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
					className: "mb-3 px-1 text-sm text-muted",
					children: "Sample roll, so this page can be tried without a phone. The Android screen reads MediaStore instead."
				}),
				/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("label", {
					className: "mb-3 flex h-11 items-center gap-2 rounded-sm border border-line bg-paper px-3",
					children: [
						/* @__PURE__ */ (0, import_jsx_runtime.jsx)(Search, {
							className: "size-4 text-muted",
							"aria-hidden": "true"
						}),
						/* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", {
							className: "sr-only",
							children: "Search filenames"
						}),
						/* @__PURE__ */ (0, import_jsx_runtime.jsx)("input", {
							value: search,
							onChange: (event) => setSearch(event.target.value),
							placeholder: "Search filenames",
							className: "w-full bg-transparent text-sm text-ink outline-none placeholder:text-muted"
						})
					]
				}),
				/* @__PURE__ */ (0, import_jsx_runtime.jsx)("div", {
					className: "mb-3 flex gap-2 overflow-x-auto",
					children: SORTS.map((sort) => {
						const active = sort.key === sortKey;
						const arrow = active ? direction === "ASC" ? " ↑" : " ↓" : "";
						return /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("button", {
							type: "button",
							onClick: () => chooseSort(sort.key),
							className: `h-11 shrink-0 rounded-sm px-3 text-sm ${active ? "bg-signal text-signal-ink" : "bg-paper text-ink"}`,
							children: [sort.label, arrow]
						}, sort.key);
					})
				}),
				/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", {
					className: "mb-2 flex flex-wrap items-center gap-1",
					children: [
						/* @__PURE__ */ (0, import_jsx_runtime.jsx)("button", {
							type: "button",
							className: "h-11 shrink-0 whitespace-nowrap px-2 text-sm",
							onClick: () => setSelected(selectAllVisible(selected, visible)),
							children: "Select all"
						}),
						/* @__PURE__ */ (0, import_jsx_runtime.jsx)("button", {
							type: "button",
							className: "h-11 shrink-0 whitespace-nowrap px-2 text-sm",
							onClick: () => setSelected(deselectAll()),
							children: "Deselect all"
						}),
						/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("span", {
							className: "ml-auto font-mono text-xs text-muted tabular-nums",
							children: [selected.length, " selected"]
						}),
						/* @__PURE__ */ (0, import_jsx_runtime.jsx)("button", {
							type: "button",
							"aria-label": "Grid",
							"aria-pressed": mode === "GRID",
							className: `grid size-11 place-items-center rounded-sm ${mode === "GRID" ? "text-signal" : "text-muted"}`,
							onClick: () => setMode("GRID"),
							children: /* @__PURE__ */ (0, import_jsx_runtime.jsx)(LayoutGrid, { className: "size-4" })
						}),
						/* @__PURE__ */ (0, import_jsx_runtime.jsx)("button", {
							type: "button",
							"aria-label": "List",
							"aria-pressed": mode === "LIST",
							className: `grid size-11 place-items-center rounded-sm ${mode === "LIST" ? "text-signal" : "text-muted"}`,
							onClick: () => setMode("LIST"),
							children: /* @__PURE__ */ (0, import_jsx_runtime.jsx)(List, { className: "size-4" })
						})
					]
				}),
				hiddenSelected > 0 ? /* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
					className: "mb-2 px-1 text-xs text-muted",
					children: "Some selected videos are hidden by this search."
				}) : null,
				visible.length === 0 ? /* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
					className: "px-1 py-8 text-sm text-muted",
					children: "No filenames match that search."
				}) : mode === "GRID" ? /* @__PURE__ */ (0, import_jsx_runtime.jsx)("ul", {
					className: "grid grid-cols-2 gap-2",
					children: visible.map((item) => /* @__PURE__ */ (0, import_jsx_runtime.jsx)("li", { children: /* @__PURE__ */ (0, import_jsx_runtime.jsx)(VideoCard, {
						item,
						selected: selected.includes(item.id),
						layout: "grid",
						onToggle: () => setSelected(toggleSelection(selected, item.id, item))
					}) }, item.id))
				}) : /* @__PURE__ */ (0, import_jsx_runtime.jsx)("ul", {
					className: "flex flex-col gap-2",
					children: visible.map((item) => /* @__PURE__ */ (0, import_jsx_runtime.jsx)("li", { children: /* @__PURE__ */ (0, import_jsx_runtime.jsx)(VideoCard, {
						item,
						selected: selected.includes(item.id),
						layout: "list",
						onToggle: () => setSelected(toggleSelection(selected, item.id, item))
					}) }, item.id))
				})
			]
		}), /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("aside", {
			className: "space-y-4 text-sm text-pretty text-muted",
			children: [
				/* @__PURE__ */ (0, import_jsx_runtime.jsx)("h2", {
					className: "text-lg font-semibold text-ink",
					children: "What this screen does"
				}),
				/* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", { children: "Search looks at the filename only. A folder such as DCIM will not match unless the name itself contains those letters." }),
				/* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", { children: "Select all adds the videos currently listed and skips inaccessible ones. A video hidden by search stays selected until Deselect all." }),
				/* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", { children: "Unknown size, date, or duration sorts last in both directions. Duplicate MediaStore rows with the same URI collapse before this list. Two different URIs with the same name, size, and folder are marked Possible duplicate and are not merged." }),
				/* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", { children: "Thumbnails on the phone are one decoded frame, 256 pixels on the long side, never the whole file. These plates stand in for that frame." })
			]
		})]
	});
}
function VideoCard({ item, selected, layout, onToggle }) {
	const plate = /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", {
		className: `relative overflow-hidden rounded-sm bg-paper ${layout === "grid" ? "aspect-video" : "h-14 w-24 shrink-0"}`,
		children: [/* @__PURE__ */ (0, import_jsx_runtime.jsx)("div", { className: `absolute inset-x-0 bottom-0 bg-signal ${item.id.length % 2 === 0 ? "h-2" : "h-8"} opacity-80` }), /* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", {
			className: "absolute right-1 bottom-1 font-mono text-xs text-ink tabular-nums",
			children: formatDuration(item.durationMs)
		})]
	});
	return /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("button", {
		type: "button",
		disabled: !item.accessible,
		"aria-pressed": selected,
		onClick: onToggle,
		className: `w-full rounded-sm bg-paper p-2 text-left disabled:cursor-not-allowed ${layout === "list" ? "flex min-h-16 items-center gap-3" : ""} ${selected ? "outline outline-2 outline-signal" : ""}`,
		children: [plate, /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("span", {
			className: layout === "grid" ? "mt-2 block" : "block min-w-0",
			children: [
				/* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", {
					className: "block truncate text-sm text-ink",
					children: item.fileName
				}),
				/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("span", {
					className: "block font-mono text-xs text-muted tabular-nums",
					children: [
						formatBytes(item.sizeBytes),
						" · ",
						formatDuration(item.durationMs)
					]
				}),
				/* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", {
					className: "block font-mono text-xs text-muted tabular-nums",
					children: resolutionLabel(item)
				}),
				item.relativePath ? /* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", {
					className: "block truncate text-xs text-muted",
					children: item.relativePath
				}) : null,
				item.metadataWarning ? /* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", {
					className: "block text-xs text-signal",
					children: item.metadataWarning
				}) : null,
				item.possibleDuplicate ? /* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", {
					className: "block text-xs text-signal",
					children: "Possible duplicate"
				}) : null,
				!item.accessible ? /* @__PURE__ */ (0, import_jsx_runtime.jsx)("span", {
					className: "block text-xs text-signal",
					children: "Inaccessible"
				}) : null
			]
		})]
	});
}
function CompatTab() {
	return /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", {
		className: "grid gap-4 lg:grid-cols-2",
		children: [
			/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("article", {
				className: "rounded-md border border-line bg-panel p-4",
				children: [/* @__PURE__ */ (0, import_jsx_runtime.jsx)("h2", {
					className: "text-lg font-semibold",
					children: "Build"
				}), /* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
					className: "mt-2 text-sm text-pretty text-muted",
					children: "No APK. JDK 17 is present. The Android SDK, a Kotlin compiler on the path, and Gradle on the path were not. Gradle 8.11.1 was downloaded and the wrapper was generated. The phone UI has not been compiled."
				})]
			}),
			/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("article", {
				className: "rounded-md border border-line bg-panel p-4",
				children: [/* @__PURE__ */ (0, import_jsx_runtime.jsx)("h2", {
					className: "text-lg font-semibold",
					children: "Phones"
				}), /* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
					className: "mt-2 text-sm text-pretty text-muted",
					children: "Galaxy A72: Android 11 through 14, last OS update 6 January 2024, Snapdragon 720G. Galaxy A30s: Android 9 through 11. The phone was not confirmed, so minSdk is 28 and targetSdk is 35."
				})]
			}),
			/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("article", {
				className: "rounded-md border border-line bg-panel p-4",
				children: [/* @__PURE__ */ (0, import_jsx_runtime.jsx)("h2", {
					className: "text-lg font-semibold",
					children: "Deleting the original"
				}), /* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
					className: "mt-2 text-sm text-pretty text-muted",
					children: "From Android 11 on, deleting a camera video shows the system dialog from MediaStore.createDeleteRequest. A background job cannot tap that dialog. The delete is armed once in the database first. A restart must not arm it again. If it fails, both copies stay."
				})]
			}),
			/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("article", {
				className: "rounded-md border border-line bg-panel p-4",
				children: [/* @__PURE__ */ (0, import_jsx_runtime.jsx)("h2", {
					className: "text-lg font-semibold",
					children: "Folders and USB"
				}), /* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
					className: "mt-2 text-sm text-pretty text-muted",
					children: "On Android 11 and later the storage-access picker will not grant the internal root, a reliable SD card root, Download, or Android/data. Converted files go back through MediaStore using the original relative path, or through a folder the user already granted. Never through the USB path, and never to a fallback folder. FAT32 and exFAT are the likely writable USB formats. NTFS is not assumed. Files of 4 GiB or more will be warned before a FAT32 stick is asked to hold them."
				})]
			}),
			/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("article", {
				className: "rounded-md border border-line bg-panel p-4 lg:col-span-2",
				children: [/* @__PURE__ */ (0, import_jsx_runtime.jsx)("h2", {
					className: "text-lg font-semibold",
					children: "Tests"
				}), /* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
					className: "mt-2 text-sm text-pretty text-muted",
					children: "Gradle compiled the core module. Its test worker then died on a local socket before any test method ran. The same class files were executed with JUnit Platform Console: 20 tests, 0 failures. That covers selection, sorting, bad metadata, and the rule that a source delete cannot run twice. Nothing has run on a Galaxy phone."
				})]
			})
		]
	});
}
var STATES = [
	{
		id: "PENDING",
		next: "Start transfer → TRANSFERRING. Cancel → CANCELLED."
	},
	{
		id: "TRANSFERRING",
		next: "Checksum match → TRANSFER_VERIFIED. Failure keeps the original → FAILED. A repeat start does not delete anything."
	},
	{
		id: "TRANSFER_VERIFIED",
		next: "The only state that may arm a source delete. The arm flips once. A second arm does nothing."
	},
	{
		id: "SOURCE_DELETED",
		next: "Conversion may start. Retry after a later conversion failure returns here, not to a new transfer."
	},
	{
		id: "CONVERTING",
		next: "Validated output → CONVERTED. Failure → FAILED. The USB original is not deleted."
	},
	{
		id: "CONVERTED",
		next: "Finished. Cancel is rejected."
	},
	{
		id: "NEEDS_ATTENTION",
		next: "Pauses that file only and remembers the state to resume. Used for a denied delete, a missing USB drive, or a folder the app cannot write."
	},
	{
		id: "FAILED",
		next: "Retry goes to PENDING if the phone still has the file, or SOURCE_DELETED if the phone copy is already gone."
	},
	{
		id: "CANCELLED",
		next: "Does not delete the phone file, the USB copy, or a finished MP4."
	}
];
function PipelineTab() {
	const [active, setActive] = (0, import_react.useState)("TRANSFER_VERIFIED");
	const current = STATES.find((state) => state.id === active) ?? STATES[0];
	return /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", {
		className: "grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,18rem)]",
		children: [/* @__PURE__ */ (0, import_jsx_runtime.jsxs)("div", { children: [/* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
			className: "mb-3 max-w-2xl text-sm text-pretty text-muted",
			children: "One file at a time. Media3 Transformer will do the later MP4 step: H.264, AAC when the source has audio, 480p or 720p, aspect kept, no upscale. FFmpegKit is retired and is not linked. A temp file on USB is never treated as a finished move."
		}), /* @__PURE__ */ (0, import_jsx_runtime.jsx)("ol", {
			className: "grid gap-2 sm:grid-cols-3",
			children: STATES.map((state) => /* @__PURE__ */ (0, import_jsx_runtime.jsx)("li", { children: /* @__PURE__ */ (0, import_jsx_runtime.jsx)("button", {
				type: "button",
				onClick: () => setActive(state.id),
				"aria-pressed": active === state.id,
				className: `h-11 w-full rounded-sm px-2 font-mono text-xs ${active === state.id ? "bg-signal text-signal-ink" : "bg-panel text-ink"}`,
				children: state.id
			}) }, state.id))
		})] }), /* @__PURE__ */ (0, import_jsx_runtime.jsxs)("aside", {
			className: "rounded-md border border-line bg-panel p-4",
			children: [/* @__PURE__ */ (0, import_jsx_runtime.jsx)("h2", {
				className: "font-mono text-sm",
				children: current.id
			}), /* @__PURE__ */ (0, import_jsx_runtime.jsx)("p", {
				className: "mt-2 text-sm text-pretty text-muted",
				children: current.next
			})]
		})]
	});
}
var SplitComponent = VideoMoverReview;
//#endregion
export { SplitComponent as component };
