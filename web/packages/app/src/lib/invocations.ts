import type { HarnessId, Invocation, Skill, SlashCommand } from "@roboco/proto";
import type { RpcErrorKind } from "@roboco/engine-client";
import { filterIndices } from "./picker-search";
import type { SkillCompletionPreferences } from "../state/ui-settings";
import {
  completionMarkdownEnd,
  fileMentionLinks,
  graphemeAwareEnd,
  invocationLink,
  invocationLinks,
  isCharBoundary,
  isWhitespaceCode,
  type CompletionToken,
} from "./mentions";

/**
 * The invocation library — the `$skill` / `/command` completion core, a port
 * of the desktop composer.rs rich-reference machinery (upstream
 * 3c7185b4 → a75cf1a6): `invocation_token` (`:5048`), `completion_trigger`
 * (`:5080`), `skill_display_name` (`:5103`), `invocation_candidates`
 * (`:5296`), `merge_invocation_results` (`:5358`), `invocation_insertion`
 * (`:5213`), `references_require_update` (`:5229`), and the per-harness
 * `SkillCompletionSettings` defaults (`settings.rs:677`). The durable link
 * transport itself lives in mentions.ts beside the file-mention transport
 * it shares.
 */

export type { SkillCompletionPreferences };

/**
 * The per-harness preferences now live in the ui-settings store
 * (`skillCompletionFor` in state/ui-settings.ts, desktop settings.rs:1425);
 * the composer derives them from the current snapshot, so the Shortcuts
 * page's toggles apply without a reload. `completionTrigger` below keeps
 * accepting the resolved pair.
 */

/**
 * `invocation_token` (composer.rs:5048): invocation triggers share file
 * completion's boundary rules (`(`, `[`, `{`, `>`, whitespace — never a
 * path segment, currency amount, or mid-word `$`), the query must be
 * command-name graphemes (a `$` query may not start with a digit), and the
 * token never spans into a following `/` (a typed path). The token range
 * spans the whole word, cursor included or not.
 */
export function invocationToken(
  text: string,
  cursor: number,
  prefix: "/" | "$",
): CompletionToken | null {
  if (cursor > text.length || !isCharBoundary(text, cursor)) {
    return null;
  }
  let start = 0;
  for (let at = cursor - 1; at >= 0; at -= 1) {
    const ch = text[at]!;
    if (
      isWhitespaceCode(text.charCodeAt(at)) ||
      ch === "(" ||
      ch === "[" ||
      ch === "{" ||
      ch === ">"
    ) {
      start = at + 1;
      break;
    }
  }
  if (!text.slice(start, cursor).startsWith(prefix)) {
    return null;
  }
  const query = text.slice(start + 1, cursor);
  const nameGrapheme = (grapheme: string): boolean => {
    const code = grapheme.codePointAt(0)!;
    return /\p{L}|\p{N}/u.test(String.fromCodePoint(code)) || "-_:.".includes(grapheme[0]!);
  };
  const graphemes = splitGraphemes(query);
  if (
    !graphemes.every(nameGrapheme) ||
    (prefix === "$" && query.length > 0 && /^\p{N}/u.test(query))
  ) {
    return null;
  }
  // Scan from the token's start, including any combining marks after a caret
  // positioned between code points of the same grapheme.
  let end = graphemeAwareEnd(text, start + 1, nameGrapheme);
  if (text[end] === "/") {
    return null;
  }
  const markdownEnd = completionMarkdownEnd(text, start, cursor, end);
  return markdownEnd === null ? null : { start, end: markdownEnd, query };
}

/** Grapheme-cluster split (`Intl.Segmenter` with the code-point fallback,
 * links.ts's pattern — the desktop iterates `graphemes(true)`). */
export function splitGraphemes(text: string): string[] {
  const ctor = (
    Intl as { Segmenter?: new (locale?: string, options?: { granularity: string }) => SegmenterLike }
  ).Segmenter;
  if (ctor !== undefined) {
    return [...new ctor(undefined, { granularity: "grapheme" }).segment(text)].map(
      (seg) => seg.segment,
    );
  }
  return [...text];
}

interface SegmenterLike {
  segment(input: string): Iterable<{ index: number; segment: string }>;
}

/**
 * `completion_trigger` (composer.rs:5080): interpret the trigger before
 * discovery so a disabled `$` stays ordinary text. Returns the token plus
 * the mode flags — whether the trigger is a skill token, whether skill
 * rows join the menu, and whether command rows are allowed at all.
 */
export function completionTrigger(
  text: string,
  cursor: number,
  preferences: SkillCompletionPreferences,
): { token: CompletionToken | null; skill: boolean; includeSkills: boolean; commandsAllowed: boolean } {
  const skillToken = preferences.dollar ? invocationToken(text, cursor, "$") : null;
  const skill = skillToken !== null;
  const includeSkills = skill || !preferences.separateFromSlash;
  const token = skillToken ?? invocationToken(text, cursor, "/");
  const commandsAllowed = token !== null && !skill;
  return { token, skill, includeSkills, commandsAllowed };
}

/**
 * `skill_display_name` (composer.rs:5103): human-readable presentation only;
 * invocation names and paths stay canonical. Splits on `:` (plugin
 * namespaces), `-`, `_` and whitespace, then Title-cases each word.
 */
export function skillDisplayName(name: string): string {
  const last = name.split(":").pop() ?? name;
  return last
    .split(/[-_\s]+/)
    .filter((word) => word.length > 0)
    .map((word) => word[0]!.toUpperCase() + word.slice(1).toLowerCase())
    .join(" ");
}

/** `valid_invocation_name` (invocation.rs:25). */
export function validInvocationName(name: string): boolean {
  return name.length > 0 && ![...name].some((ch) => ch.charCodeAt(0) < 32 || /\s/.test(ch));
}

/** `valid_skill_path` (invocation.rs:31): spaces and Unicode are valid. */
export function validSkillPath(path: string): boolean {
  return path.length > 0 && ![...path].some((ch) => ch.charCodeAt(0) < 32);
}

/** `valid_skill_command_name` (invocation.rs:37). */
export function validSkillCommandName(name: string): boolean {
  return name.length > 0 && /^[A-Za-z0-9\-_:.]+$/.test(name);
}

/** `native_skill_identity` (invocation.rs:17): plugin/harness-provided
 * skills have no local skill file. */
export function nativeSkillIdentity(path: string): boolean {
  return path.startsWith("opencode-skill:") || path.startsWith("harness-skill:");
}

/**
 * `invocation_insertion` (composer.rs:5213): a command on a target without
 * composer-references support inserts its plain text (the provider still
 * understands the prefix); everything else inserts the canonical link chip.
 */
export function invocationInsertion(invocation: Invocation, supported: boolean): string {
  return !supported && invocation.kind === "command"
    ? `/${invocation.name}`
    : invocationLink(invocation);
}

/**
 * `references_require_update` (composer.rs:5229): the send gate — a draft
 * holding references the delivery target cannot decode must fail with the
 * draft preserved, never silently degrade.
 */
export function referencesRequireUpdate(text: string, supported: boolean): boolean {
  return !supported && (invocationLinks(text).length > 0 || fileMentionLinks(text).length > 0);
}

// ---------------------------------------------------------------------------
// The candidate catalog (composer.rs:5296-5395)
// ---------------------------------------------------------------------------

/** One completion row: the durable invocation plus its presentation. */
export interface InvocationRow {
  readonly invocation: Invocation;
  readonly name: string;
  readonly description: string;
  readonly inputHint: string | null;
  /** The host rejects this command while the conversation has active work. */
  readonly idleOnly?: boolean;
}

/**
 * `invocation_candidates` (composer.rs:5296): merge commands and skills into
 * rows. A remote engine may use an older catalog decoder, so every visible
 * choice is re-validated against the local canonical-reference decoder;
 * provider commands shadowed by an advertised skill command are dropped
 * (the skill row carries the reference); disabled skills never show.
 */
export function invocationCandidates(
  commands: readonly SlashCommand[],
  skills: readonly Skill[] | null,
): InvocationRow[] {
  const validSkills = (skills ?? []).filter(
    (skill) =>
      validInvocationName(skill.name) &&
      validSkillPath(skill.path) &&
      (skill.command === null || skill.command === undefined || validSkillCommandName(skill.command.name)),
  );
  const skillCommands = new Set(
    validSkills
      .filter((skill) => skill.command !== null && skill.command !== undefined)
      .map((skill) => skill.command!.name),
  );
  const rows: InvocationRow[] = commands
    .filter((command) => validInvocationName(command.name) && !skillCommands.has(command.name))
    .map((command) => ({
      invocation: { kind: "command", name: command.name },
      name: command.name,
      description: command.description,
      inputHint: command.inputHint ?? null,
    }));
  for (const skill of validSkills) {
    if (!skill.enabled) {
      continue;
    }
    rows.push({
      invocation: {
        kind: "skill",
        name: skill.name,
        path: skill.path,
        command: skill.command ?? null,
      },
      name: skill.name,
      description: nativeSkillIdentity(skill.path)
        ? skill.description
        : `${skill.description} — ${skill.path}`,
      inputHint: null,
    });
  }
  return rows;
}

/** Decode a `ListCommands` reply, tolerating a malformed payload (the old
 * `parse_slash_commands`, kept verbatim). */
export function parseSlashCommands(reply: unknown): readonly SlashCommand[] | null {
  if (!Array.isArray(reply)) {
    return null;
  }
  const commands: SlashCommand[] = [];
  for (const entry of reply) {
    if (typeof entry !== "object" || entry === null) {
      return null;
    }
    const candidate = entry as Record<string, unknown>;
    if (typeof candidate.name !== "string" || typeof candidate.description !== "string") {
      return null;
    }
    const inputHint = typeof candidate.inputHint === "string" ? candidate.inputHint : null;
    commands.push({
      name: candidate.name,
      description: candidate.description,
      inputHint: candidate.inputHint === undefined ? null : inputHint,
    });
  }
  return commands;
}

/** Decode a `ListSkills` reply (`Option<Vec<Skill>>`): `null` = the provider
 * does not advertise skills; `undefined` = malformed payload (the reply
 * failed to decode and reports as an error, like serde on the desktop). */
export function parseSkillsReply(
  reply: unknown,
): { ok: true; skills: readonly Skill[] | null } | { ok: false } {
  if (reply === null || reply === undefined) {
    return { ok: true, skills: null };
  }
  if (!Array.isArray(reply)) {
    return { ok: false };
  }
  const skills: Skill[] = [];
  for (const entry of reply) {
    if (typeof entry !== "object" || entry === null) {
      return { ok: false };
    }
    const candidate = entry as Record<string, unknown>;
    if (
      typeof candidate.name !== "string" ||
      typeof candidate.path !== "string" ||
      typeof candidate.description !== "string" ||
      typeof candidate.enabled !== "boolean"
    ) {
      return { ok: false };
    }
    let command: { name: string; harness: HarnessId } | null = null;
    if (candidate.command !== null && candidate.command !== undefined) {
      if (typeof candidate.command !== "object") {
        return { ok: false };
      }
      const raw = candidate.command as Record<string, unknown>;
      if (typeof raw.name !== "string" || typeof raw.harness !== "string") {
        return { ok: false };
      }
      command = { name: raw.name, harness: raw.harness as HarnessId };
    }
    skills.push({
      name: candidate.name,
      path: candidate.path,
      description: candidate.description,
      enabled: candidate.enabled,
      command,
    });
  }
  return { ok: true, skills };
}

/** One catalog arm's outcome: a value or the error kind that replaced it. */
export type CatalogOutcome<T> = { ok: true; value: T } | { ok: false; kind: RpcErrorKind };

/**
 * `merge_invocation_results` (composer.rs:5358): partial success is useful —
 * commands with a failed skills probe (or skills with a failed command
 * probe) still show their rows plus a warning; total failure surfaces the
 * error. `supported` is false only for a `$` token on a provider that does
 * not advertise skills at all.
 */
export function mergeInvocationResults(
  commands: CatalogOutcome<readonly SlashCommand[]>,
  skills: CatalogOutcome<readonly Skill[] | null>,
  skillOnly: boolean,
): { rows: readonly InvocationRow[]; supported: boolean; warning: string | null } | { error: RpcErrorKind } {
  if (commands.ok) {
    if (skills.ok) {
      return {
        rows: invocationCandidates(commands.value, skills.value),
        supported: !skillOnly || skills.value !== null,
        warning: null,
      };
    }
    if (!skillOnly && commands.value.length > 0) {
      return {
        rows: invocationCandidates(commands.value, []),
        supported: true,
        warning: slashErrorMessage(skills.kind, true),
      };
    }
    return { error: skills.kind };
  }
  if (
    skills.ok &&
    skills.value !== null &&
    skills.value.some((skill) => skill.enabled)
  ) {
    return {
      rows: invocationCandidates([], skills.value),
      supported: true,
      warning: slashErrorMessage(commands.kind, false),
    };
  }
  return { error: commands.kind };
}

/** `slash_error_message` (composer.rs:5387): a failed discovery, translated
 * for the popup — version skew, unreachable, or plain failure, each with
 * its skill/command wording. */
export function slashErrorMessage(kind: RpcErrorKind, skill: boolean): string {
  switch (kind) {
    case "unknown-method":
      return skill
        ? "Skills require an updated engine on the selected device. Restart that device’s Roboco after updating."
        : "Commands require an updated engine on the selected device. Restart that device’s Roboco after updating.";
    case "transport":
    case "closed":
    case "timeout":
    case "parked":
      return "The session's device is unreachable";
    default:
      return skill ? "Couldn't load this agent's skills" : "Couldn't load this agent's commands";
  }
}

/**
 * `refilter_slash` (composer.rs:5430-5449): the pure local filter — prefix
 * before substring, ties by input order (`popover::filter_indices`), the
 * cursor re-entering at 0. No RPC, no debounce, no skeleton churn.
 */
export function refilterSlash(
  query: string,
  rows: readonly InvocationRow[],
): { filtered: readonly number[]; active: number | null } {
  const names = rows.map((row) => row.name);
  const filtered = filterIndices(query, names);
  return { filtered, active: filtered.length > 0 ? 0 : null };
}

/**
 * A slash row's description (composer.rs:5580-5588): the row's own
 * description, with the input hint folded in — `"<hint>"` alone when there
 * is no description, else `"{description} · <{hint}>"`.
 */
export function slashDescription(row: InvocationRow): string {
  const hint = row.inputHint;
  if (hint === null || hint.length === 0) {
    return row.description;
  }
  return row.description.length === 0 ? `<${hint}>` : `${row.description} · <${hint}>`;
}
