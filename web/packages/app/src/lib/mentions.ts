import type { HarnessId, Invocation } from "@roboco/proto";
export type { Invocation };
import type { RpcErrorKind } from "@roboco/engine-client";
import { graphemeBoundaries } from "./links";

/**
 * The `@` file-mention library — a line-for-line port of the desktop's
 * composer.rs mention core (constants at `:864-870`, the Markdown
 * transport at `:929-1051`, the tooltip state machine at `:1089-1149`,
 * `TextProjection` at `:1158-1262`, `mention_display_labels` at `:1267`,
 * `sent_mention_display` at `:1316`, `mention_token` at `:3866`, and
 * `mention_error_message` at `:3969`).
 *
 * Offsets are UTF-16 code units (the browser's string indices), the direct
 * equivalent of the desktop's byte indices: every caret value the web feeds
 * these functions comes from `selectionStart`/`selectionEnd`, which are
 * always code-unit indices on char boundaries.
 *
 * The rich-composer wave (upstream 3c7185b4 → a75cf1a6) extends the same
 * machinery to invocation references: `roboco-invoke:` links share the
 * Markdown transport, the chip projection, and the sent-message display,
 * so `$skill` and `/command` chips are file chips with a different prefix.
 */

/** The literal `@` a chip displays before its file name (composer.rs:864). */
export const MENTION_PREFIX = "@";
/** Non-breaking side bearings around the chip label (composer.rs:867). */
export const MENTION_SIDE_PAD = "\u00a0";
/**
 * A private URI scheme keeps file mentions distinguishable from ordinary
 * Markdown links pasted into the composer (composer.rs:870).
 */
export const FILE_MENTION_SCHEME = "roboco-file:";
/**
 * The invocation transport's private scheme (invocation.rs:8): canonical
 * `$skill`/`/command` chips carry their `Invocation` payload as hex JSON.
 */
export const INVOCATION_SCHEME = "roboco-invoke:";
/** The prefix character a reference chip displays (`Invocation::prefix`). */
export type ReferencePrefix = "@" | "$" | "/";
/** Hover dwell before a chip's path tooltip appears (composer.rs:865). */
export const MENTION_TOOLTIP_DELAY_MS = 420;
/** The path tooltip's fixed height (composer.rs:866). */
export const MENTION_TOOLTIP_HEIGHT = 24;
/** The chip wash's corner radius (composer.rs:3500-3506). */
export const MENTION_CHIP_RADIUS = 5;
/** The chip wash's top inset inside its text row (composer.rs:3494-3498). */
export const MENTION_CHIP_TOP_INSET = 2;
/** The chip wash's height reduction inside its text row (composer.rs:3498). */
export const MENTION_CHIP_HEIGHT_CUT = 4;

/** A detected completion token: the range it spans plus the typed query. */
export interface CompletionToken {
  readonly start: number;
  readonly end: number;
  readonly query: string;
}

/** Whether `index` falls between UTF-16 code units (never splits a pair). */
export function isCharBoundary(text: string, index: number): boolean {
  if (index < 0 || index > text.length) {
    return false;
  }
  if (index === text.length) {
    return true;
  }
  const code = text.charCodeAt(index);
  return code < 0xdc00 || code > 0xdfff;
}

export function isWhitespaceCode(code: number): boolean {
  return code === 0x20 || (code >= 0x09 && code <= 0x0d) || code === 0xa0 || code === 0x1680 ||
    (code >= 0x2000 && code <= 0x200a) || code === 0x2028 || code === 0x2029 || code === 0x202f ||
    code === 0x205f || code === 0x3000 || code === 0xfeff;
}

/** The first offset ≥ `from` whose grapheme cluster fails `isNameGrapheme`
 * (or the text length) — the desktop's `grapheme_indices` end scan
 * (composer.rs:5076). Grapheme boundaries come from `Intl.Segmenter`
 * (code points on runtimes without it — links.ts's fallback pattern). */
export function graphemeAwareEnd(
  text: string,
  from: number,
  isNameGrapheme: (grapheme: string) => boolean,
): number {
  const boundaries = graphemeBoundaries(text);
  for (let ix = 0; ix < boundaries.length; ix += 1) {
    const segStart = boundaries[ix]!;
    if (segStart < from) {
      continue;
    }
    const segEnd = boundaries[ix + 1] ?? text.length;
    if (!isNameGrapheme(text.slice(segStart, segEnd))) {
      return segStart;
    }
  }
  return text.length;
}

/** The `@` must begin a token (composer.rs:4999, post-rich-references):
 * excludes `name@example.com` and mid-word `@`, allows `(@src`, `[@src`,
 * `{@src`, `>@src` (quote continuation); a bracketed mention ends at its
 * matching closer, and the token must sit in prose Markdown, never inside
 * a canonical chip, a code span/block, or an unfinished link destination. */
export function mentionToken(text: string, cursor: number): CompletionToken | null {
  if (cursor > text.length || !isCharBoundary(text, cursor)) {
    return null;
  }
  let tokenStart = 0;
  for (let at = cursor - 1; at >= 0; at -= 1) {
    if (isWhitespaceCode(text.charCodeAt(at))) {
      tokenStart = at + 1;
      break;
    }
  }
  const at = text.lastIndexOf("@", cursor - 1);
  if (at < tokenStart) {
    return null;
  }
  const previous = at > 0 ? text[at - 1] : undefined;
  const validBoundary =
    at === 0 ||
    (previous !== undefined &&
      (isWhitespaceCode(previous.charCodeAt(0)) ||
        previous === "(" ||
        previous === "[" ||
        previous === "{" ||
        previous === ">"));
  if (!validBoundary || text.slice(at + 1, cursor).includes("@")) {
    return null;
  }
  const closing =
    previous === "("
      ? ")"
      : previous === "["
        ? "]"
        : previous === "{"
          ? "}"
          : null;
  let end = text.length;
  for (let ix = cursor; ix < text.length; ix += 1) {
    if (isWhitespaceCode(text.charCodeAt(ix)) || (closing !== null && text[ix] === closing)) {
      end = ix;
      break;
    }
  }
  if (closing !== null && text.slice(at + 1, cursor).includes(closing)) {
    return null;
  }
  const markdownEnd = completionMarkdownEnd(text, at, cursor, end);
  return markdownEnd === null ? null : { start: at, end: markdownEnd, query: text.slice(at + 1, cursor) };
}

// ---------------------------------------------------------------------------
// `completion_markdown_end` (composer.rs:4884) — the prose guard both token
// machines share. The desktop parses the whole draft with pulldown-cmark and
// rejects tokens inside links/images, code spans/blocks, reference
// definitions, quote continuations without a boundary, and unfinished link
// destinations. The web port covers the cases a completion can actually
// misfire on — an existing canonical chip, an unfinished `[label](…`
// destination, a backtick code span containing the token, and fenced or
// indented code blocks — without a full Markdown parse; quote/emphasis
// delimiter subtleties stay approximate (they only matter for tokens typed
// inside `**bold**`/`> quote` contexts, which the boundary scan already
// handles for the common forms).
// ---------------------------------------------------------------------------

/** A backtick run in `text` starting at `at` (length ≥ 1). */
function backtickRun(text: string, at: number): number {
  let length = 0;
  while (text[at + length] === "`") {
    length += 1;
  }
  return length;
}

/** Is `offset` inside an inline code span? The CommonMark rule simplified:
 * find the nearest opener run before `offset`, then its exact-length closer
 * — the offset is in code exactly when the closer exists and lies after it. */
function inCodeSpan(text: string, offset: number): boolean {
  const paragraphStart = text.lastIndexOf("\n\n", offset - 1) + 1;
  let opener = -1;
  for (let at = paragraphStart; at < offset; at += 1) {
    if (text[at] === "`") {
      opener = at;
    }
  }
  if (opener < 0) {
    return false;
  }
  const run = backtickRun(text, opener);
  for (let scan = opener + run; scan < text.length; scan += 1) {
    if (text[scan] !== "`") {
      continue;
    }
    if (backtickRun(text, scan) === run) {
      if (scan > offset) {
        return true;
      }
      return false;
    }
  }
  return false;
}

/** Is `offset` inside a fenced (``` / ~~~) or indented (4 spaces / tab)
 * code block? Line-based: an unclosed fence opens a block until the next
 * matching fence; an indented line starts a block after a blank line. */
function inCodeBlock(text: string, offset: number): boolean {
  const lines = text.split("\n");
  let at = 0;
  let fenced: { delimiter: string; length: number } | null = null;
  let blankBefore = true;
  for (const line of lines) {
    const lineStart = at;
    at += line.length + 1;
    const trimmed = line.replace(/^[ \t]{1,3}/, "");
    const fence = /^( {0,3})(`{3,}|~{3,})/.exec(line);
    if (fence !== null) {
      const delimiter = fence[2]![0]!;
      const length = fence[2]!.length;
      if (fenced !== null) {
        if (fenced.delimiter === delimiter && fenced.length === length) {
          fenced = null;
        }
      } else {
        fenced = { delimiter, length };
      }
    }
    if (line.trim().length === 0) {
      blankBefore = true;
      continue;
    }
    const indented = /^[ \t]{4,}|\t/.test(line);
    const inFence = fenced !== null || fence !== null;
    if (offset >= lineStart && offset <= lineStart + line.length) {
      return inFence || (blankBefore && indented);
    }
    blankBefore = false;
  }
  return false;
}

/** An unfinished `[label](destination` whose destination opens at or before
 * `start` and never closes before the cursor (composer.rs:4960-4996):
 * inserting a canonical link would nest inside the one being authored. */
function inUnfinishedLink(text: string, start: number, cursor: number): boolean {
  const before = text.slice(0, cursor);
  let destination = before.lastIndexOf("](");
  while (destination >= 0) {
    const label = before.lastIndexOf("[", destination);
    if (label >= 0) {
      const labelEscaped = countTrailingBackslashes(before, label) % 2 === 1;
      const closingEscaped = countTrailingBackslashes(before, destination) % 2 === 1;
      if (!labelEscaped && !closingEscaped && destination + 2 <= start) {
        let depth = 1;
        let at = destination + 2;
        while (at < before.length) {
          const ch = before[at]!;
          if (ch === "\\") {
            at += 2;
            continue;
          }
          if (ch === "(") {
            depth += 1;
          } else if (ch === ")") {
            depth -= 1;
            if (depth === 0) {
              break;
            }
          }
          at += 1;
        }
        if (depth > 0) {
          return true;
        }
      }
    }
    destination = before.lastIndexOf("](", destination - 1);
  }
  return false;
}

function countTrailingBackslashes(text: string, before: number): number {
  let count = 0;
  let at = before - 1;
  while (at >= 0 && text[at] === "\\") {
    count += 1;
    at -= 1;
  }
  return count;
}

/** Is `at` inside an image's alt text (`![…` up to the label's closing
 * `](`)? Links there are descriptions, never active selections
 * (invocation.rs:112, the desktop's image_depth tracking). */
function insideImageAlt(text: string, at: number): boolean {
  let search = 0;
  for (;;) {
    const image = text.indexOf("![", search);
    if (image < 0 || image + 1 >= at) {
      return false;
    }
    const labelEnd = labelClose(text, image + 2);
    if (labelEnd === null) {
      search = image + 1;
      continue;
    }
    if (at > image && at < labelEnd) {
      return true;
    }
    search = labelEnd;
  }
}

/**
 * `completion_markdown_end` (composer.rs:4884): the end the token may span,
 * or `null` when the token sits somewhere completion must not fire. See the
 * block comment above for the web's coverage notes.
 */
export function completionMarkdownEnd(
  text: string,
  start: number,
  cursor: number,
  end: number,
): number | null {
  for (const link of fileMentionLinks(text)) {
    if (link.start < end && link.end > start) {
      return null;
    }
  }
  for (const link of invocationLinks(text)) {
    if (link.start < end && link.end > start) {
      return null;
    }
  }
  if (inUnfinishedLink(text, start, cursor)) {
    return null;
  }
  if (inCodeSpan(text, start) || inCodeBlock(text, start)) {
    return null;
  }
  return end;
}

// ---------------------------------------------------------------------------
// Strict local Markdown transport (composer.rs:929-1051)
// ---------------------------------------------------------------------------

/** `percent_encode_path`: keeps `[A-Za-z0-9-._~/]`, everything else `%XX`
 * uppercase hex, over the UTF-8 bytes (composer.rs:892). */
export function percentEncodePath(path: string): string {
  let out = "";
  for (const byte of new TextEncoder().encode(path)) {
    const ch = String.fromCharCode(byte);
    if (/[A-Za-z0-9\-._~/]/.test(ch)) {
      out += ch;
    } else {
      out += `%${byte.toString(16).toUpperCase().padStart(2, "0")}`;
    }
  }
  return out;
}

function percentDecodeBytes(encoded: string): number[] {
  const bytes: number[] = [];
  let at = 0;
  while (at < encoded.length) {
    if (encoded[at] === "%") {
      const hex = encoded.slice(at + 1, at + 3);
      const value = Number.parseInt(hex, 16);
      if (hex.length !== 2 || Number.isNaN(value)) {
        throw new Error("bad escape");
      }
      bytes.push(value);
      at += 3;
    } else {
      bytes.push(encoded.charCodeAt(at));
      at += 1;
    }
  }
  return bytes;
}

function percentDecodePath(encoded: string): string | null {
  try {
    return new TextDecoder().decode(new Uint8Array(percentDecodeBytes(encoded)));
  } catch {
    return null;
  }
}

/** `escape_mention_label` (composer.rs:922 / file_mentions.rs:34):
 * `\` → `\\`, `[`/`]` escaped, and `` ` `` escaped so a canonical label
 * can never open a Markdown code span across chips. */
export function escapeMentionLabel(label: string): string {
  return label
    .replaceAll("\\", "\\\\")
    .replaceAll("[", "\\[")
    .replaceAll("]", "\\]")
    .replaceAll("`", "\\`");
}

/** The pre-backtick-escaping label form: older transcripts carry it, and
 * `file_mention_links`/`invocation_links` still recognize it
 * (invocation.rs `legacy_backtick_labels_remain_recognizable`). */
export function legacyBacktickLabel(label: string): string {
  return label.replaceAll("\\`", "`");
}

/** `local_file_link` (composer.rs:929): the strict local Markdown form
 * `[{escaped basename}](roboco-file:{percent-encoded path}{"/" if dir})`. */
export function localFileLink(path: string, isDir: boolean): string {
  const trimmed = path.replace(/\/+$/, "");
  const parts = trimmed.split("/");
  let basename = parts[parts.length - 1] ?? "";
  if (basename === "") {
    basename = trimmed;
  }
  return `[${escapeMentionLabel(basename)}](${FILE_MENTION_SCHEME}${percentEncodePath(
    `${trimmed}${isDir ? "/" : ""}`,
  )})`;
}

/** A strict, workspace-relative, no-traversal path (composer.rs:984). */
export function localPathIsSafe(path: string): boolean {
  return (
    path.length > 0 &&
    !path.startsWith("/") &&
    !path.includes("\\") &&
    ![...path].some((ch) => ch.charCodeAt(0) < 32) &&
    !path.split("/").some((part) => part.length === 0 || part === "." || part === "..")
  );
}

/**
 * `dropped_file_mention` (composer.rs:947): the insertion a workspace-path
 * drop produces at an arbitrary selection — its own leading separator when
 * the drop point abuts text, a trailing space unless a non-newline
 * whitespace already follows. Returns the inserted string plus how far the
 * cursor advances past the insertion point.
 */
export function droppedFileMention(
  content: string,
  range: { start: number; end: number },
  path: string,
  isDir: boolean,
): { inserted: string; cursorAdvance: number } | null {
  if (
    range.start > range.end ||
    !localPathIsSafe(path) ||
    !isCharBoundary(content, range.start) ||
    !isCharBoundary(content, range.end)
  ) {
    return null;
  }
  const suffix = content.slice(range.end);
  const prefix =
    range.start > 0 && !isWhitespaceCode(content.charCodeAt(range.start - 1)) ? " " : "";
  const existingSeparator =
    suffix.length > 0 &&
    isWhitespaceCode(suffix.charCodeAt(0)) &&
    suffix[0] !== "\n" &&
    suffix[0] !== "\r"
      ? suffix[0]
      : null;
  const existing = existingSeparator ?? null;
  const trailing = existing !== null ? "" : " ";
  const inserted = `${prefix}${localFileLink(path, isDir)}${trailing}`;
  const cursorAdvance = inserted.length + (existing !== null ? existing.length : 0);
  return { inserted, cursorAdvance };
}

/** The `](` that closes a mention label (composer.rs:994). */
function labelClose(text: string, start: number): number | null {
  let escaped = false;
  for (let at = start; at < text.length; at += 1) {
    const ch = text[at]!;
    if (escaped) {
      escaped = false;
    } else if (ch === "\\") {
      escaped = true;
    } else if (ch === "]" && text.slice(at + 1).startsWith("(")) {
      return at;
    }
  }
  return null;
}

/** One canonical `[label](roboco-file:target)` link in the raw text. */
export interface FileMentionLink {
  readonly start: number;
  readonly end: number;
  readonly basename: string;
  readonly path: string;
  readonly isDir: boolean;
  /** The chip's trigger character: `@` for files, `/` or `$` for
   * invocations (the desktop's `FileMentionLink.prefix`). */
  readonly prefix: ReferencePrefix;
  /** For invocation links: the decoded reference; `null` for files. */
  readonly invocation: Invocation | null;
}

/** Scan the raw text for strict, canonical links (composer.rs:1008): the
 * target must decode, round-trip, be a safe local path, and its basename
 * must equal the (escaped) label — with the pre-backtick-escaping form of
 * an older transcript still recognizable. Links inside code, an escaped
 * bracket, or image alt text are skipped, like the desktop's Markdown
 * parse. */
export function fileMentionLinks(text: string): FileMentionLink[] {
  if (!text.includes(FILE_MENTION_SCHEME)) {
    return [];
  }
  const links: FileMentionLink[] = [];
  let search = 0;
  for (;;) {
    const start = text.indexOf("[", search);
    if (start < 0) {
      return links;
    }
    if (
      (start > 0 && (text[start - 1] === "!" || text[start - 1] === "\\")) ||
      insideImageAlt(text, start) ||
      (countTrailingBackslashes(text, start) % 2 === 1) ||
      inCodeSpan(text, start) ||
      inCodeBlock(text, start)
    ) {
      search = start + 1;
      continue;
    }
    const labelEnd = labelClose(text, start + 1);
    if (labelEnd === null) {
      search = start + 1;
      continue;
    }
    const targetStart = labelEnd + 2;
    const close = text.indexOf(")", targetStart);
    if (close < 0) {
      search = start + 1;
      continue;
    }
    const end = close + 1;
    const label = text.slice(start + 1, labelEnd);
    const encoded = text.slice(targetStart, end - 1);
    if (!encoded.startsWith(FILE_MENTION_SCHEME)) {
      search = end;
      continue;
    }
    const payload = encoded.slice(FILE_MENTION_SCHEME.length);
    const decoded = percentDecodePath(payload);
    let parsed: { path: string; isDir: boolean } | null = null;
    if (decoded !== null) {
      const isDir = decoded.endsWith("/");
      const path = isDir ? decoded.slice(0, -1) : decoded;
      const basename = path.split("/").pop() ?? "";
      const canonicalLabel = escapeMentionLabel(basename);
      if (
        localPathIsSafe(path) &&
        percentEncodePath(decoded) === payload &&
        (canonicalLabel === label || legacyBacktickLabel(canonicalLabel) === label)
      ) {
        parsed = { path, isDir };
      }
    }
    if (parsed !== null) {
      links.push({
        start,
        end,
        basename: parsed.path.split("/").pop() ?? "",
        path: parsed.path,
        isDir: parsed.isDir,
        prefix: "@",
        invocation: null,
      });
    }
    search = end;
  }
}

// ---------------------------------------------------------------------------
// The invocation transport (invocation.rs:30-160)
// ---------------------------------------------------------------------------

/** `escape_label` (invocation.rs:12): the same escapes as file labels. */
const escapeInvocationLabel = escapeMentionLabel;

/** `Invocation::link` (invocation.rs:77): `[{prefix}{escaped name}](roboco-invoke:{hex})`
 * where the payload is the invocation's JSON, hex-encoded over its UTF-8
 * bytes. The JSON field order matches serde's internally-tagged output so
 * the canonical round-trip check is byte-identical to the desktop's. */
export function invocationLink(invocation: Invocation): string {
  const label = escapeInvocationLabel(invocation.name);
  const prefix = invocationPrefix(invocation);
  return `[${prefix}${label}](${INVOCATION_SCHEME}${hexEncode(invocationJson(invocation))})`;
}

function invocationJson(invocation: Invocation): string {
  if (invocation.kind === "command") {
    return JSON.stringify({ kind: "command", name: invocation.name });
  }
  const command = invocation.command ?? null;
  const entry: Record<string, unknown> = {
    kind: "skill",
    name: invocation.name,
    path: invocation.path,
  };
  if (command !== null) {
    entry.command = { name: command.name, harness: command.harness };
  }
  return JSON.stringify(entry);
}

function hexEncode(json: string): string {
  let out = "";
  for (const byte of new TextEncoder().encode(json)) {
    out += byte.toString(16).padStart(2, "0");
  }
  return out;
}

/** `Invocation::prefix` (invocation.rs:69): `/` for commands, `$` for skills. */
export function invocationPrefix(invocation: Invocation): ReferencePrefix {
  return invocation.kind === "command" ? "/" : "$";
}

/** `Invocation::detail` (invocation.rs:73): the row's secondary line —
 * `/{name}` for commands, the skill path for skills. */
export function invocationDetail(invocation: Invocation): string {
  return invocation.kind === "command" ? `/${invocation.name}` : invocation.path;
}

/** Decode a hex payload as JSON bytes, tolerating odd or non-ASCII hex. */
function decodeInvocation(hex: string): Invocation | null {
  if (hex.length % 2 !== 0 || !/^[0-9a-fA-F]*$/.test(hex)) {
    return null;
  }
  const bytes = new Uint8Array(hex.length / 2);
  for (let at = 0; at < bytes.length; at += 1) {
    bytes[at] = Number.parseInt(hex.slice(at * 2, at * 2 + 2), 16);
  }
  let decoded: unknown;
  try {
    decoded = JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return null;
  }
  if (typeof decoded !== "object" || decoded === null) {
    return null;
  }
  const entry = decoded as Record<string, unknown>;
  if (entry.kind === "command") {
    if (typeof entry.name !== "string") {
      return null;
    }
    return { kind: "command", name: entry.name };
  }
  if (entry.kind === "skill") {
    if (typeof entry.name !== "string" || typeof entry.path !== "string") {
      return null;
    }
    let command: { name: string; harness: HarnessId } | null = null;
    if (entry.command !== undefined && entry.command !== null) {
      if (
        typeof entry.command !== "object" ||
        typeof (entry.command as Record<string, unknown>).name !== "string" ||
        typeof (entry.command as Record<string, unknown>).harness !== "string"
      ) {
        return null;
      }
      const raw = entry.command as { name: string; harness: string };
      command = { name: raw.name, harness: raw.harness as HarnessId };
    }
    return { kind: "skill", name: entry.name, path: entry.path, command };
  }
  return null;
}

/** `invocation_links` (invocation.rs:100): only canonical links created by
 * completion are decoded — strict hex payload, name/path validation, and a
 * byte-identical canonical round-trip (the pre-backtick-escaping label form
 * of an older transcript remains recognizable). Ordinary `$`/`/` text,
 * escaped brackets, and links inside code spans, code blocks, or image alt
 * text never activate. */
export function invocationLinks(text: string): FileMentionLink[] {
  if (!text.includes(INVOCATION_SCHEME)) {
    return [];
  }
  const links: FileMentionLink[] = [];
  let search = 0;
  for (;;) {
    const start = text.indexOf("[", search);
    if (start < 0) {
      return links;
    }
    // Links inside image syntax `![alt](…)` are descriptions, never
    // active selections (invocation.rs:112), and an escaped `\[` is
    // literal text, never a link.
    if (
      (start > 0 && text[start - 1] === "!") ||
      insideImageAlt(text, start) ||
      countTrailingBackslashes(text, start) % 2 === 1 ||
      inCodeSpan(text, start) ||
      inCodeBlock(text, start)
    ) {
      search = start + 1;
      continue;
    }
    const labelEnd = labelClose(text, start + 1);
    if (labelEnd === null) {
      search = start + 1;
      continue;
    }
    const targetStart = labelEnd + 2;
    const close = text.indexOf(")", targetStart);
    if (close < 0) {
      search = start + 1;
      continue;
    }
    const end = close + 1;
    const label = text.slice(start + 1, labelEnd);
    const encoded = text.slice(targetStart, end - 1);
    if (!encoded.startsWith(INVOCATION_SCHEME)) {
      search = end;
      continue;
    }
    const source = text.slice(start, end);
    const invocation = decodeInvocation(encoded.slice(INVOCATION_SCHEME.length));
    const canonical = invocation === null ? null : invocationLink(invocation);
    if (
      invocation !== null &&
      canonical !== null &&
      (canonical === source || legacyBacktickLabel(canonical) === source)
    ) {
      links.push({
        start,
        end,
        basename: invocation.name,
        path: invocationDetail(invocation),
        isDir: false,
        prefix: invocationPrefix(invocation),
        invocation,
      });
    }
    search = end;
  }
}

// ---------------------------------------------------------------------------
// Display labels + the chip projection (composer.rs:1267, 1158-1262)
// ---------------------------------------------------------------------------

/**
 * `mention_display_labels` (composer.rs:1402): the basename when unique
 * within its prefix; otherwise the shortest unique path-component suffix
 * (per-component comparison for `@`, suffix comparison for `$`/`/` —
 * duplicates there show `"name · suffix"`); the full path as the last
 * resort. References with the same visible name share a result instead of
 * rescanning the draft.
 */
export function mentionDisplayLabels(links: readonly FileMentionLink[]): string[] {
  const groups = new Map<string, number>();
  for (const link of links) {
    const key = `${link.prefix}\u0000${link.basename}`;
    groups.set(key, (groups.get(key) ?? 0) + 1);
  }
  const cache = new Map<string, string>();
  return links.map((link) => {
    const cacheKey = `${link.prefix}\u0000${link.basename}\u0000${link.path}`;
    const cached = cache.get(cacheKey);
    if (cached !== undefined) {
      return cached;
    }
    const label = displayLabelFor(link, links);
    cache.set(cacheKey, label);
    return label;
  });
}

function displayLabelFor(link: FileMentionLink, links: readonly FileMentionLink[]): string {
  const duplicates = links.filter(
    (other) => other.prefix === link.prefix && other.basename === link.basename && other.path !== link.path,
  );
  if (duplicates.length === 0) {
    return link.basename;
  }
  const parts = link.path.split("/");
  const suffix = (() => {
    for (let count = 1; count <= parts.length; count += 1) {
      const candidate = parts.slice(parts.length - count).join("/");
      if (link.prefix !== "@") {
        if (duplicates.every((other) => !other.path.endsWith(candidate))) {
          return candidate;
        }
      } else {
        const suffixParts = candidate.split("/");
        if (
          duplicates.every(
            (other) => other.path.split("/").slice(-suffixParts.length).join("/") !== candidate,
          )
        ) {
          return candidate;
        }
      }
    }
    return link.path;
  })();
  return link.prefix === "@" ? suffix : `${link.basename} · ${suffix}`;
}

/** One chip in the projected display string (a link plus its display range). */
export interface ProjectedMention {
  readonly link: FileMentionLink;
  /** The chip's range over the display string: `NBSP @label NBSP`. */
  readonly start: number;
  readonly end: number;
}

/**
 * `TextProjection` (composer.rs:1153-1262, post-rich-references): file and
 * invocation links merge into one chip list sorted by range, each collapsing
 * to `NBSP {prefix}label NBSP`, plus the mapping functions that make the
 * chip atomic — a collapsed caret inside a link snaps to the nearer end, a
 * selection overlapping a link swallows it whole, and the boundary helpers
 * let Left/Right/Backspace step over a chip in one press.
 */
export class TextProjection {
  readonly display: string;
  readonly mentions: readonly ProjectedMention[];

  constructor(raw: string) {
    const links = [...fileMentionLinks(raw), ...invocationLinks(raw)].sort(
      (a, b) => a.start - b.start,
    );
    const labels = mentionDisplayLabels(links);
    let display = "";
    const mentions: ProjectedMention[] = [];
    let rawAt = 0;
    links.forEach((link, ix) => {
      if (link.start < rawAt) {
        return;
      }
      display += raw.slice(rawAt, link.start);
      const start = display.length;
      display += MENTION_SIDE_PAD;
      display += link.prefix;
      display += labels[ix]!.replaceAll(" ", MENTION_SIDE_PAD);
      display += MENTION_SIDE_PAD;
      mentions.push({ link, start, end: display.length });
      rawAt = link.end;
    });
    display += raw.slice(rawAt);
    this.display = display;
    this.mentions = mentions;
  }

  /** `raw_to_display` (composer.rs:1190): a raw offset into the display
   * string; inside a link maps to the chip's display start. */
  rawToDisplay(raw: number): number {
    let rawAt = 0;
    let displayAt = 0;
    for (const { link, start, end } of this.mentions) {
      if (raw <= link.start) {
        return displayAt + Math.max(0, raw - rawAt);
      }
      if (raw < link.end) {
        return start;
      }
      rawAt = link.end;
      displayAt = end;
    }
    return displayAt + Math.max(0, raw - rawAt);
  }

  /** `display_to_raw` (composer.rs:1206): a display offset back to raw;
   * inside a chip snaps to the link start (first half) or end. */
  displayToRaw(displayOffset: number): number {
    let rawAt = 0;
    let displayAt = 0;
    for (const { link, start, end } of this.mentions) {
      if (displayOffset <= start) {
        return rawAt + Math.max(0, displayOffset - displayAt);
      }
      if (displayOffset < end) {
        return displayOffset - start < (end - start) / 2 ? link.start : link.end;
      }
      rawAt = link.end;
      displayAt = end;
    }
    return rawAt + Math.max(0, displayOffset - displayAt);
  }

  /** `normalize_range` (composer.rs:1226): collapsed carets inside a link
   * snap to the nearer end; selections expand to swallow every overlapping
   * link whole. */
  normalizeRange(start: number, end: number): { start: number; end: number } {
    if (start === end) {
      for (const { link } of this.mentions) {
        if (link.start < start && start < link.end) {
          const midpoint = link.start + (link.end - link.start) / 2;
          const at = start < midpoint ? link.start : link.end;
          return { start: at, end: at };
        }
      }
      return { start, end };
    }
    let normalizedStart = start;
    let normalizedEnd = end;
    for (const { link } of this.mentions) {
      if (normalizedStart < link.end && normalizedEnd > link.start) {
        normalizedStart = Math.min(normalizedStart, link.start);
        normalizedEnd = Math.max(normalizedEnd, link.end);
      }
    }
    return { start: normalizedStart, end: normalizedEnd };
  }

  /** `previous_boundary` (composer.rs:1251): the link start when the caret
   * sits at a link's end, else null. */
  previousBoundary(raw: number): number | null {
    for (const { link } of this.mentions) {
      if (raw === link.end) {
        return link.start;
      }
    }
    return null;
  }

  /** `next_boundary` (composer.rs:1257): the link end when the caret sits
   * at a link's start, else null. */
  nextBoundary(raw: number): number | null {
    for (const { link } of this.mentions) {
      if (raw === link.start) {
        return link.end;
      }
    }
    return null;
  }
}

/**
 * `display_row_segments` (composer.rs:1131): split a display range at every
 * soft-wrap boundary — a range crossing a wrap gets a fresh segment starting
 * at x = 0 on the new row (the wash never bleeds across rows). Only the
 * tooltip anchor and the unit test need this on the web; CSS wrapping
 * handles the visual split.
 */
export function displayRowSegments(
  range: { start: number; end: number },
  rowEnds: readonly number[],
): Array<{ row: number; rowStart: number; start: number; end: number }> {
  const segments: Array<{ row: number; rowStart: number; start: number; end: number }> = [];
  let rowStart = 0;
  for (let rowIx = 0; rowIx < rowEnds.length; rowIx += 1) {
    const rowEnd = rowEnds[rowIx]!;
    const start = Math.max(range.start, rowStart);
    const end = Math.min(range.end, rowEnd);
    if (start < end) {
      segments.push({ row: rowIx, rowStart, start, end });
    }
    rowStart = rowEnd;
    if (rowStart >= range.end) {
      break;
    }
  }
  return segments;
}

// ---------------------------------------------------------------------------
// Sent-message projection (composer.rs:1303-1338)
// ---------------------------------------------------------------------------

/** One chip in a *sent* message: its display range, plus the full path. */
export interface SentMentionSpan {
  readonly start: number;
  readonly end: number;
  /** Full workspace-relative path (labels can be shortened to suffixes). */
  readonly path: string;
  readonly isDir: boolean;
}

/**
 * `sent_mention_display` (composer.rs:1472): project a sent prompt's raw
 * Markdown — file AND invocation links collapse to the same chip labels
 * the composer shows (`TextProjection::new`, the read-only projection).
 * `null` on the zero-allocation fast path: no scheme substring or no valid
 * mention parses.
 */
export function sentMentionDisplay(
  raw: string,
): { display: string; mentions: readonly SentMentionSpan[] } | null {
  if (!raw.includes(FILE_MENTION_SCHEME) && !raw.includes(INVOCATION_SCHEME)) {
    return null;
  }
  const projection = new TextProjection(raw);
  if (projection.mentions.length === 0) {
    return null;
  }
  const mentions = projection.mentions.map((chip) => ({
    start: chip.start,
    end: chip.end,
    path: chip.link.isDir ? `${chip.link.path}/` : chip.link.path,
    isDir: chip.link.isDir,
  }));
  return { display: projection.display, mentions };
}

// ---------------------------------------------------------------------------
// The path-tooltip state machine (composer.rs:1061-1129)
// ---------------------------------------------------------------------------

/** A path alone is not enough: the raw range is part of the hover identity. */
export interface MentionTooltipTarget {
  readonly start: number;
  readonly end: number;
  readonly path: string;
}

/** `MentionTooltipPhase` (composer.rs:1067-1078). */
export type MentionTooltipPhase =
  | { readonly kind: "hidden" }
  | { readonly kind: "waiting"; readonly target: MentionTooltipTarget; readonly generation: number }
  | { readonly kind: "visible"; readonly target: MentionTooltipTarget; readonly generation: number };

function phaseTarget(phase: MentionTooltipPhase): MentionTooltipTarget | null {
  return phase.kind === "hidden" ? null : phase.target;
}

function sameTarget(a: MentionTooltipTarget | null, b: MentionTooltipTarget | null): boolean {
  return a !== null && b !== null && a.start === b.start && a.end === b.end && a.path === b.path;
}

/**
 * `mention_tooltip_reduce` (composer.rs:1092): motion within the same chip
 * preserves both phases (jitter cannot starve the delay or flicker a
 * visible tooltip); a different chip restarts the wait; the pointer inside
 * the tooltip while visible keeps it; anything else hides it.
 */
export function mentionTooltipReduce(
  phase: MentionTooltipPhase,
  pointerTarget: MentionTooltipTarget | null,
  pointerInPopup: boolean,
  generation: number,
): MentionTooltipPhase {
  if (pointerTarget !== null) {
    if (sameTarget(phaseTarget(phase), pointerTarget)) {
      return phase;
    }
    return { kind: "waiting", target: pointerTarget, generation };
  }
  if (pointerInPopup && phase.kind === "visible") {
    return phase;
  }
  return { kind: "hidden" };
}

/**
 * `mention_tooltip_promote` (composer.rs:1106): a wait whose generation
 * matches the fired timer becomes visible when the chip still exists;
 * a matching generation with a dead target hides; a stale timer changes
 * nothing.
 */
export function mentionTooltipPromote(
  phase: MentionTooltipPhase,
  generation: number,
  targetIsLive: boolean,
): MentionTooltipPhase {
  if (phase.kind !== "waiting") {
    return phase;
  }
  if (phase.generation !== generation) {
    return phase;
  }
  return targetIsLive
    ? { kind: "visible", target: phase.target, generation }
    : { kind: "hidden" };
}

/** `mention_tooltip_contains` (composer.rs:1127): the tooltip stays up only
 * over its chip or its own popup. */
export function mentionTooltipContains(inChip: boolean, inPopup: boolean): boolean {
  return inChip || inPopup;
}

// ---------------------------------------------------------------------------
// Response currency + error copy (composer.rs:3961, 3969)
// ---------------------------------------------------------------------------

/**
 * `mention_response_is_current` (composer.rs:3961): a reply only lands when
 * its request generation matches AND a token is still open.
 */
export function mentionResponseIsCurrent(
  state: { request: number; token: unknown },
  request: number,
): boolean {
  return state.request === request && state.token !== null && state.token !== undefined;
}

/**
 * `mention_error_message` (composer.rs:3969): a failure must never render as
 * "No matching files" — cross-device searches fail for reasons the user can
 * act on. `timeout`/`parked` (web-client-only kinds) read as unreachable:
 * both mean the reply never came back over the transport.
 */
export function mentionErrorMessage(kind: RpcErrorKind): string {
  switch (kind) {
    case "unknown-method":
      return "The session's device runs an older roboco — update it to search its files";
    case "transport":
    case "closed":
    case "timeout":
    case "parked":
      return "The session's device is unreachable";
    default:
      return "File search failed";
  }
}
