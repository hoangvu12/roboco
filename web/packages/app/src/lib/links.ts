/**
 * Link destination policy — the web port of the desktop's
 * `crates/ui/src/browser/model.rs` link validation
 * (`transcript_address`/`normalize_address`), the width-bounded label
 * truncation of `crates/ui/src/markdown/link_presentation.rs` (`truncate` +
 * `OffsetMap`), and the safe workspace-file resolution of
 * `crates/ui/src/workspace_links.rs` (`resolve_workspace_file_link`).
 *
 * Pure functions only: the renderer decides what a validated, internal or
 * rejected destination looks like, and the tests drive the accept/reject
 * tables straight from `markdown/links.rs` and `workspace_links.rs`.
 */

// ---------------------------------------------------------------------------
// Address validation (browser/model.rs)
// ---------------------------------------------------------------------------

/** Rust `char::is_control` — Unicode Cc (C0 + 0x7F..0x9F). */
function isControlChar(code: number): boolean {
  return code <= 0x1f || (code >= 0x7f && code <= 0x9f);
}

/** `loopback` (model.rs) — localhost names and loopback IPs. */
function isLoopback(url: URL): boolean {
  const host = url.hostname;
  if (host === "localhost" || host.endsWith(".localhost")) {
    return true;
  }
  if (/^127\.\d+\.\d+\.\d+$/.test(host) || host === "[::1]") {
    return true;
  }
  return false;
}

/**
 * `normalize_address` (model.rs:37-74): trim, reject controls, infer an
 * https:// scheme for bare hosts (never for a bare host:port that only looks
 * like a scheme), parse, then allow only http/https with a host and no
 * embedded credentials. Returns the normalized URL, or null when rejected.
 */
export function normalizeAddress(input: string): string | null {
  const text = input.trim();
  if (text.length === 0) {
    return null;
  }
  for (const c of text) {
    if (isControlChar(c.codePointAt(0)!)) {
      return null;
    }
  }
  // A bare host:port looks like a URI scheme to a URL parser. Only accept
  // that ambiguity when the suffix is an actual numeric port.
  const authority = text.split(/[/?#]/, 1)[0] ?? text;
  const colon = authority.lastIndexOf(":");
  const port = colon >= 0 ? authority.slice(colon + 1) : "";
  const hostPort = colon >= 0 && port.length > 0 && /^\d+$/.test(port);
  const explicit = text.includes("://") || (text.includes(":") && !hostPort && !text.startsWith("["));
  let parsed: URL;
  try {
    parsed = new URL(explicit ? text : `https://${text}`);
  } catch {
    return null;
  }
  if ((parsed.protocol !== "http:" && parsed.protocol !== "https:") || parsed.hostname.length === 0) {
    return null;
  }
  if (parsed.username.length > 0 || parsed.password.length > 0) {
    return null;
  }
  if (!explicit && isLoopback(parsed)) {
    parsed.protocol = "http:";
  }
  return parsed.href;
}

/**
 * `transcript_address` (model.rs:76-97) — the transcript's stricter gate:
 * chat links require an explicit web authority; never infer a scheme or
 * silently strip controls, credentials, malformed escapes or backslashes.
 * Returns the normalized destination, or null when the link stays inert.
 */
export function transcriptAddress(input: string): string | null {
  const lower = input.toLowerCase();
  const scheme = lower.startsWith("https://")
    ? "https://"
    : lower.startsWith("http://")
      ? "http://"
      : null;
  if (scheme === null) {
    return null;
  }
  const authority = lower.slice(scheme.length);
  if (
    [...input].some((c) => {
      const code = c.codePointAt(0)!;
      return isControlChar(code) || /\s/.test(c);
    }) ||
    input.includes("\\") ||
    authority.length === 0 ||
    authority.startsWith("/") ||
    authority.startsWith("?") ||
    authority.startsWith("#")
  ) {
    return null;
  }
  for (let i = 0; i < input.length; i++) {
    if (input.charCodeAt(i) === 0x25 && !isHexPair(input, i + 1)) {
      return null;
    }
  }
  return normalizeAddress(input);
}

function isHexPair(text: string, at: number): boolean {
  return isHexDigit(text.charCodeAt(at)) && isHexDigit(text.charCodeAt(at + 1));
}

function isHexDigit(code: number | undefined): boolean {
  return (
    code !== undefined &&
    ((code >= 0x30 && code <= 0x39) || (code >= 0x41 && code <= 0x46) || (code >= 0x61 && code <= 0x66))
  );
}

// ---------------------------------------------------------------------------
// Presentation truncation (link_presentation.rs)
// ---------------------------------------------------------------------------

/** An omission: the original range that was cut, and the `…` that replaced it. */
export interface LinkOmission {
  readonly originalStart: number;
  readonly originalEnd: number;
  readonly shownStart: number;
  readonly shownEnd: number;
}

/**
 * `OffsetMap` (link_presentation.rs:10-49) — maps offsets between the
 * displayed (truncated) text and the original, so selection and copy still
 * resolve against the untruncated source.
 */
export class OffsetMap {
  readonly omissions: readonly LinkOmission[];

  constructor(omissions: readonly LinkOmission[] = []) {
    this.omissions = omissions;
  }

  original(displayed: number): number {
    let shift = 0;
    for (const { originalStart, originalEnd, shownStart, shownEnd } of this.omissions) {
      if (displayed < shownStart) {
        break;
      }
      if (displayed < shownEnd) {
        return originalStart;
      }
      shift = originalEnd - shownEnd;
    }
    return displayed + shift;
  }

  displayed(original: number): number {
    let shift = 0;
    for (const { originalStart, originalEnd, shownStart, shownEnd } of this.omissions) {
      if (original < originalStart) {
        break;
      }
      if (original < originalEnd) {
        return shownStart;
      }
      shift = originalEnd - shownEnd;
    }
    return original - shift;
  }
}

/**
 * Grapheme boundaries as string indices (for the truncation binary search
 * and the destination card's wrap breaks). Falls back to code points on
 * runtimes without `Intl.Segmenter`.
 */
export function graphemeBoundaries(text: string): number[] {
  const ctor = (Intl as { Segmenter?: new (locale?: string, options?: { granularity: string }) => SegmenterLike }).Segmenter;
  if (ctor !== undefined) {
    const out: number[] = [];
    for (const { index } of new ctor(undefined, { granularity: "grapheme" }).segment(text)) {
      out.push(index);
    }
    return out;
  }
  const out: number[] = [];
  let at = 0;
  for (const c of text) {
    out.push(at);
    at += c.length;
  }
  return out;
}

interface SegmenterLike {
  segment(input: string): Iterable<{ index: number }>;
}

export interface LinkTruncationInput {
  /** The whole flattened text of the element. */
  readonly text: string;
  /** Link ranges within `text` (only these are truncation candidates). */
  readonly links: readonly { readonly start: number; readonly end: number; readonly url: string }[];
}

export interface LinkTruncationResult {
  readonly text: string;
  readonly offsets: OffsetMap;
}

/** `'…'.len_utf8()` — the ellipsis's byte length in the source's guard. */
const ELLIPSIS_UTF8_LEN = 3;

/**
 * `truncate` (link_presentation.rs:71-147) — width-bounded label truncation:
 * every link whose flattened label overflows `width` (measured through the
 * caller's `measure`) is cut to a grapheme boundary + `…`, binary-searched on
 * shaped width, and never lets a short label get LONGER just to add the
 * ellipsis. Links that fail destination validation are left alone.
 */
export function linkPresentationTruncate(
  input: LinkTruncationInput,
  width: number,
  measure: (label: string) => number,
): LinkTruncationResult {
  const omissions: LinkOmission[] = [];
  for (const range of input.links) {
    if (transcriptAddress(range.url) === null) {
      continue;
    }
    const label = input.text.slice(range.start, range.end);
    if (measure(label) <= width) {
      continue;
    }
    const boundaries = graphemeBoundaries(label);
    let low = 0;
    let high = boundaries.length;
    while (low < high) {
      const middle = Math.floor((low + high) / 2);
      const candidate = `${label.slice(0, boundaries[middle])}…`;
      if (measure(candidate) <= width) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    const prefix = boundaries[Math.max(0, low - 1)] ?? 0;
    // Never make a short label longer just to show an ellipsis: only cut
    // when the removed text outlives the ellipsis that replaces it (the
    // source compares against `'…'.len_utf8()` = 3, in the same units).
    const removed = input.text.slice(range.start + prefix, range.end);
    if ([...removed].length > ELLIPSIS_UTF8_LEN) {
      omissions.push({ originalStart: range.start + prefix, originalEnd: range.end, shownStart: 0, shownEnd: 0 });
    }
  }

  let text = "";
  let at = 0;
  const recorded: LinkOmission[] = [];
  for (const omission of omissions) {
    text += input.text.slice(at, omission.originalStart);
    const shownStart = text.length;
    text += "…";
    recorded.push({
      originalStart: omission.originalStart,
      originalEnd: omission.originalEnd,
      shownStart,
      shownEnd: shownStart + 1,
    });
    at = omission.originalEnd;
  }
  text += input.text.slice(at);
  return { text, offsets: new OffsetMap(recorded) };
}

/**
 * Zero-width-space grapheme breaks (link_destination.rs:22-25): the hover
 * card wraps even a single long path segment by breaking at graphemes.
 */
export function graphemeBreaks(text: string): string {
  let out = "";
  const boundaries = graphemeBoundaries(text);
  for (let i = 0; i < boundaries.length; i++) {
    const start = boundaries[i]!;
    const end = i + 1 < boundaries.length ? boundaries[i + 1]! : text.length;
    out += text.slice(start, end);
    out += "​";
  }
  return out;
}

// ---------------------------------------------------------------------------
// Workspace file links (workspace_links.rs)
// ---------------------------------------------------------------------------

export interface WorkspaceFileLink {
  readonly path: string;
  readonly line: number | null;
  readonly column: number | null;
  /**
   * An absolute destination no known root owns: still a file link — the
   * linking chat opens it read-only as a host file by absolute path.
   */
  readonly outside: boolean;
}

const FILE_MENTION_SCHEME = "roboco-file:";

/** The path with its `#L12` fragment or `:12` line suffix taken off. */
export function withoutLocation(target: string): string {
  const split = splitLineFragment(target);
  if (split === null) {
    return target;
  }
  const suffix = splitLineSuffix(split.path);
  return suffix === null ? target : suffix.path;
}

/**
 * `resolve_workspace_file_link` (workspace_links.rs, #606) — classify an
 * agent-authored link under the file-link grammar and resolve it against
 * `workspaceRoot`. The destination decodes exactly once (a broken escape
 * keeps the raw spelling — a literal `%` in a file name still opens); a
 * relative target stays inside-or-unresolved, while a POSIX-absolute one
 * inside the root reads like its relative equivalent and anything else
 * resolves as an outside host file. Rejects `?`, controls, backslashes,
 * `.`/`..`/empty segments, scheme-shaped targets, `~`, `file://` with a
 * host, and non-round-tripping file mentions.
 */
export function resolveWorkspaceFileLink(target: string, workspaceRoot: string): WorkspaceFileLink | null {
  const classified = classifyFileLink(target);
  if (classified === null) {
    return null;
  }
  switch (classified.kind) {
    case "mention":
    case "relative": {
      const path = resolveDecodedPath(classified.link.path, workspaceRoot);
      return path === null ? null : { ...classified.link, path, outside: false };
    }
    case "absolute": {
      const path = resolveDecodedPath(classified.link.path, workspaceRoot);
      return path === null
        ? { ...classified.link, outside: true }
        : { ...classified.link, path, outside: false };
    }
  }
}

/** A decoded path plus its line reference, tagged by resolution shape. */
interface ClassifiedLink {
  readonly kind: "mention" | "relative" | "absolute";
  readonly link: { readonly path: string; readonly line: number | null; readonly column: number | null };
}

/**
 * `classify_file_link` (workspace_links.rs) — without a root to resolve
 * against yet. Line references split on the raw target so an escaped `#`
 * or `:` stays inside the path; the path itself then decodes exactly once
 * and every safety check runs on the decoded string.
 */
function classifyFileLink(target: string): ClassifiedLink | null {
  const trimmed = target.trim();
  if (trimmed.length === 0) {
    return null;
  }

  // `roboco-file:` mentions keep their strict canonical spelling: the whole
  // path decodes once and must re-encode to the identical string.
  if (trimmed.startsWith(FILE_MENTION_SCHEME)) {
    const encoded = trimmed.slice(FILE_MENTION_SCHEME.length);
    const decoded = percentDecodePath(encoded);
    if (
      decoded === null ||
      percentEncodePath(decoded) !== encoded ||
      decoded.endsWith("/") ||
      decoded.includes(":") ||
      !cleanPath(decoded)
    ) {
      return null;
    }
    return { kind: "mention", link: { path: decoded, line: null, column: null } };
  }

  const isFileUrl = trimmed.startsWith("file://");
  let raw: string;
  if (isFileUrl) {
    const rest = trimmed.slice("file://".length);
    // Only an empty host keeps this a file path — `file://localhost/…`
    // and friends are ordinary URLs — and a `?query` is never a file.
    const hostEnd = rest.indexOf("/") >= 0 ? rest.indexOf("/") : rest.length;
    if (rest.slice(0, hostEnd).length > 0 || rest.includes("?")) {
      return null;
    }
    raw = rest.slice(hostEnd);
  } else {
    if (trimmed.includes("://") || trimmed.startsWith("mailto:")) {
      return null;
    }
    raw = trimmed;
  }

  const fragment = splitLineFragment(raw);
  if (fragment === null) {
    return null;
  }
  const suffix = splitLineSuffix(fragment.path);
  if (suffix === null) {
    return null;
  }
  const rawPath = suffix.path;
  let decoded: string;
  if (rawPath.includes("%")) {
    // Broken escapes keep the raw spelling — a literal `%` in a file name
    // still opens.
    decoded = percentDecodePath(rawPath) ?? rawPath;
  } else {
    decoded = rawPath;
  }
  if (decoded.length === 0 || !cleanPath(decoded)) {
    return null;
  }
  let kind: "relative" | "absolute";
  if (decoded.startsWith("/")) {
    // Absolute POSIX path: one leading slash, not the root itself, and no
    // trailing slash. A plain absolute path also wants a `.` in its file
    // name (`/usr/bin/ls` stays plain text); `file://` is exempt.
    if (
      [...decoded].length <= 1 ||
      decoded.startsWith("//") ||
      decoded.endsWith("/") ||
      (!isFileUrl && !fileName(decoded).includes("."))
    ) {
      return null;
    }
    kind = "absolute";
  } else {
    if (isFileUrl || decoded.startsWith("~") || hasUrlScheme(decoded)) {
      return null;
    }
    if (!fileName(decoded).includes(".")) {
      return null;
    }
    kind = "relative";
  }
  return {
    kind,
    link: {
      path: decoded,
      line: fragment.line ?? suffix.line,
      column: fragment.column ?? suffix.column,
    },
  };
}

/** `resolve_decoded_path` — the absolute keeps only its root-relative
 * remainder; anything else must already be a clean relative path. */
function resolveDecodedPath(target: string, root: string): string | null {
  const relative = hasRoot(target) ? stripRootPrefix(target, root) : target;
  if (relative === null) {
    return null;
  }
  return safeRelativePath(relative);
}

/** `Path::has_root` for our purposes — a leading separator or drive prefix. */
function hasRoot(path: string): boolean {
  return path.startsWith("/") || path.startsWith("\\") || /^[A-Za-z]:/.test(path);
}

/** `Path::strip_prefix` component-wise (both sides may use `/` or `\`). */
function stripRootPrefix(target: string, root: string): string | null {
  const targetParts = splitComponents(target);
  const rootParts = splitComponents(root);
  if (rootParts.length === 0 || targetParts.length < rootParts.length) {
    return null;
  }
  for (let i = 0; i < rootParts.length; i++) {
    if (targetParts[i] !== rootParts[i]) {
      return null;
    }
  }
  return targetParts.slice(rootParts.length).join("/");
}

function splitComponents(path: string): string[] {
  return path.split(/[\\/]/);
}

/** `safe_relative_path` — every component must be a normal, non-empty name. */
function safeRelativePath(path: string): string | null {
  if (path.length === 0) {
    return null;
  }
  const parts: string[] = [];
  for (const component of path.split("/")) {
    if (component.length === 0 || component === "." || component === "..") {
      return null;
    }
    parts.push(component);
  }
  return parts.length > 0 ? parts.join("/") : null;
}

/** `clean_path` — no `?`, no controls, no `.`/`..`/empty segments; a single
 * leading `/` segment is allowed (it marks an absolute path). */
function cleanPath(path: string): boolean {
  if (path.includes("?") || path.includes("\\")) {
    return false;
  }
  for (const c of path) {
    if (isControlChar(c.codePointAt(0)!)) {
      return false;
    }
  }
  return path
    .split("/")
    .every((part, index) => (part.length !== 0 || index === 0) && part !== "." && part !== "..");
}

function fileName(path: string): string {
  const slash = path.lastIndexOf("/");
  return slash < 0 ? path : path.slice(slash + 1);
}

/** `has_url_scheme` — `scheme:` at the start of a relative path means it is
 * a URL, not a file. */
function hasUrlScheme(path: string): boolean {
  const chars = [...path];
  const first = chars.shift();
  if (first === undefined || !/[A-Za-z]/.test(first)) {
    return false;
  }
  for (const c of chars) {
    if (c === ":") {
      return true;
    }
    if (!/[A-Za-z0-9+\-.]/.test(c)) {
      return false;
    }
  }
  return false;
}

/** A line reference one shape contributed (`split_line_fragment`). */
interface ParsedAnchor {
  readonly line: number | null;
  readonly column: number | null;
}

/** null = not a location (stays whole); { line: null } = a `#`/`:` split
 * that dropped away; invalid anchors return `null` from the split callers
 * by killing the whole target — modeled as `{ invalid: true }`. */
interface SplitResult {
  readonly path: string;
  readonly line: number | null;
  readonly column: number | null;
}

/**
 * `split_line_fragment` — a `#L12`-style fragment (GitHub line anchors,
 * ranges included, all opening at the first line). A non-empty fragment
 * without `/` that is not one of these drops away; `0` and reversed
 * ranges make the whole target not a file link (null return).
 */
function splitLineFragment(target: string): SplitResult | null {
  const hash = target.lastIndexOf("#");
  if (hash < 0) {
    return { path: target, line: null, column: null };
  }
  const path = target.slice(0, hash);
  const fragment = target.slice(hash + 1);
  if (fragment.length === 0) {
    return { path, line: null, column: null };
  }
  if (fragment.includes("/")) {
    // The `#` is inside the path, not an anchor.
    return { path: target, line: null, column: null };
  }
  const anchor = parseAnchor(fragment);
  if (anchor === "invalid") {
    return null;
  }
  if (anchor === "none") {
    return { path, line: null, column: null };
  }
  return { path, line: anchor.line, column: anchor.column };
}

/**
 * `split_line_suffix` — `path:12`, `path:12:5` and `path:12-20` line
 * suffixes. As with fragments, a zero line or a backwards range makes the
 * whole target not a file link (null return).
 */
function splitLineSuffix(target: string): SplitResult | null {
  const colon = target.lastIndexOf(":");
  if (colon < 0) {
    return { path: target, line: null, column: null };
  }
  const last = target.slice(colon + 1);
  const dash = last.indexOf("-");
  if (dash >= 0) {
    const start = last.slice(0, dash);
    const end = last.slice(dash + 1);
    const startNumber = /^\d+$/.test(start) ? Number.parseInt(start, 10) : null;
    const endNumber = /^\d+$/.test(end) ? Number.parseInt(end, 10) : null;
    if (
      startNumber === null ||
      endNumber === null ||
      startNumber > 4294967295 ||
      endNumber > 4294967295
    ) {
      // Not a range the grammar knows (a `u32` parse failure upstream):
      // keep the target whole (its `:` may still reject it as scheme-shaped
      // later).
      return { path: target, line: null, column: null };
    }
    return startNumber === 0 || endNumber < startNumber
      ? null
      : { path: target.slice(0, colon), line: startNumber, column: null };
  }
  const lastNumber = positiveNumber(last);
  if (/^\d+$/.test(last) && Number.parseInt(last, 10) === 0) {
    return null;
  }
  if (lastNumber === null) {
    return { path: target, line: null, column: null };
  }
  const before = target.slice(0, colon);
  const colon2 = before.lastIndexOf(":");
  if (colon2 >= 0) {
    const beforePiece = before.slice(colon2 + 1);
    const line = positiveNumber(beforePiece);
    if (line !== null) {
      return { path: before.slice(0, colon2), line, column: lastNumber };
    }
    // `a:0:5` rejects like `a:0`; `a:x:5` keeps `a:x` as the path — the `:`
    // it carries rejects it as a scheme-shaped target anyway.
    if (/^\d+$/.test(beforePiece) && Number.parseInt(beforePiece, 10) === 0) {
      return null;
    }
    return { path: before, line: lastNumber, column: null };
  }
  return { path: before, line: lastNumber, column: null };
}

/** `parse_anchor` — `#L12`, `#L12C5`, `#L12-L20`, `#L12-20` or
 * `#L12C1-L20C3`: the first line plus its column when present. */
function parseAnchor(fragment: string): "none" | "invalid" | { line: number; column: number | null } {
  if (!fragment.startsWith("L")) {
    return "none";
  }
  const rest = fragment.slice(1);
  let startPart = rest;
  let endPart: string | null = null;
  const dash = rest.indexOf("-");
  if (dash >= 0) {
    startPart = rest.slice(0, dash);
    const tail = rest.slice(dash + 1);
    endPart = tail.startsWith("L") ? tail.slice(1) : tail;
  }
  const start = parseLineColumn(startPart);
  if (start === null) {
    return "none";
  }
  const [line, column] = start;
  if (line === 0 || column === 0) {
    return "invalid";
  }
  if (endPart !== null) {
    const end = parseLineColumn(endPart);
    if (end === null) {
      return "none";
    }
    const [endLine, endColumn] = end;
    if (endLine < line || endColumn === 0) {
      return "invalid";
    }
  }
  return { line, column };
}

/** `12` or `12C5` — the line number plus an optional column. */
function parseLineColumn(value: string): [number, number | null] | null {
  const cAt = value.indexOf("C");
  const linePart = cAt >= 0 ? value.slice(0, cAt) : value;
  const columnPart = cAt >= 0 ? value.slice(cAt + 1) : null;
  if (!/^\d+$/.test(linePart)) {
    return null;
  }
  const line = Number.parseInt(linePart, 10);
  if (line > 4294967295) {
    return null;
  }
  if (columnPart !== null) {
    if (!/^\d+$/.test(columnPart)) {
      return null;
    }
    const column = Number.parseInt(columnPart, 10);
    if (column > 4294967295) {
      return null;
    }
    return [line, column];
  }
  return [line, null];
}

function positiveNumber(value: string): number | null {
  if (!/^\d+$/.test(value)) {
    return null;
  }
  // `u32::parse` + positive filter (workspace_links.rs `positive_number`):
  // overflow rejects the fragment rather than pinning a line beyond the file.
  const number = Number.parseInt(value, 10);
  return number > 0 && number <= 4294967295 ? number : null;
}

function percentDecodePath(encoded: string): string | null {
  const bytes: number[] = [];
  for (let i = 0; i < encoded.length; ) {
    if (encoded[i] === "%") {
      if (!isHexPair(encoded, i + 1)) {
        return null;
      }
      bytes.push(Number.parseInt(encoded.slice(i + 1, i + 3), 16));
      i += 3;
    } else {
      // Non-ASCII in a file-mention target cannot round-trip the byte-wise
      // re-encode below, so reject instead of lossy-decoding.
      const code = encoded.codePointAt(i)!;
      if (code > 0x7f) {
        return null;
      }
      bytes.push(code);
      i += code >= 0x10000 ? 2 : 1;
    }
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(new Uint8Array(bytes));
  } catch {
    return null;
  }
}

function percentEncodePath(path: string): string {
  let out = "";
  for (const byte of new TextEncoder().encode(path)) {
    if (
      (byte >= 0x30 && byte <= 0x39) ||
      (byte >= 0x41 && byte <= 0x5a) ||
      (byte >= 0x61 && byte <= 0x7a) ||
      byte === 0x2d ||
      byte === 0x2e ||
      byte === 0x5f ||
      byte === 0x7e ||
      byte === 0x2f
    ) {
      out += String.fromCharCode(byte);
    } else {
      out += `%${byte.toString(16).toUpperCase().padStart(2, "0")}`;
    }
  }
  return out;
}
