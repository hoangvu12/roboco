import { describe, expect, it } from "vitest";
import type { Invocation, Skill, SlashCommand } from "@roboco/proto";
import { invocationLink, localFileLink } from "../src/lib/mentions";
import {
  completionTrigger,
  invocationCandidates,
  withWorkspaceCommands,
  workspaceCommandForText,
  invocationInsertion,
  invocationToken,
  mergeInvocationResults,
  parseSkillsReply,
  parseSlashCommands,
  refilterSlash,
  referencesRequireUpdate,
  skillDisplayName,
  slashDescription,
  slashErrorMessage,
} from "../src/lib/invocations";
import { defaultSkillCompletion, defaultUiSettings, skillCompletionFor } from "../src/state/ui-settings";

/**
 * The invocation unit tests — mirrors of the desktop's composer.rs tests
 * (`slash_token_opens_at_prose_boundaries` :12092,
 * `invocation_completion_rejects_code_paths_currency_and_escapes` :10932,
 * `invocation_tokens_preserve_unicode_graphemes` :11728, and
 * `legacy_host_commands_remain_literal_and_saved_references_need_an_update`
 * :11792), plus the candidate/merge contracts (`invocation_candidates`
 * :5296, `merge_invocation_results` :5358) and the old slash-library
 * coverage (filter, descriptions, error copy, wire decode).
 */

const skillCommand = (name: string, harness: SlashCommand["name"] extends never ? never : string): { name: string; harness: "claude-code" } => ({
  name,
  harness: "claude-code",
});

describe("slash_token_opens_at_prose_boundaries", () => {
  it("opens on a whole-prompt prefix", () => {
    expect(invocationToken("/comp", 5, "/")).toEqual({ start: 0, end: 5, query: "comp" });
  });

  it("the token range spans the whole command word even mid-cursor", () => {
    expect(invocationToken("/compact now", 3, "/")).toEqual({ start: 0, end: 8, query: "co" });
  });

  it("mid-prose `/` opens too — prose, not just the prompt prefix", () => {
    expect(invocationToken("run /compact", 12, "/")).toEqual({ start: 4, end: 12, query: "compact" });
  });

  it("cursor past the command word (typing the argument) is closed", () => {
    expect(invocationToken("/goal ship it", 10, "/")).toBeNull();
  });

  it("a typed absolute path is not a command", () => {
    expect(invocationToken("/usr/bin", 8, "/")).toBeNull();
  });

  it("bare '/' at cursor 0 stays closed; after it opens all", () => {
    expect(invocationToken("/", 0, "/")).toBeNull();
    expect(invocationToken("/", 1, "/")?.query).toBe("");
  });
});

describe("invocation_completion_rejects_code_paths_currency_and_escapes", () => {
  it("never interprets amounts, paths, code, or escapes as skills", () => {
    for (const text of ["cost $100", "word$review", "\\$review", "`$review", "```\n$review", "path/$review"]) {
      expect(invocationToken(text, text.length, "$")).toBeNull();
    }
    for (const text of ["use $review", "- $review", "($review", "first\n$review", "hello\u00a0$review"]) {
      expect(invocationToken(text, text.length, "$")?.query).toBe("review");
    }
    expect(invocationToken("try /usr/bin", 12, "/")).toBeNull();
  });
});

describe("invocation_tokens_preserve_unicode_graphemes", () => {
  it("names with accents, combining marks, and CJK survive whole", () => {
    for (const name of ["réview", "re\u0301view", "確認", "レビュー"]) {
      for (const prefix of ["$", "/"] as const) {
        const text = `please ${prefix}${name}`;
        const token = invocationToken(text, text.length, prefix);
        expect(token?.query).toBe(name);
        expect(text.slice(token?.start ?? 0, token?.end ?? 0)).toBe(`${prefix}${name}`);
      }
    }
  });

  it("a caret between a base and its combining mark completes the grapheme", () => {
    const text = "$re\u0301view";
    const token = invocationToken(text, 3, "$");
    expect(token?.query).toBe("re");
    expect(text.slice(token?.start ?? 0, token?.end ?? 0)).toBe(text);
  });

  it("digits, emoji, paths, and lone combining marks never open `$`", () => {
    for (const text of ["$🧑", "$٣", "$12", "$re/view", "$\u0301"]) {
      expect(invocationToken(text, text.length, "$")).toBeNull();
    }
    // The desktop's `$é`-at-2 case is its byte-boundary guard (cursor
    // inside the two-byte é); the web's code-unit guard covers the
    // equivalent — a caret inside a surrogate pair.
    expect(invocationToken("$\u{1F9D1}".codePointAt(0) === 0x1F9D1 ? "$\uD83E\uDDD1x" : "$x", 3, "$")).toBeNull();
  });
});

describe("completion_trigger_interprets_the_trigger_before_discovery", () => {
  const native = defaultSkillCompletion("codex");
  const ordinary = defaultSkillCompletion("claude-code");

  it("defaults: only Codex speaks `$` natively and separates its menus", () => {
    expect(native).toEqual({ dollar: true, separateFromSlash: true });
    expect(ordinary).toEqual({ dollar: false, separateFromSlash: false });
  });

  it("a disabled `$` stays ordinary text; slash menus include skills", () => {
    const on = completionTrigger("use $review", 11, native);
    expect(on).toEqual({ token: { start: 4, end: 11, query: "review" }, skill: true, includeSkills: true, commandsAllowed: false });
    const off = completionTrigger("use $review", 11, ordinary);
    expect(off.skill).toBe(false);
    expect(off.token).toBeNull();
    const slash = completionTrigger("/rev", 4, ordinary);
    expect(slash).toEqual({ token: { start: 0, end: 4, query: "rev" }, skill: false, includeSkills: true, commandsAllowed: true });
    const slashCodex = completionTrigger("/rev", 4, native);
    expect(slashCodex.includeSkills).toBe(false);
  });

  it("preferences resolve from the ui-settings store the Shortcuts page writes", () => {
    // The composer resolves per-harness preferences from the settings
    // snapshot (settings.rs:1425); an override turns `$` on for a harness
    // whose defaults keep it ordinary text.
    const overridden = {
      ...defaultUiSettings(),
      skillCompletionByHarness: { "claude-code": { dollar: true, separateFromSlash: false } },
    };
    const preferences = skillCompletionFor(overridden, "claude-code");
    expect(preferences).toEqual({ dollar: true, separateFromSlash: false });
    const enabled = completionTrigger("use $review", 11, preferences);
    expect(enabled.skill).toBe(true);
    expect(skillCompletionFor(defaultUiSettings(), "claude-code")).toEqual({ dollar: false, separateFromSlash: false });
  });
});

describe("legacy_host_commands_remain_literal_and_saved_references_need_an_update", () => {
  const command: Invocation = { kind: "command", name: "compact" };
  const skill: Invocation = { kind: "skill", name: "review", path: "/repo/SKILL.md" };

  it("a command on an unsupported target inserts plain text, everything else the chip", () => {
    expect(invocationInsertion(command, false)).toBe("/compact");
    expect(invocationInsertion(command, true)).toBe(invocationLink(command));
    expect(invocationInsertion(skill, false)).toBe(invocationLink(skill));
  });

  it("a draft holding references needs an update; literals never do", () => {
    for (const reference of [invocationLink(command), invocationLink(skill), localFileLink("src/lib.rs", false)]) {
      expect(referencesRequireUpdate(reference, false)).toBe(true);
      expect(referencesRequireUpdate(reference, true)).toBe(false);
      for (const literal of [`\`${reference}\``, `    ${reference}`, `\\${reference}`]) {
        expect(referencesRequireUpdate(literal, false)).toBe(false);
      }
    }
    expect(referencesRequireUpdate("/compact", false)).toBe(false);
  });
});

describe("invocation_candidates", () => {
  const commands: SlashCommand[] = [
    { name: "compact", description: "Summarize", inputHint: null },
    { name: "review", description: "Provider alias", inputHint: "scope..." },
    { name: "bad name", description: "Invalid", inputHint: null },
  ];
  const skills: Skill[] = [
    { name: "review", path: "/repo/SKILL.md", description: "Review the diff", enabled: true, command: { name: "review", harness: "claude-code" } },
    { name: "draft", path: "/repo/draft/SKILL.md", description: "Draft docs", enabled: true, command: null },
    { name: "disabled", path: "/repo/off/SKILL.md", description: "Off", enabled: false, command: null },
    { name: "native", path: "opencode-skill:plugin:audit", description: "Audit", enabled: true, command: null },
  ];

  it("drops skill-aliased provider commands and disabled skills; native identities keep their plain description", () => {
    const rows = invocationCandidates(commands, skills);
    expect(rows.map((row) => row.name)).toEqual(["compact", "review", "draft", "native"]);
    const review = rows.find((row) => row.name === "review")!;
    expect(review.invocation.kind).toBe("skill");
    expect(review.description).toBe("Review the diff — /repo/SKILL.md");
    expect(rows.find((row) => row.name === "native")?.description).toBe("Audit");
    expect(rows.find((row) => row.name === "compact")?.inputHint).toBeNull();
  });

  it("a null skills catalog yields command rows only", () => {
    expect(invocationCandidates(commands, null).map((row) => row.name)).toEqual(["compact", "review"]);
  });
});

describe("merge_invocation_results", () => {
  const commands: SlashCommand[] = [
    { name: "compact", description: "Summarize", inputHint: null },
  ];
  const skills: Skill[] = [
    { name: "review", path: "/repo/SKILL.md", description: "Review", enabled: true, command: null },
  ];

  it("both catalogs land merged with no warning", () => {
    const merged = mergeInvocationResults({ ok: true, value: commands }, { ok: true, value: skills }, false);
    expect(merged).toEqual({
      rows: [
        { invocation: { kind: "command", name: "compact" }, name: "compact", description: "Summarize", inputHint: null, workspaceCommand: null },
        { invocation: { kind: "skill", name: "review", path: "/repo/SKILL.md", command: null }, name: "review", description: "Review — /repo/SKILL.md", inputHint: null, workspaceCommand: null },
      ],
      supported: true,
      warning: null,
    });
  });

  it("commands survive a failed skills probe (plus a warning); the reverse holds with enabled skills", () => {
    const partial = mergeInvocationResults({ ok: true, value: commands }, { ok: false, kind: "transport" }, false);
    expect("rows" in partial && partial.rows).toHaveLength(1);
    expect("warning" in partial && partial.warning).toBe("The session's device is unreachable");
    const flipped = mergeInvocationResults({ ok: false, kind: "unknown-method" }, { ok: true, value: skills }, false);
    expect("rows" in flipped && flipped.rows.map((row) => row.name)).toEqual(["review"]);
    expect("warning" in flipped && flipped.warning).toContain("Commands require an updated engine");
  });

  it("a `$` token on a provider without skills is unsupported, not an error", () => {
    const merged = mergeInvocationResults({ ok: true, value: commands }, { ok: true, value: null }, true);
    expect("supported" in merged && merged.supported).toBe(false);
  });

  it("total failure surfaces the error kind", () => {
    expect(mergeInvocationResults({ ok: false, kind: "failed" }, { ok: true, value: null }, false)).toEqual({ error: "failed" });
    expect(mergeInvocationResults({ ok: true, value: [] }, { ok: false, kind: "closed" }, false)).toEqual({ error: "closed" });
  });
});

describe("skill_display_name", () => {
  it("splits namespaces and separators, then Title-cases", () => {
    expect(skillDisplayName("plugin:review")).toBe("Review");
    expect(skillDisplayName("code-review")).toBe("Code Review");
    expect(skillDisplayName("draft_docs ui")).toBe("Draft Docs Ui");
  });
});

describe("refilter_slash_over_invocation_rows", () => {
  const rows = invocationCandidates(
    [
      { name: "compact", description: "Summarize the conversation", inputHint: null },
      { name: "pr-comments", description: "Review pending comments", inputHint: null },
    ],
    [{ name: "review", path: "/repo/SKILL.md", description: "", enabled: true, command: null }],
  );

  it("prefix matches rank before substring matches, ties by input order", () => {
    const { filtered, active } = refilterSlash("c", rows);
    expect(filtered).toEqual([0, 1]);
    expect(active).toBe(0);
  });

  it("no match leaves the cursor null", () => {
    expect(refilterSlash("zzz", rows)).toEqual({ filtered: [], active: null });
  });

  it("a slash row folds its input hint into the description", () => {
    expect(
      slashDescription({
        invocation: { kind: "command", name: "goal" },
        name: "goal",
        description: "",
        inputHint: "text...",
        workspaceCommand: null,
      }),
    ).toBe("<text...>");
    expect(
      slashDescription({
        invocation: { kind: "command", name: "compact" },
        name: "compact",
        description: "Summarize",
        inputHint: null,
        workspaceCommand: null,
      }),
    ).toBe("Summarize");
  });
});

describe("slash_error_message", () => {
  it("each failure kind has its own verbatim skill/command wording", () => {
    expect(slashErrorMessage("unknown-method", true)).toBe(
      "Skills require an updated engine on the selected device. Restart that device’s Roboco after updating.",
    );
    expect(slashErrorMessage("unknown-method", false)).toBe(
      "Commands require an updated engine on the selected device. Restart that device’s Roboco after updating.",
    );
    expect(slashErrorMessage("transport", false)).toBe("The session's device is unreachable");
    expect(slashErrorMessage("failed", true)).toBe("Couldn't load this agent's skills");
    expect(slashErrorMessage("bad-reply", false)).toBe("Couldn't load this agent's commands");
  });
});

describe("catalog_reply_decoders", () => {
  it("ListCommands decodes tolerantly — malformed payloads fail, not throw", () => {
    expect(parseSlashCommands([{ name: "compact", description: "d", inputHint: "h" }])).toEqual([
      { name: "compact", description: "d", inputHint: "h" },
    ]);
    expect(parseSlashCommands([{ name: "compact" }])).toBeNull();
    expect(parseSlashCommands("nope")).toBeNull();
  });

  it("ListSkills: null = not advertised, arrays validate, malformed fails", () => {
    expect(parseSkillsReply(null)).toEqual({ ok: true, skills: null });
    const parsed = parseSkillsReply([
      { name: "review", path: "/repo/SKILL.md", description: "d", enabled: true, command: { name: "review", harness: "claude-code" } },
    ]);
    expect(parsed).toEqual({
      ok: true,
      skills: [
        { name: "review", path: "/repo/SKILL.md", description: "d", enabled: true, command: { name: "review", harness: "claude-code" } },
      ],
    });
    expect(parseSkillsReply([{ name: "review", path: 3, description: "d", enabled: true }]).ok).toBe(false);
    expect(parseSkillsReply("nope").ok).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Workspace commands (composer.rs:5317-5413, the Roboco-local commands)
// ---------------------------------------------------------------------------

describe("with_workspace_commands / workspace_command_for_text", () => {
  it("merges the nine Roboco commands behind the provider rows, gated by chat", () => {
    const empty = withWorkspaceCommands([], true);
    expect(empty.map((row) => row.workspaceCommand)).toEqual([
      "model", "new", "resume", "settings", "diff", "files", "terminal", "rename", "stop",
    ]);
    expect(empty.map((row) => row.name)).toEqual([
      "model", "new", "resume", "settings", "diff", "files", "terminal", "rename", "stop",
    ]);
    // The needs-chat gate: without a chat, only the four global ones.
    const draftRows = withWorkspaceCommands([], false);
    expect(draftRows.map((row) => row.workspaceCommand)).toEqual([
      "model", "new", "resume", "settings",
    ]);
    // Descriptions read as Roboco-scoped.
    expect(empty.find((row) => row.workspaceCommand === "model")?.description).toContain("Roboco:");
  });

  it("preserves native commands and avoids collisions (desktop test port)", () => {
    const native: SlashCommand[] = [
      { name: "model", description: "Native model command", inputHint: "model id" },
      { name: "roboco:model", description: "Plugin command", inputHint: null },
    ];
    const rows = withWorkspaceCommands(invocationCandidates(native, []), true);
    expect(rows).toHaveLength(11);
    expect(rows[0]!.workspaceCommand).toBeNull();
    expect(rows[0]!.inputHint).toBe("model id");
    // The bare and singly-prefixed names belong to the provider: neither
    // dispatches. The workspace row lands under roboco:roboco:model.
    expect(workspaceCommandForText("/model", rows)).toBeNull();
    expect(workspaceCommandForText("/roboco:model", rows)).toBeNull();
    expect(workspaceCommandForText("/roboco:roboco:model", rows)).toBe("model");
    // The merge is idempotent (re-merging never duplicates).
    expect(withWorkspaceCommands(rows, true)).toHaveLength(11);
    expect(withWorkspaceCommands([], false)).toHaveLength(4);
  });

  it("matches only a whole-prompt command token, trailing whitespace allowed", () => {
    const rows = withWorkspaceCommands([], false);
    expect(workspaceCommandForText("/model", rows)).toBe("model");
    expect(workspaceCommandForText("/model  ", rows)).toBe("model");
    // A needs-chat command with no chat never matches.
    expect(workspaceCommandForText("/diff", rows)).toBeNull();
    const inChat = withWorkspaceCommands([], true);
    expect(workspaceCommandForText("/diff", inChat)).toBe("diff");
    // Prose, code, arguments, and paths never dispatch.
    for (const literal of [
      "    /model",
      "`/model`",
      "```\n/model\n```",
      "please /model",
      "/model extra",
      "/model/path",
    ]) {
      expect(workspaceCommandForText(literal, rows)).toBeNull();
    }
  });

  it("a skill row shadowing a workspace name prefixes the workspace row too", () => {
    const skills: Skill[] = [
      { name: "new", path: "/repo/new/SKILL.md", description: "A skill named new", enabled: true, command: null },
    ];
    const rows = withWorkspaceCommands(invocationCandidates([], skills), true);
    expect(workspaceCommandForText("/new", rows)).toBeNull();
    expect(workspaceCommandForText("/roboco:new", rows)).toBe("new");
  });
});
