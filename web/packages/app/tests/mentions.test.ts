import { describe, expect, it } from "vitest";
import {
  displayRowSegments,
  droppedFileMention,
  fileMentionLinks,
  invocationLink,
  invocationLinks,
  localFileLink,
  mentionDisplayLabels,
  mentionResponseIsCurrent,
  mentionToken,
  mentionTooltipContains,
  mentionTooltipPromote,
  mentionTooltipReduce,
  sentMentionDisplay,
  TextProjection,
  type FileMentionLink,
  type Invocation,
  type MentionTooltipPhase,
  type MentionTooltipTarget,
} from "../src/lib/mentions";

/**
 * The mention/projection/tooltip unit tests — each describe named after the
 * `composer.rs` unit test it mirrors (8499-8692, 8425-8480, 8545).
 */

describe("mention_token_requires_a_token_boundary_and_tracks_full_token", () => {
  it("opens at a token boundary and spans the whole token", () => {
    expect(mentionToken("Fix @src/com", 12)).toEqual({ start: 4, end: 12, query: "src/com" });
  });

  it("rejects emails, mid-word @s, and path @s", () => {
    expect(mentionToken("mail@example.com", 16)).toBeNull();
    expect(mentionToken("word@file", 9)).toBeNull();
    expect(mentionToken("path/@file", 10)).toBeNull();
  });

  it("allows punctuation boundaries", () => {
    expect(mentionToken("See (@lib", 9)?.start).toBe(5);
    expect(mentionToken("See (@lib", 9)?.end).toBe(9);
  });

  it("bracketed mentions close at their matching bracket; escaped and mid-bracket @s never open", () => {
    expect(mentionToken("(@src)", 5)).toEqual({ start: 1, end: 5, query: "src" });
    expect(mentionToken("[@src]", 5)).toEqual({ start: 1, end: 5, query: "src" });
    expect(mentionToken("(@src)", 6)).toBeNull();
    expect(mentionToken("\\@src", 5)).toBeNull();
  });

  it("a quote continuation is still a token boundary (the `>` rule)", () => {
    expect(mentionToken(">@src", 5)?.start).toBe(1);
  });

  it("a token inside a canonical chip, a code span, or an unfinished link never opens", () => {
    const chip = localFileLink("src/@weird.rs", false);
    const raw = `see ${chip}`;
    expect(mentionToken(raw, raw.indexOf(".rs") + 1)).toBeNull();
    expect(mentionToken("`code @src` tail", 10)).toBeNull();
    expect(mentionToken("```\n@src\n``` tail", 7)).toBeNull();
    expect(mentionToken("    @src indent", 9)).toBeNull();
    expect(mentionToken("[label](https://ex @src", 24)).toBeNull();
  });
});

describe("file_mentions_serialize_to_strict_local_markdown", () => {
  it("percent-encodes and escapes the label", () => {
    const raw = localFileLink("src/a file#[x].rs", false);
    expect(raw).toBe("[a file#\\[x\\].rs](roboco-file:src/a%20file%23%5Bx%5D.rs)");
    const links = fileMentionLinks(raw);
    expect(links).toHaveLength(1);
    expect(links[0]?.path).toBe("src/a file#[x].rs");
    expect(links[0]?.basename).toBe("a file#[x].rs");
    expect(links[0]?.isDir).toBe(false);
  });

  it("directories keep the trailing slash", () => {
    const folder = localFileLink("src/components", true);
    expect(folder).toBe("[components](roboco-file:src/components/)");
    const links = fileMentionLinks(folder);
    expect(links[0]?.path).toBe("src/components");
    expect(links[0]?.isDir).toBe(true);
  });
});

describe("file_mentions_reject_external_or_noncanonical_markdown", () => {
  it("skips everything but canonical local links", () => {
    expect(fileMentionLinks("[site](https://example.com/a)")).toEqual([]);
    expect(fileMentionLinks("[a.rs](../a.rs)")).toEqual([]);
    expect(fileMentionLinks("[a.rs](src/a file.rs)")).toEqual([]);
    expect(fileMentionLinks("[other](src/a.rs)")).toEqual([]);
    expect(fileMentionLinks("[a.rs](src/a.rs)")).toEqual([]);
    expect(fileMentionLinks("[a.rs](src%5Cfake%5Ca.rs)")).toEqual([]);
    expect(fileMentionLinks("[a.rs](src/a%0A.rs)")).toEqual([]);
  });
});

describe("invocations_serialize_to_canonical_links_that_round_trip", () => {
  const command: Invocation = { kind: "command", name: "compact" };
  const skill: Invocation = {
    kind: "skill",
    name: "review",
    path: "/repo/a b/SKILL.md",
    command: { name: "plugin:review", harness: "claude-code" },
  };

  it("the link carries the prefix label and a hex JSON payload", () => {
    const raw = invocationLink(command);
    expect(raw).toContain("[/compact](roboco-invoke:");
    expect(invocationLinks(raw)).toHaveLength(1);
    expect(invocationLinks(raw)[0]?.invocation).toEqual(command);
    expect(invocationLinks(raw)[0]?.prefix).toBe("/");
    expect(invocationLinks(raw)[0]?.path).toBe("/compact");
    expect(invocationLink(skill)).toContain("[$review](roboco-invoke:");
    expect(invocationLinks(invocationLink(skill))[0]?.invocation).toEqual({ ...skill, command: skill.command });
  });

  it("only canonical links decode — literal `$`, code, and images never activate", () => {
    const link = invocationLink(skill);
    for (const literal of [
      `\`${link}\``,
      "```\n" + link + "\n```",
      `\\${link}`,
      `![example ${link}](example.png)`,
    ]) {
      expect(invocationLinks(literal)).toEqual([]);
    }
    expect(invocationLinks("plain $review text")).toEqual([]);
    expect(invocationLinks("[/x](roboco-invoke:bad)").length === 0).toBe(true);
    expect(invocationLinks("[/x](roboco-invoke:zz)").length === 0).toBe(true);
  });

  it("the pre-backtick-escaping label form remains recognizable", () => {
    const backtickSkill: Invocation = { kind: "skill", name: "review`ui", path: "/repo/SKILL.md" };
    const legacy = invocationLink(backtickSkill).replaceAll("\\`", "`");
    expect(invocationLinks(legacy)).toHaveLength(1);
    expect(invocationLinks(legacy)[0]?.invocation).toEqual({ ...backtickSkill, command: null });
    // File labels escape backticks now, and recognize the legacy form too.
    const file = localFileLink("src/a`b.rs", false);
    expect(file).toBe("[a\\`b.rs](roboco-file:src/a%60b.rs)");
    expect(fileMentionLinks(file)[0]?.path).toBe("src/a`b.rs");
    expect(fileMentionLinks(file.replaceAll("\\`", "`"))[0]?.path).toBe("src/a`b.rs");
  });

  it("two references in one draft stay ordered and independent", () => {
    const raw = `first ${invocationLink(command)} then ${invocationLink({
      kind: "skill",
      name: "review",
      path: "/repo/SKILL.md",
    })} finally`;
    const links = invocationLinks(raw);
    expect(links).toHaveLength(2);
    expect(links[0]!.start).toBeLessThan(links[1]!.start);
  });
});

describe("dropped_mentions_are_separated_from_surrounding_text", () => {
  it("supplies its own separators when none follow", () => {
    const drop = droppedFileMention("fixnow", { start: 3, end: 3 }, "src/lib.rs", false);
    expect(drop?.inserted).toBe(" [lib.rs](roboco-file:src/lib.rs) ");
    expect(drop?.cursorAdvance).toBe(drop?.inserted.length);
  });

  it("advances past an existing separator", () => {
    const drop = droppedFileMention("fix now", { start: 3, end: 3 }, "src/components", true);
    expect(drop?.inserted).toBe(" [components](roboco-file:src/components/)");
    expect(drop?.cursorAdvance).toBe(drop!.inserted.length + 1);
  });
});

describe("dropped_mentions_reject_paths_outside_the_workspace", () => {
  it("absolute and traversing paths never insert", () => {
    expect(droppedFileMention("", { start: 0, end: 0 }, "/tmp/file.rs", false)).toBeNull();
    expect(droppedFileMention("", { start: 0, end: 0 }, "../file.rs", false)).toBeNull();
  });
});

describe("duplicate_mention_basenames_use_unique_suffixes", () => {
  it("chips expand to the unique path suffix", () => {
    const raw = `${localFileLink("src/one/mod.rs", false)} ${localFileLink("src/two/mod.rs", false)}`;
    const projection = new TextProjection(raw);
    expect(projection.display).toContain("one/mod.rs");
    expect(projection.display).toContain("two/mod.rs");
  });
});

describe("mention_suffixes_compare_path_components", () => {
  it("foo/mod.rs + bar/oomod.rs stay distinct — components, never substrings", () => {
    const links: FileMentionLink[] = [
      { start: 0, end: 0, basename: "mod.rs", path: "foo/mod.rs", isDir: false, prefix: "@", invocation: null },
      { start: 0, end: 0, basename: "oomod.rs", path: "bar/oomod.rs", isDir: false, prefix: "@", invocation: null },
    ];
    expect(mentionDisplayLabels(links)).toEqual(["mod.rs", "oomod.rs"]);
  });
});

describe("projection_maps_and_expands_atomic_chip_ranges", () => {
  const raw = `open ${localFileLink("src/composer.rs", false)} now`;
  const projection = new TextProjection(raw);
  const chip = projection.mentions[0]!;

  it("projects NBSP @label NBSP", () => {
    expect(projection.display.slice(chip.start, chip.end)).toBe("\u00a0@composer.rs\u00a0");
  });
  it("display offsets inside the chip snap to its raw edges", () => {
    expect(projection.displayToRaw(chip.start + 1)).toBe(chip.link.start);
    expect(projection.displayToRaw(chip.end - 1)).toBe(chip.link.end);
  });

  it("Left/Right boundaries step over the whole chip", () => {
    expect(projection.previousBoundary(chip.link.end)).toBe(chip.link.start);
    expect(projection.nextBoundary(chip.link.start)).toBe(chip.link.end);
  });

  it("a selection overlapping the chip swallows it whole", () => {
    expect(projection.normalizeRange(chip.link.start + 2, chip.link.end - 2)).toEqual({
      start: chip.link.start,
      end: chip.link.end,
    });
  });
});

describe("invocation_chips_share_the_projection_and_the_sent_display", () => {
  const command: Invocation = { kind: "command", name: "compact" };
  const skill: Invocation = { kind: "skill", name: "review", path: "/repo/SKILL.md" };
  const raw = `run ${invocationLink(command)} with ${invocationLink(skill)} and ${localFileLink("src/lib.rs", false)}`;

  it("chips carry their own prefix and stay atomic, files and invocations merged in range order", () => {
    const projection = new TextProjection(raw);
    expect(projection.mentions.map((chip) => chip.link.prefix)).toEqual(["/", "$", "@"]);
    expect(projection.display).toContain("\u00a0/compact\u00a0");
    expect(projection.display).toContain("\u00a0$review\u00a0");
    const skillChip = projection.mentions[1]!;
    expect(projection.previousBoundary(skillChip.link.end)).toBe(skillChip.link.start);
    expect(projection.displayToRaw(skillChip.start + 1)).toBe(skillChip.link.start);
  });

  it("duplicate invocation names disambiguate as `name · suffix`", () => {
    const raw2 = `${invocationLink({ kind: "skill", name: "review", path: "/a/SKILL.md" })} ${invocationLink({
      kind: "skill",
      name: "review",
      path: "/b/SKILL.md",
    })}`;
    const projection2 = new TextProjection(raw2);
    expect(projection2.display).toContain("review\u00a0·\u00a0a/SKILL.md");
    expect(projection2.display).toContain("review\u00a0·\u00a0b/SKILL.md");
  });

  it("sent_mention_display projects invocation chips with the reference's detail as the path", () => {
    const sent = sentMentionDisplay(raw);
    expect(sent).not.toBeNull();
    const { display, mentions } = sent!;
    expect(display).not.toContain("roboco-invoke:");
    expect(mentions.map((span) => span.path)).toEqual(["/compact", "/repo/SKILL.md", "src/lib.rs"]);
    expect(mentions.every((span) => span.isDir === false)).toBe(true);
    expect(sentMentionDisplay("ordinary $dollar /slash text")).toBeNull();
  });
});

describe("sent_mention_display_projects_chips_for_the_transcript", () => {
  it("collapses links to chips with full paths", () => {
    const raw = `check ${localFileLink("src/composer.rs", false)} and ${localFileLink("src/components", true)}`;
    const projection = sentMentionDisplay(raw);
    expect(projection).not.toBeNull();
    const { display, mentions } = projection!;
    expect(display).not.toContain("roboco-file:");
    expect(display).toContain("composer.rs");
    expect(display).toContain("components");
    expect(mentions).toHaveLength(2);
    expect(display.slice(mentions[0]!.start, mentions[0]!.end)).toBe("\u00a0@composer.rs\u00a0");
    expect(mentions[0]!.isDir).toBe(false);
    expect(mentions[0]!.path).toBe("src/composer.rs");
    expect(mentions[1]!.isDir).toBe(true);
    expect(mentions[1]!.path).toBe("src/components/");
  });
});

describe("sent_mention_display_leaves_plain_prompts_untouched", () => {
  it("stays on the zero-cost path — including scheme talk and hostile paths", () => {
    expect(sentMentionDisplay("fix the composer")).toBeNull();
    expect(sentMentionDisplay("what is a roboco-file: link?")).toBeNull();
    expect(sentMentionDisplay("[a.rs](roboco-file:../a.rs)")).toBeNull();
  });
});

describe("mention_wash_moves_wholly_to_the_next_visual_row_at_a_wrap", () => {
  it("a wrapped range gets a fresh segment starting at the new row's start", () => {
    expect(displayRowSegments({ start: 12, end: 24 }, [12, 40])).toEqual([
      { row: 1, rowStart: 12, start: 12, end: 24 },
    ]);
    expect(displayRowSegments({ start: 8, end: 24 }, [12, 40])).toEqual([
      { row: 0, rowStart: 0, start: 8, end: 12 },
      { row: 1, rowStart: 12, start: 12, end: 24 },
    ]);
  });
});

function tooltipTarget(start: number, end: number, path: string): MentionTooltipTarget {
  return { start, end, path };
}

describe("mention_tooltip_wait_survives_pointer_jitter_and_promotes_once", () => {
  it("same-chip jitter keeps the wait; a stale timer never reveals", () => {
    const target = tooltipTarget(3, 20, "src/composer.rs");
    const waiting: MentionTooltipPhase = { kind: "waiting", target, generation: 1 };
    const restarted = mentionTooltipReduce(waiting, target, false, 2);
    expect(restarted).toEqual(waiting);
    expect(restarted.kind).toBe("waiting");
    if (restarted.kind === "waiting") {
      expect(restarted.generation).toBe(1);
    }
    expect(mentionTooltipPromote(restarted, 2, true)).toEqual(restarted);
    const visible = mentionTooltipPromote(restarted, 1, true);
    expect(visible.kind).toBe("visible");
    if (visible.kind === "visible") {
      expect(visible.generation).toBe(1);
    }
    expect(mentionTooltipReduce(visible, target, false, 3)).toEqual(visible);
  });
});

describe("mention_tooltip_changes_target_and_cancels_disappeared_target", () => {
  it("a different chip restarts the wait; a dead target hides", () => {
    const first = tooltipTarget(0, 10, "src/a.rs");
    const second = tooltipTarget(20, 30, "src/a.rs");
    const visible: MentionTooltipPhase = { kind: "visible", target: first, generation: 4 };
    const next = mentionTooltipReduce(visible, second, false, 5);
    expect(next.kind).toBe("waiting");
    if (next.kind === "waiting") {
      expect(next.generation).toBe(5);
    }
    expect(
      mentionTooltipPromote({ kind: "waiting", target: tooltipTarget(20, 30, "src/a.rs"), generation: 5 }, 5, false),
    ).toEqual({ kind: "hidden" });
  });
});

describe("mention_tooltip_stays_visible_over_chip_or_popup_only", () => {
  it("in-chip or in-popup keeps it", () => {
    expect(mentionTooltipContains(true, false)).toBe(true);
    expect(mentionTooltipContains(false, true)).toBe(true);
    expect(mentionTooltipContains(false, false)).toBe(false);
  });
});

describe("dismissed_mentions_reject_stale_responses", () => {
  it("only a matching generation with an open token lands", () => {
    const state = { request: 7, token: mentionToken("@src", 4) };
    expect(mentionResponseIsCurrent(state, 7)).toBe(true);
    const stale = { request: 8, token: null };
    expect(mentionResponseIsCurrent(stale, 7)).toBe(false);
    expect(mentionResponseIsCurrent(stale, 8)).toBe(false);
  });
});
