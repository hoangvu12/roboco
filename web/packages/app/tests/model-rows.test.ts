import { describe, expect, it } from "vitest";
import type { HarnessDescriptor, HarnessId, Model } from "@roboco/proto";
import type { ModelOption } from "@roboco/proto";
import {
  compactEffort,
  compactHiddenOptions,
  defaultModel,
  fastModeValues,
  normalizeModelRows,
  offeredHarnessesImpl,
  REASONING_SETTING_ID,
  scopedModelRows,
  scopedRailDescriptors,
  selectedOnlyRow,
  settingGroups,
  visibleHarnessesImpl,
  workspaceFooterLayout,
} from "../src/lib/model-rows";

function descriptor(id: HarnessId, name: string, extra: Partial<HarnessDescriptor> = {}): HarnessDescriptor {
  return {
    id,
    name,
    supportsSteering: true,
    steeringMode: "step-boundary",
    reasoningLevels: [],
    installed: true,
    canInstall: false,
    enabled: null,
    ...extra,
  };
}

function bareModel(id: string, label: string, extra: Partial<Model> = {}): Model {
  return {
    id,
    label,
    description: null,
    reasoningLevels: [],
    options: [],
    ...extra,
  };
}

describe("default_model", () => {
  it("default_model_is_first_catalog_row", () => {
    const models = [bareModel("flagship", "Flagship"), bareModel("fast", "Fast")];
    expect(defaultModel(models)?.id).toBe("flagship");
    expect(defaultModel([])).toBeNull();
  });
});

describe("scoped_model_rows", () => {
  const descriptors = [descriptor("claude-code", "Claude Code"), descriptor("codex", "Codex")];
  const claude = [bareModel("fable-5", "Fable 5")];
  const codex = [bareModel("gpt-fable", "Fable (Codex)")];
  const modelsFor = (harness: HarnessId): readonly Model[] | null =>
    harness === "claude-code" ? claude : harness === "codex" ? codex : null;

  it("tab_search_never_leaves_the_viewed_harness", () => {
    // Both catalogs match "fable", but the viewed tab is Claude — the Codex
    // hit must not appear.
    const rows = scopedModelRows("fable", "harness", "claude-code", descriptors, modelsFor, () => false);
    expect(rows).toHaveLength(1);
    expect(rows[0]!.harness).toBe("claude-code");
    expect(rows[0]!.model.id).toBe("fable-5");
  });

  it("favorites_tab_search_ranks_only_starred_rows", () => {
    const starred = (harness: HarnessId, model: string): boolean =>
      harness === "codex" && model === "gpt-fable";
    const rows = scopedModelRows("fable", "favorites", "claude-code", descriptors, modelsFor, starred);
    expect(rows).toHaveLength(1);
    expect(rows[0]!.harness).toBe("codex");

    // Empty query on the favorites tab: the starred set, nothing else.
    const unfiltered = scopedModelRows("", "favorites", "claude-code", descriptors, modelsFor, starred);
    expect(unfiltered).toHaveLength(1);
    expect(unfiltered[0]!.model.id).toBe("gpt-fable");
  });

  it("harness_tab_lists_stars_first_and_description_still_matches", () => {
    const descriptors = [descriptor("opencode", "opencode")];
    const providerA = bareModel("glm-5.2-a", "GLM-5.2", { description: "Anthropic" });
    const providerB = bareModel("glm-5.2-b", "GLM-5.2", { description: "Baseten" });
    const models = [providerA, providerB];
    const modelsFor = (harness: HarnessId): readonly Model[] | null =>
      harness === "opencode" ? models : null;
    const starred = (harness: HarnessId, model: string): boolean =>
      harness === "opencode" && model === "glm-5.2-b";

    // No query: catalog order with the star floated to the top.
    const rows = scopedModelRows("", "harness", "opencode", descriptors, modelsFor, starred);
    expect(rows[0]!.model.id).toBe("glm-5.2-b");
    expect(rows[1]!.model.id).toBe("glm-5.2-a");

    // Provider attribution stays searchable inside the tab.
    const byDescription = scopedModelRows("baseten", "harness", "opencode", descriptors, modelsFor, starred);
    expect(byDescription).toHaveLength(1);
    expect(byDescription[0]!.model.id).toBe("glm-5.2-b");
  });
});


describe("rail descriptor scoping (pickers.rs:1936-1940)", () => {
  const claude = descriptor("claude-code", "Claude Code");
  const codex = descriptor("codex", "Codex");
  const offered = [claude, codex];

  it("locked_chats_rail_retains_only_the_locked_harness", () => {
    // A locked chat (a side chat whose config is not editable) restricts
    // every view to its own harness: the rail retains only that tab, so
    // neither the list nor the favorites view can render a dead foreign
    // row whose pick would no-op under the lock.
    expect(scopedRailDescriptors(offered, true, "claude-code")).toEqual([claude]);
    // A harness outside the rail stays out, and nothing is retained when
    // there is no effective harness to keep (the desktop's retain arm).
    expect(scopedRailDescriptors([codex], true, "claude-code")).toEqual([]);
    expect(scopedRailDescriptors(offered, true, null)).toEqual([]);
  });

  it("unlocked_chats_keep_the_full_offered_rail", () => {
    // New chats and editable side chats offer the full set, order kept —
    // the favorites view spans every harness.
    expect(scopedRailDescriptors(offered, false, "claude-code")).toEqual([claude, codex]);
  });
});

describe("compact model rows (upstream #471)", () => {
  const descriptors = [descriptor("claude-code", "Claude"), descriptor("codex", "Codex")];
  const claude = [bareModel("plain-a", "Model A"), bareModel("star-a", "My Model A")];
  const codex = [bareModel("plain-b", "Model B"), bareModel("star-b", "My Model B")];
  const modelsFor = (harness: HarnessId): readonly Model[] | null =>
    harness === "claude-code" ? claude : harness === "codex" ? codex : null;
  const isFavorite = (harness: HarnessId, model: string): boolean => model.startsWith("star-");

  it("compact_all_models_keeps_favorites_first_in_catalog_and_search", () => {
    for (const query of ["", "model"]) {
      const rows = scopedModelRows(query, "all", "codex", descriptors, modelsFor, isFavorite);
      expect(rows.map((row) => row.model.id)).toEqual(["star-a", "star-b", "plain-a", "plain-b"]);
    }
  });

  it("every_fast_mode_encoding_gets_the_same_controls", () => {
    const option = (id: string, choices: readonly string[], defaultChoice: string): ModelOption => ({
      id,
      label: id,
      choices: choices.map((choice) => ({ id: choice, label: choice })),
      defaultChoice,
    });
    // Codex tier, Claude toggle, the speed option and the snake_case toggle.
    expect(fastModeValues(option("serviceTier", ["default", "fast"], "default"))).toEqual({
      on: "fast",
      off: "default",
    });
    expect(fastModeValues(option("fastMode", ["off", "on"], "off"))).toEqual({ on: "on", off: "off" });
    expect(fastModeValues(option("speed", ["standard", "fast"], "standard"))).toEqual({
      on: "fast",
      off: "standard",
    });
    // Cursor's true/false switch, off and on by default: both toggle.
    expect(fastModeValues(option("fast", ["false", "true"], "false"))).toEqual({
      on: "true",
      off: "false",
    });
    expect(fastModeValues(option("fast", ["false", "true"], "true"))).toEqual({
      on: "true",
      off: "false",
    });
    // Not fast mode: other toggles, including Cursor's true/false ones.
    expect(fastModeValues(option("thinking", ["off", "on"], "off"))).toBeNull();
    expect(fastModeValues(option("thinking", ["false", "true"], "true"))).toBeNull();
    expect(fastModeValues(option("contextWindow", ["200k", "1m"], "200k"))).toBeNull();
  });

  it("compact_effort_uses_the_ladder_else_an_effort_option", () => {
    // The ladder wins when present.
    const laddered = bareModel("m", "M", { reasoningLevels: ["low", "high"] });
    expect(compactEffort(laddered, ["low", "high"], "high", {})).toEqual({
      kind: "reasoning",
      labels: ["Low", "High"],
      levels: ["low", "high"],
      selected: 1,
    });
    // Cursor's shape: no ladder, effort as an option; the slider owns it.
    const cursor = bareModel("opus", "Opus", {
      options: [
        {
          id: "effort",
          label: "Effort",
          defaultChoice: "high",
          choices: [
            { id: "low", label: "Low" },
            { id: "medium", label: "Medium" },
            { id: "high", label: "High" },
          ],
        },
      ],
    });
    const effort = compactEffort(cursor, [], null, {});
    expect(effort?.kind).toBe("option");
    expect(effort?.labels).toEqual(["Low", "Medium", "High"]);
    expect(effort?.selected).toBe(2);
    expect(compactHiddenOptions(cursor)).toEqual(["effort"]);
    // No ladder and no effort option: no slider.
    expect(compactEffort(bareModel("x", "X"), [], null, {})).toBeNull();
  });
});

describe("normalize_model_rows", () => {
  it("normalize_drops_default_alias_and_folds_orphan_1m_rows", () => {
    // The shape an OLDER engine serves: a `default` alias row plus 1M-pinned
    // variants with no bare base. A non-claude harness keeps wire labels (no
    // curated catalog to borrow from).
    const models = normalizeModelRows("codex", [
      bareModel("default", "Default (recommended)"),
      bareModel("titan[1m]", "Titan (1M context)"),
      bareModel("gpt-x-9[1m]", "GPT X-9"),
      bareModel("nano", "Nano"),
    ]);
    expect(models.map((model) => model.id)).toEqual(["titan", "gpt-x-9", "nano"]);
    expect(models[0]!.label).toBe("Titan");
    expect(models[1]!.label).toBe("GPT X-9");
    // Folded rows pin the Context Window trait to 1M.
    expect(
      models[0]!.options.some(
        (option) => option.id === "contextWindow" && option.defaultChoice === "1m",
      ),
    ).toBe(true);
    expect(models[2]!.options).toHaveLength(0);

    // A `default`-only list survives (nothing real to prefer).
    const onlyDefault = normalizeModelRows("codex", [bareModel("default", "Default")]);
    expect(onlyDefault).toHaveLength(1);

    // A base-plus-variant pair: variant drops, base is untouched.
    const paired = normalizeModelRows("codex", [
      bareModel("titan-5", "Titan 5"),
      bareModel("titan-5[1m]", "Titan 5 (1M)"),
    ]);
    expect(paired).toHaveLength(1);
    expect(paired[0]!.id).toBe("titan-5");

    // Idempotent over a clean list.
    const clean = [bareModel("titan-5", "Titan 5")];
    expect(normalizeModelRows("codex", clean)).toEqual(clean);
  });

  it("normalize_gives_claude_rows_their_versioned_catalog_labels", () => {
    // The real prod shape: alias values with terse names. Claude rows adopt
    // the curated labels so the version number always shows, exact ids
    // included; foreign ids pass through.
    const models = normalizeModelRows("claude-code", [
      bareModel("default", "Default (recommended)"),
      bareModel("opus[1m]", "Opus (1M context)"),
      bareModel("claude-fable-5[1m]", "Fable"),
      bareModel("sonnet", "Sonnet"),
      bareModel("haiku", "Haiku"),
      bareModel("claude-nova-1", "Nova 1"),
    ]);
    expect(models.map((model) => model.label)).toEqual([
      "Opus 5.5",
      "Fable 5",
      "Sonnet 5",
      "Haiku 4.5",
      "Nova 1",
    ]);
    expect(models.map((model) => model.id)).toEqual([
      "opus",
      "claude-fable-5",
      "sonnet",
      "haiku",
      "claude-nova-1",
    ]);
  });
});

describe("harness visibility", () => {
  it("mock_harness_hidden_unless_alone", () => {
    const mixed = [descriptor("mock", "Mock"), descriptor("claude-code", "Claude Code")];
    // Env-independent core: mock hidden in production…
    const visible = visibleHarnessesImpl(mixed, false);
    expect(visible).toHaveLength(1);
    expect(visible[0]!.id).toBe("claude-code");
    const onlyMock = [descriptor("mock", "Mock")];
    expect(visibleHarnessesImpl(onlyMock, false)).toHaveLength(1);
    // …and opted back in by the dev rig (the web never sets it).
    expect(visibleHarnessesImpl(mixed, true)).toHaveLength(2);
    expect(visibleHarnessesImpl(mixed, true)[0]!.id).toBe("mock");
  });

  it("offered_harnesses_follow_the_catalog_enabled_flags", () => {
    const withFlags = (id: HarnessId, name: string, enabled: boolean | null): HarnessDescriptor =>
      descriptor(id, name, { enabled });
    const catalog = (
      claude: boolean | null,
      codex: boolean | null,
      grok: boolean | null,
    ): HarnessDescriptor[] => [
      withFlags("mock", "Mock", false),
      withFlags("claude-code", "Claude Code", claude),
      withFlags("codex", "Codex", codex),
      withFlags("grok", "Grok", grok),
    ];
    // A catalog from an engine predating the flag (all null) follows its
    // installed probes, so every detected real harness is offered.
    expect(offeredHarnessesImpl(catalog(null, null, null), false).map((d) => d.id)).toEqual([
      "claude-code",
      "codex",
      "grok",
    ]);
    // The device's flags win: Grok on, Codex off; catalog order holds.
    expect(offeredHarnessesImpl(catalog(true, false, true), false).map((d) => d.id)).toEqual([
      "claude-code",
      "grok",
    ]);
    // The dev-rig mock opt-in survives the enabled filter.
    expect(offeredHarnessesImpl(catalog(true, false, null), true).map((d) => d.id)).toEqual([
      "mock",
      "claude-code",
      "grok",
    ]);
    // Nothing enabled offers nothing — the no-agents empty state.
    expect(offeredHarnessesImpl(catalog(false, false, false), false)).toEqual([]);
    // So does a legacy catalog whose installed probes all failed.
    const missing = catalog(null, null, null).map((d) => ({ ...d, installed: false }));
    expect(offeredHarnessesImpl(missing, false)).toEqual([]);
  });

  it("offered_harnesses_require_an_installed_cli", () => {
    const catalog = [
      descriptor("claude-code", "Claude Code", { enabled: true, installed: false }),
      descriptor("codex", "Codex", { enabled: true, installed: false }),
      descriptor("grok", "Grok", { enabled: true, installed: true }),
    ];
    expect(offeredHarnessesImpl(catalog, false).map((d) => d.id)).toEqual(["grok"]);
    // Nothing enabled AND installed: an empty offered set — never resurface
    // unrunnable agents just to avoid an empty picker.
    const all = [
      descriptor("claude-code", "Claude Code", { enabled: true, installed: false }),
      descriptor("codex", "Codex", { enabled: false, installed: false }),
      descriptor("grok", "Grok", { enabled: false, installed: true }),
    ];
    expect(offeredHarnessesImpl(all, false)).toEqual([]);
  });
});

describe("selected_only row (pickers.rs:1996-2033)", () => {
  it("selected_only_row_builds_the_row_with_the_remembered_label_fallback", () => {
    // The synthetic row names the chat's pick when the fresh catalog no
    // longer lists it: the remembered label, else the raw id; the desktop's
    // verbatim description rides the standard attribution slot, and the row
    // carries no traits of its own (an absent model offers nothing).
    const row = selectedOnlyRow("claude-code", "Claude", "gone-model", "Gone model");
    expect(row.harness).toBe("claude-code");
    expect(row.harnessName).toBe("Claude");
    expect(row.selectedOnly).toBe(true);
    expect(row.model.id).toBe("gone-model");
    expect(row.model.label).toBe("Gone model");
    // No remembered label: the raw id names the pick.
    const bare = selectedOnlyRow("claude-code", "Claude", "gone-model", null);
    expect(bare.model.label).toBe("gone-model");
    for (const candidate of [row, bare]) {
      expect(candidate.model.description).toBe(
        "Selected in this chat; absent from the current model list",
      );
      expect(candidate.model.reasoningLevels).toEqual([]);
      expect(candidate.model.options).toEqual([]);
    }
  });

  it("scoped_model_rows_stays_pure_no_row_is_ever_selected_only", () => {
    // The unshift is the identity card's job (it gates on rail/loaded/
    // absent); the scoped catalog itself must never mint a synthetic row —
    // otherwise every consumer (compact, favorites) would inherit it.
    const models = [bareModel("haiku", "Haiku")];
    const rows = scopedModelRows(
      "",
      "harness",
      "claude-code",
      [descriptor("claude-code", "Claude")],
      () => models,
      () => false,
    );
    expect(rows.map((entry) => entry.selectedOnly)).toEqual([false]);
  });
});

describe("setting_groups", () => {
  const opus = bareModel("opus", "Opus", {
    reasoningLevels: ["low", "high"],
    options: [
      {
        id: "contextWindow",
        label: "Context window",
        defaultChoice: "standard",
        choices: [
          { id: "standard", label: "Standard" },
          { id: "extended", label: "Extended" },
        ],
      },
      {
        id: "emptyOption",
        label: "Never offered",
        defaultChoice: "x",
        choices: [],
      },
    ],
  });

  it("builds_the_reasoning_group_then_every_option_with_choices", () => {
    const groups = settingGroups(opus, ["low", "high"], "high", {});
    expect(groups.map((group) => group.id)).toEqual([REASONING_SETTING_ID, "contextWindow"]);
    expect(groups[0]!.label).toBe("Reasoning");
    expect(groups[0]!.choices.map((choice) => choice.reasoning)).toEqual(["low", "high"]);
    expect(groups[0]!.choices.map((choice) => choice.selected)).toEqual([false, true]);
    expect(groups[0]!.choices.map((choice) => choice.isDefault)).toEqual([false, true]);
    expect(groups[1]!.label).toBe("Context window");
  });

  it("resolves_each_groups_selected_choice_from_the_saved_pick_or_default", () => {
    const saved = { contextWindow: "extended" };
    const groups = settingGroups(opus, ["low", "high"], null, saved);
    expect(groups[1]!.choices.map((choice) => choice.selected)).toEqual([false, true]);
    expect(groups[1]!.choices.map((choice) => choice.isDefault)).toEqual([true, false]);
    // No reasoning pick: nothing in the ladder reads selected.
    expect(groups[0]!.choices.every((choice) => !choice.selected)).toBe(true);
  });

  it("is_empty_without_a_ladder_or_offered_options", () => {
    expect(settingGroups(undefined, [], null, {})).toEqual([]);
    const haiku = bareModel("haiku", "Haiku");
    expect(settingGroups(haiku, [], null, {})).toEqual([]);
  });
});

describe("workspace_footer_row", () => {
  it("workspace_footer_pair_keeps_its_leading_edge_and_gap", () => {
    // The flex model of the footer row (`workspaceFooterLayout`): the pair
    // keeps its leading edge across container widths, the inter-chip gap is
    // exactly 4px, the chips share a top, and the trailing cluster's right
    // edge sits width - 20 from the pair's left edge.
    const options = { checkoutWidth: 120, refWidth: 90, trailingWidth: 60 };
    let firstLeft: number | undefined;
    for (const width of [320, 680, 1000, 320]) {
      const layout = workspaceFooterLayout(width, options);
      if (firstLeft === undefined) {
        firstLeft = layout.checkout.left;
      }
      expect(layout.checkout.left).toBe(firstLeft);
      expect(layout.ref.left - layout.checkout.right).toBe(4);
      expect(layout.checkout.top).toBe(layout.ref.top);
      expect(layout.trailingRight - layout.checkout.left).toBe(width - 20);
    }
  });
});
