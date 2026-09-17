/**
 * The add-space palette's pure path/search logic — line-for-line ports of the
 * desktop's folder-browser helpers (`crates/ui/src/pickers.rs:296-401`,
 * `crates/ui/src/shell/spaces.rs:254-260` and `:497-500`) plus the
 * filter/completion derivations the flow renders from
 * (`spaces.rs:2011-2166`). No engine dependency anywhere in this file.
 */

import type { DriveEntry, FolderEntry } from "@roboco/proto";
import { filterIndices } from "./picker-search";

/** `parent_path` (pickers.rs:296-306): the parent of an absolute path; `null`
 *  at the filesystem root. All trailing `/` are trimmed first. */
export function parentPath(path: string): string | null {
  const trimmed = path.replace(/\/+$/, "");
  if (trimmed.length === 0) {
    return null;
  }
  const at = trimmed.lastIndexOf("/");
  if (at === 0) {
    return "/";
  }
  if (at === -1) {
    return null;
  }
  return trimmed.slice(0, at);
}

/** `child_path` (pickers.rs:309-315): join a listing path and an entry name. */
export function childPath(base: string, name: string): string {
  return base.endsWith("/") ? `${base}${name}` : `${base}/${name}`;
}

/**
 * `completion_prefix_len` (pickers.rs:321-332): length of `name`'s prefix
 * matching `query`, compared code-point by code-point case-insensitively;
 * `null` when `query` is not a prefix of `name`. The length indexes into
 * `name` (not `query`) at a code-point boundary, so slicing keeps the
 * folder's real casing: `("Documents", "doc") → 3` → `"uments"`. An empty
 * query yields 0; a query longer than the name yields null.
 */
export function completionPrefixLen(name: string, query: string): number | null {
  const nameChars = Array.from(name);
  let len = 0;
  let next = 0;
  for (const queryChar of query) {
    if (next >= nameChars.length) {
      return null;
    }
    const nameChar = nameChars[next]!;
    if (nameChar.toLowerCase() !== queryChar.toLowerCase()) {
      return null;
    }
    len += nameChar.length;
    next += 1;
  }
  return len;
}

/**
 * `segment_target` (pickers.rs:338-354): resolve a slash-descend query
 * against folder names — exact case-sensitive match first, then an exact
 * case-insensitive match, then a UNIQUE case-insensitive prefix. Ambiguity
 * resolves to null: the slash stays in the query as an honest "no match".
 */
export function segmentTarget(names: readonly string[], query: string): number | null {
  const exact = names.findIndex((name) => name === query);
  if (exact >= 0) {
    return exact;
  }
  const insensitive = names.findIndex((name) => completionPrefixLen(name, query) === name.length);
  if (insensitive >= 0) {
    return insensitive;
  }
  const hits: number[] = [];
  for (let ix = 0; ix < names.length; ix += 1) {
    if (completionPrefixLen(names[ix] ?? "", query) !== null) {
      hits.push(ix);
    }
  }
  return hits.length === 1 ? (hits[0] as number) : null;
}

/**
 * `typed_path_target` (pickers.rs:361-384): interpret a query as a typed
 * path jump — absolute (`/disk2`) or home-relative (`~`, `~/github`) — and
 * return the absolute path to browse, trailing `/` trimmed. `~` cannot
 * expand before home is known (null); `~foo` is a folder name, not a path.
 */
export function typedPathTarget(query: string, home: string | null): string | null {
  const trimmed = query.trim();
  if (trimmed.startsWith("~")) {
    if (home === null) {
      return null;
    }
    const base = home.replace(/\/+$/, "");
    const rest = trimmed.slice(1);
    if (rest.length === 0) {
      return base;
    }
    if (!rest.startsWith("/")) {
      return null;
    }
    const sub = rest.slice(1).replace(/\/+$/, "");
    return sub.length === 0 ? base : `${base}/${sub}`;
  }
  if (trimmed.startsWith("/")) {
    const absolute = trimmed.replace(/\/+$/, "");
    return absolute.length === 0 ? "/" : absolute;
  }
  return null;
}

/** `breadcrumbs` (pickers.rs:387-396): `(label, full path)` pairs for a path,
 *  root first, accumulating the full path as segments are walked. */
export function breadcrumbs(path: string): Array<[string, string]> {
  const out: Array<[string, string]> = [["/", "/"]];
  let acc = "";
  for (const segment of path.split("/")) {
    if (segment.length === 0) {
      continue;
    }
    acc += `/${segment}`;
    out.push([segment, acc]);
  }
  return out;
}

/** `browser_rows` (pickers.rs:399-401): a listing's directory entries —
 *  files never render in the folder browser. */
export function browserRows(entries: readonly FolderEntry[]): FolderEntry[] {
  return entries.filter((entry) => entry.isDir);
}

/** `path_under` (spaces.rs:497-500): segment-aware "is `path` at or under
 *  `base`" — `/media/a` is under `/media` but not under `/media/ab`. An
 *  empty/root base covers everything. */
export function pathUnder(path: string, base: string): boolean {
  const trimmed = base.replace(/\/+$/, "");
  return trimmed.length === 0 || path === trimmed || path.startsWith(`${trimmed}/`);
}

/** `manual_path_query` (spaces.rs:254-260): true when the trimmed text reads
 *  as a typed path — absolute, home-relative, backslash-leading, or a
 *  Windows drive letter (`C:`). Kept unconditionally: the browsed ENGINE
 *  may be a Windows machine even when the browser is not. */
export function manualPathQuery(text: string): boolean {
  const trimmed = text.trim();
  return (
    trimmed.startsWith("/") ||
    trimmed.startsWith("~") ||
    trimmed.startsWith("\\") ||
    trimmed.charAt(1) === ":"
  );
}

/**
 * `add_space_filtered` (spaces.rs:2011-2029): the current listing's folder
 * rows filtered by the search query — directories only, the hidden-name
 * filter applied client-side (cheap insurance against a stale listing),
 * then ranked via `filterIndices` (prefix matches first).
 */
export function filteredFolders(entries: readonly FolderEntry[], query: string): FolderEntry[] {
  const dirs = entries.filter(
    (entry) => entry.isDir && (query.startsWith(".") || !entry.name.startsWith(".")),
  );
  const names = dirs.map((entry) => entry.name);
  return filterIndices(query, names).map((ix) => dirs[ix] as FolderEntry);
}

/**
 * `add_space_completion` (spaces.rs:2135-2154): the tab-completion target —
 * the highlighted row when the query prefixes its name, else the first
 * prefix match (filtering ranks those first). `(name, suffix)`, null on an
 * empty query or when the match is already complete.
 */
export function addSpaceCompletion(
  rows: readonly FolderEntry[],
  active: number,
  query: string,
): { name: string; suffix: string } | null {
  if (query.length === 0) {
    return null;
  }
  const highlighted = rows[active];
  const entry =
    highlighted !== undefined && completionPrefixLen(highlighted.name, query) !== null
      ? highlighted
      : (rows.find((row) => completionPrefixLen(row.name, query) !== null) ?? null);
  if (entry === null) {
    return null;
  }
  const len = completionPrefixLen(entry.name, query);
  if (len === null || len >= entry.name.length) {
    return null;
  }
  return { name: entry.name, suffix: entry.name.slice(len) };
}

// ---------------------------------------------------------------------------
// Stale-response guard (`AddSpaceFlow::is_stale`, spaces.rs:234-243)
// ---------------------------------------------------------------------------

/** The request-time snapshot every async response carries. `revision` is
 *  null for loads that key off the browser path instead (ListFolders,
 *  ListDrives), exactly the desktop's `Option<u64>`. */
export interface StaleGuard {
  readonly identity: string;
  readonly revision: number | null;
  readonly deviceId: string | null;
}

/** The flow fields the guard compares against. */
export interface StaleFlowFields {
  readonly identity: string;
  readonly revision: number;
  readonly deviceId: string | null;
}

/**
 * `is_stale`: a response is dropped once the flow was reset (identity), the
 * browse advanced past the request (revision, when the caller passed one),
 * or the device changed underneath it. Strictly this palette's async guard —
 * nothing to do with chat/session staleness.
 */
export function isStaleResponse(flow: StaleFlowFields, request: StaleGuard): boolean {
  return (
    flow.identity !== request.identity ||
    (request.revision !== null && flow.revision !== request.revision) ||
    flow.deviceId !== request.deviceId
  );
}

// ---------------------------------------------------------------------------
// Rail Locations + breadcrumb folding (spaces.rs:2596-2622, 2741-2763)
// ---------------------------------------------------------------------------

/** One row of the rail's Locations section: home, or a drive by index. */
export type LocationRow = { kind: "home" } | { kind: "drive"; index: number };

/**
 * The rail's active Locations row: the root that owns the browsed path.
 * Longest mount prefix wins; home carries a +1 weight so it outranks a
 * drive that covers it (the System `/` row covers everything).
 */
export function activeLocation(
  path: string | null,
  home: string | null,
  drives: readonly DriveEntry[],
): LocationRow | null {
  if (path === null) {
    return null;
  }
  let best: { len: number; row: LocationRow } | null = null;
  if (home !== null && pathUnder(path, home)) {
    best = { len: home.replace(/\/+$/, "").length + 1, row: { kind: "home" } };
  }
  for (let ix = 0; ix < drives.length; ix += 1) {
    const drive = drives[ix] as DriveEntry;
    if (!pathUnder(path, drive.path)) {
      continue;
    }
    const len = drive.path.replace(/\/+$/, "").length;
    if (best === null || len > best.len) {
      best = { len, row: { kind: "drive", index: ix } };
    }
  }
  return best === null ? null : best.row;
}

function segmentCount(path: string): number {
  return path.split("/").filter((segment) => segment.length > 0).length;
}

/**
 * How many of `breadcrumbs`' entries the device/drive crumbs absorb
 * (spaces.rs:2755-2763): the root crumb always folds (the device name
 * stands in for it); a drive's mount folds its segments into the drive
 * crumb, else home's segments fold into the device crumb when the browsed
 * path sits at/under home. The caller skips this many entries.
 */
export function crumbFold(listingPath: string, home: string | null, driveMount: string | null): number {
  if (driveMount !== null) {
    return 1 + segmentCount(driveMount);
  }
  if (home !== null && (listingPath === home || listingPath.startsWith(`${home}/`))) {
    return 1 + segmentCount(home);
  }
  return 1;
}
