/**
 * The web client's pure patch parser — a port of the desktop's `parse_patch`
 * (`crates/ui/src/changes.rs:432`) and the resolution/scope helpers used by
 * the Changes view. The parser is tolerant: unknown header lines are
 * skipped, truncated hunks keep what parsed so far, and file statuses are
 * inferred from the standard git header fields. The model is shared by the
 * tool-diff transcript rows (`lib/transcript.ts`) and the standalone changes
 * surface, so both surfaces draw identical rows.
 *
 * Colors come from the theme's `--rb-diff-add`/`--rb-diff-delete`/`--rb-diff-hunk`
 * tokens; per-line syntax tokens reuse `lib/syntax.ts` and the theme's
 * `--rb-syntax-*` roles. No color is hardcoded here.
 */

/** One source line on the old or new side of a patch hunk. */
export type LineKind = "context" | "add" | "del" | "meta";

export interface DiffLine {
  readonly kind: LineKind;
  readonly oldNo: number | null;
  readonly newNo: number | null;
  readonly text: string;
}

export interface Hunk {
  readonly header: string;
  readonly lines: readonly DiffLine[];
}

export type FileStatus = "added" | "deleted" | "modified" | "renamed";

export interface FileDiff {
  /** Display path (post-change side). */
  readonly path: string;
  /** Pre-rename path when different. */
  readonly oldPath: string | null;
  readonly status: FileStatus;
  readonly binary: boolean;
  /** Parser-collected notices (mode changes, etc.). */
  readonly notices: readonly string[];
  readonly hunks: readonly Hunk[];
  readonly additions: number;
  readonly deletions: number;
  /**
   * Largest line number on either side — drives the gutter width analytically
   * (a fixed column overflowed past 4 digits; user report).
   */
  readonly maxLine: number;
}

const GUTTER_WIDTH = 36;

/** Width of one line-number gutter column, fitted to the file's max line. */
export function gutterWidth(file: FileDiff): number {
  const digits = Math.max(1, Math.floor(Math.log10(Math.max(file.maxLine, 1)))) + 1;
  return Math.max(GUTTER_WIDTH, digits * 6.6 + 8 + 6);
}

/** Strip the `a/` or `b/` prefix git uses on file paths. */
function stripGitPrefix(path: string): string {
  if (path.startsWith("a/") || path.startsWith("b/")) {
    return path.slice(2);
  }
  return path;
}

/** Unquote a quoted git path (spaces / unicode). */
function unquote(s: string): string {
  const trimmed = s.trim();
  if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
    return trimmed
      .slice(1, -1)
      .replace(/\\"/g, '"')
      .replace(/\\\\/g, "\\");
  }
  return trimmed;
}

/**
 * Split the tail of a `diff --git a/… b/…` line into (old, next) paths.
 * Handles quoted paths; for unquoted paths with spaces favors the last ` b/`
 * separator — git's own convention.
 */
function parseGitPaths(rest: string): { old: string; next: string } {
  let bIx = rest.lastIndexOf(" b/");
  if (bIx < 0) {
    bIx = rest.lastIndexOf(' "b/');
  }
  if (bIx >= 0) {
    const oldPart = rest.slice(0, bIx);
    const newPart = rest.slice(bIx + 1);
    const old = stripGitPrefix(unquote(oldPart));
    const next = stripGitPrefix(unquote(newPart));
    return { old, next };
  }
  const single = stripGitPrefix(unquote(rest));
  return { old: single, next: single };
}

/**
 * Parse one `@@ -a[,b] +c[,d] @@ …` header into starting line numbers on
 * each side (one-based). The count fields are ignored — the row model
 * carries `oldNo`/`newNo` per line.
 */
function parseHunkHeader(line: string): { oldStart: number; newStart: number } | null {
  const rest = line.startsWith("@@") ? line.slice(2) : null;
  if (rest === null) {
    return null;
  }
  const minusIx = rest.indexOf("-");
  const plusIx = rest.indexOf("+");
  if (minusIx < 0 || plusIx < 0 || plusIx < minusIx) {
    return null;
  }
  const oldField = rest
    .slice(minusIx + 1, plusIx)
    .split(/[,\s]/)
    .find((part) => part.length > 0);
  const newField = rest
    .slice(plusIx + 1)
    .split(/[,\s]/)
    .find((part) => part.length > 0);
  if (oldField === undefined || newField === undefined) {
    return null;
  }
  const oldStart = Number.parseInt(oldField, 10);
  const newStart = Number.parseInt(newField, 10);
  if (!Number.isFinite(oldStart) || !Number.isFinite(newStart)) {
    return null;
  }
  return { oldStart, newStart };
}

/**
 * Parse a unified git patch into file sections. Tolerant: unknown header
 * lines are skipped, truncated hunks keep what parsed so far.
 */
export function parsePatch(patch: string): FileDiff[] {
  const files: MutableFileDiff[] = [];
  let inHunk = false;
  let oldNo = 0;
  let newNo = 0;

  const flushFile = (raw: string, current: MutableFileDiff | undefined): void => {
    if (current === undefined) {
      return;
    }
    if (raw.startsWith("new file mode")) {
      current.status = "added";
    } else if (raw.startsWith("deleted file mode")) {
      current.status = "deleted";
    } else if (raw.startsWith("rename from ")) {
      current.status = "renamed";
      current.oldPath = raw.slice("rename from ".length).trim();
    } else if (raw.startsWith("rename to ")) {
      current.status = "renamed";
      current.path = raw.slice("rename to ".length).trim();
    } else if (raw.startsWith("Binary files") || raw.startsWith("GIT binary patch")) {
      current.binary = true;
    } else if (raw.startsWith("new mode ")) {
      current.notices.push(`Mode changed to ${raw.slice("new mode ".length).trim()}`);
    } else if (raw.startsWith("+++ ")) {
      const path = raw.slice(4).trim();
      if (path === "/dev/null") {
        current.status = "deleted";
      } else if (current.oldPath === null) {
        current.path = stripGitPrefix(path);
      }
    } else if (raw.startsWith("--- ") && raw.slice(4).trim() === "/dev/null") {
      current.status = "added";
    }
    // index …, similarity index …, old mode … etc. — silently skipped.
  };

  for (const raw of patch.split(/\r?\n/)) {
    if (raw.startsWith("diff --git ")) {
      const rest = raw.slice("diff --git ".length);
      const { old, next } = parseGitPaths(rest);
      const oldPath = old !== next ? old : null;
      files.push({
        path: next,
        oldPath,
        status: "modified",
        binary: false,
        notices: [],
        hunks: [],
        additions: 0,
        deletions: 0,
        maxLine: 0,
      });
      inHunk = false;
      continue;
    }
    const file = files[files.length - 1];
    if (file === undefined) {
      continue;
    }

    if (raw.startsWith("@@")) {
      const header = parseHunkHeader(raw);
      if (header !== null) {
        oldNo = header.oldStart;
        newNo = header.newStart;
        file.hunks.push({ header: raw, lines: [] });
        inHunk = true;
      }
      continue;
    }

    if (inHunk) {
      const marker = raw[0];
      const body = raw.slice(1);
      if (marker === "+") {
        file.additions += 1;
        const line: DiffLine = { kind: "add", oldNo: null, newNo, text: body };
        newNo += 1;
        file.hunks[file.hunks.length - 1]!.lines.push(line);
        file.maxLine = Math.max(file.maxLine, line.oldNo ?? 0, line.newNo ?? 0);
        continue;
      }
      if (marker === "-") {
        file.deletions += 1;
        const line: DiffLine = { kind: "del", oldNo, newNo: null, text: body };
        oldNo += 1;
        file.hunks[file.hunks.length - 1]!.lines.push(line);
        file.maxLine = Math.max(file.maxLine, line.oldNo ?? 0, line.newNo ?? 0);
        continue;
      }
      if (marker === " " || marker === undefined) {
        const line: DiffLine = { kind: "context", oldNo, newNo, text: body };
        oldNo += 1;
        newNo += 1;
        file.hunks[file.hunks.length - 1]!.lines.push(line);
        file.maxLine = Math.max(file.maxLine, line.oldNo ?? 0, line.newNo ?? 0);
        continue;
      }
      if (marker === "\\") {
        const line: DiffLine = { kind: "meta", oldNo: null, newNo: null, text: raw.replace(/^\\/, "").trim() };
        file.hunks[file.hunks.length - 1]!.lines.push(line);
        continue;
      }
      // Non-hunk line ends the hunk; reprocess as a header.
      inHunk = false;
    }

    flushFile(raw, file);
  }

  // Seal each file's data into a readonly value, applying the parser notices.
  return files.map((file): FileDiff => {
    const notices: string[] = [];
    switch (file.status) {
      case "added":
        notices.push("New file");
        break;
      case "deleted":
        notices.push("Deleted file");
        break;
      case "renamed":
        notices.push(`Renamed from ${file.oldPath ?? "?"}`);
        break;
      case "modified":
        break;
    }
    if (file.binary) {
      notices.push("Binary file — contents not shown");
    }
    notices.push(...file.notices);
    return {
      path: file.path,
      oldPath: file.oldPath,
      status: file.status,
      binary: file.binary,
      notices,
      hunks: file.hunks.map((h) => ({ header: h.header, lines: h.lines })),
      additions: file.additions,
      deletions: file.deletions,
      maxLine: file.maxLine,
    };
  });
}

interface MutableFileDiff {
  path: string;
  oldPath: string | null;
  status: FileStatus;
  binary: boolean;
  notices: string[];
  hunks: { header: string; lines: DiffLine[] }[];
  additions: number;
  deletions: number;
  maxLine: number;
}

/**
 * Resolve a per-checkout diff list to the diff that matches the given chat.
 * `checkout_id` first, then device+cwd, then cwd alone — desktop parity
 * (`crates/ui/src/changes.rs::resolve_diff`).
 */
export function resolveDiff<T extends { readonly checkoutId: string; readonly deviceId: string; readonly cwd: string }>(
  diffs: readonly T[],
  chat: { readonly checkoutId: string | null; readonly deviceId: string; readonly cwd: string | null },
): T | null {
  if (chat.checkoutId !== null) {
    const match = diffs.find((d) => d.checkoutId === chat.checkoutId);
    if (match !== undefined) {
      return match;
    }
  }
  const cwd = chat.cwd;
  if (cwd === null) {
    return null;
  }
  const local = diffs.find((d) => d.deviceId === chat.deviceId && d.cwd === cwd);
  if (local !== undefined) {
    return local;
  }
  return diffs.find((d) => d.cwd === cwd) ?? null;
}

export type DiffPhase = "preparing" | "clean" | "list";

export function diffPhase(resolved: { readonly patch: string; readonly files: readonly unknown[] } | null): DiffPhase {
  if (resolved === null) {
    return "preparing";
  }
  if (resolved.patch.trim().length === 0 && resolved.files.length === 0) {
    return "clean";
  }
  return "list";
}

export type DiffScope = "workingTree" | "branch" | "turn";

export const DIFF_SCOPE_LABELS: Readonly<Record<DiffScope, string>> = {
  workingTree: "Working tree",
  branch: "Branch changes",
  turn: "Latest turn",
};

/** Wire value for `GetCheckoutDiff` `mode`. */
export function scopeMode(scope: DiffScope): string {
  switch (scope) {
    case "workingTree":
      return "workingTree";
    case "branch":
      return "branch";
    case "turn":
      return "turn";
  }
}

export interface ScopeLabelInputs {
  readonly scope: DiffScope;
  readonly count: number;
  readonly base?: string | null;
}

export function scopeLabel({ scope, count, base }: ScopeLabelInputs): string {
  const files = count === 1 ? "file" : "files";
  switch (scope) {
    case "workingTree":
      return count === 1 ? "1 Uncommitted change" : `${count} Uncommitted changes`;
    case "branch":
      return base !== undefined && base !== null
        ? `${count} Changed ${files} vs ${base}`
        : `${count} Changed ${files}`;
    case "turn":
      return `${count} Changed ${files} this turn`;
  }
}

/** Empty-state copy per scope. */
export function cleanMessage(scope: DiffScope, base: string | null): string {
  switch (scope) {
    case "workingTree":
      return "No uncommitted changes";
    case "branch":
      return base === null ? "No branch changes" : `No changes vs ${base}`;
    case "turn":
      return "No changes this turn";
  }
}

/**
 * Default base for the branch scope: first branch that's not the current one,
 * else `main`/`master` if present, else first entry.
 */
export function defaultBaseRef(branches: readonly string[], current: string | null): string | null {
  const first = branches[0];
  if (first === undefined) {
    return null;
  }
  if (current !== first) {
    return first;
  }
  for (const candidate of ["main", "master"]) {
    if (branches.includes(candidate)) {
      return candidate;
    }
  }
  const other = branches.find((branch) => branch !== current);
  return other ?? first;
}

/**
 * Pair a hunk's lines into split rows. Pure & index-only, the desktop
 * reference (`changes.rs::split_pairs`). A run of deletions followed by
 * additions yields one paired row per line; the longer side's leftovers
 * become one-sided rows. A deletion arriving after additions opens a new
 * block. The marker (`\ No newline at end of file`) belongs to whichever
 * side it trails and pairs with itself, never with code.
 */
export type SplitPair = readonly [number | null, number | null];

export function splitPairs(lines: readonly DiffLine[]): SplitPair[] {
  return splitPairsUpto(lines, Number.POSITIVE_INFINITY);
}

export function splitPairsUpto(lines: readonly DiffLine[], maxRows: number): SplitPair[] {
  const pairs: SplitPair[] = [];
  let dels: number[] = [];
  let adds: number[] = [];
  let delMeta: number[] = [];
  let addMeta: number[] = [];
  let pending: LineKind | null = null;

  const flush = (): void => {
    const drain = (left: number[], right: number[]): void => {
      const len = Math.max(left.length, right.length);
      for (let ix = 0; ix < len; ix += 1) {
        if (pairs.length >= maxRows) {
          break;
        }
        const l = ix < left.length ? left[ix]! : null;
        const r = ix < right.length ? right[ix]! : null;
        pairs.push([l, r] as const);
      }
      left.length = 0;
      right.length = 0;
    };
    drain(dels, adds);
    drain(delMeta, addMeta);
  };

  for (let ix = 0; ix < lines.length; ix += 1) {
    const line = lines[ix]!;
    switch (line.kind) {
      case "del": {
        if (adds.length > 0 || delMeta.length > 0 || addMeta.length > 0) {
          flush();
        }
        const remaining = maxRows - pairs.length;
        if (remaining === 0) {
          break;
        }
        if (dels.length < remaining) {
          dels.push(ix);
        }
        pending = "del";
        break;
      }
      case "add": {
        if (addMeta.length > 0) {
          flush();
        }
        const remaining = maxRows - pairs.length;
        if (remaining === 0) {
          break;
        }
        if (adds.length < remaining) {
          adds.push(ix);
        }
        pending = "add";
        break;
      }
      case "meta": {
        if (pending === "del") {
          delMeta.push(ix);
        } else if (pending === "add") {
          addMeta.push(ix);
        } else {
          delMeta.push(ix);
          addMeta.push(ix);
        }
        break;
      }
      case "context": {
        flush();
        if (pairs.length >= maxRows) {
          break;
        }
        pairs.push([ix, ix] as const);
        pending = "context";
        break;
      }
    }
  }
  flush();
  return pairs.slice(0, maxRows);
}

/**
 * Fold one pair of split indices into two line refs, or `null` on empty
 * sides. Used to bind review comments to a row.
 */
export function pairLine(lines: readonly DiffLine[], pair: SplitPair): { left: DiffLine | null; right: DiffLine | null } {
  const left = pair[0] !== null ? lines[pair[0]] ?? null : null;
  const right = pair[1] !== null ? lines[pair[1]] ?? null : null;
  return { left, right };
}

/** Flatten one file into a sequence of header + line rows for a virtualized list. */
export type DiffRow =
  | { readonly kind: "fileHeader"; readonly id: string; readonly file: FileDiff; readonly fileIx: number; readonly expanded: boolean }
  | { readonly kind: "hunkHeader"; readonly id: string; readonly file: FileDiff; readonly hunkIx: number }
  | { readonly kind: "line"; readonly id: string; readonly file: FileDiff; readonly hunkIx: number; readonly lineIx: number }
  | { readonly kind: "notice"; readonly id: string; readonly file: FileDiff; readonly messageIx: number };

/**
 * Build the row list for one file given the file's expanded/collapsed state.
 * Collapsed files emit only the header so the virtualizer skips them outright
 * (the desktop's list() at line granularity — `changes.rs`).
 */
export function flattenFileRows(file: FileDiff, fileIx: number, expanded: boolean): readonly DiffRow[] {
  const rows: DiffRow[] = [{ kind: "fileHeader", id: rowId(fileIx, "h", 0), file, fileIx, expanded }];
  if (!expanded) {
    return rows;
  }
  for (let ix = 0; ix < file.notices.length; ix += 1) {
    rows.push({ kind: "notice", id: rowId(fileIx, "n", ix), file, messageIx: ix });
  }
  for (let hunkIx = 0; hunkIx < file.hunks.length; hunkIx += 1) {
    rows.push({ kind: "hunkHeader", id: rowId(fileIx, "k", hunkIx), file, hunkIx });
    for (let lineIx = 0; lineIx < file.hunks[hunkIx]!.lines.length; lineIx += 1) {
      rows.push({ kind: "line", id: rowId(fileIx, `${hunkIx}.${lineIx}`, 0), file, hunkIx, lineIx });
    }
  }
  return rows;
}

function rowId(fileIx: number, scope: string, ix: number): string {
  return `${fileIx}:${scope}:${ix}`;
}

/** Estimate the rendered height of one row (analytic until measured). */
export function estimateRowHeight(row: DiffRow): number {
  switch (row.kind) {
    case "fileHeader":
      return 38;
    case "hunkHeader":
      return 28;
    case "notice":
      return 24;
    case "line":
      return 21;
  }
}

/**
 * Build the per-file patch-key fingerprint: a checksum + scope pair, folded
 * into the parse cache key. The desktop calls this `parse_key`
 * (`changes.rs:1867`); identical text returns the same identity.
 */
export function parseKey(checkoutId: string, checksum: string, scope: DiffScope, baseRef: string | null): string {
  const base = baseRef ?? "";
  return `${checkoutId}:${checksum}:${scope}:${base}`;
}

/**
 * Summarize the per-file additions + deletions — the corner badge on a file
 * header (`+12 -4`).
 */
export function fileCounts(file: FileDiff): string {
  if (file.binary) {
    return "Binary";
  }
  if (file.additions === 0 && file.deletions === 0) {
    return "";
  }
  const add = file.additions > 0 ? `+${file.additions}` : "";
  const del = file.deletions > 0 ? `-${file.deletions}` : "";
  return add.length > 0 && del.length > 0 ? `${add} ${del}` : add + del;
}

/**
 * Group consecutive non-add lines as additions for the file header's
 * `+12 −4` summary. The web keeps the per-file totals only — full per-line
 * group folding is for tool-diff chips in the transcript, not the standalone
 * changes view.
 */