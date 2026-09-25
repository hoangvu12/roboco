import { describe, expect, it } from "vitest";
import {
  cleanMessage,
  classifyDiffEmpty,
  defaultBaseRef,
  diffEmptyMessage,
  diffPhase,
  DIFF_SCOPE_CHIPS,
  normalizeCheckoutPath,
  parseKey,
  resolveDiff,
  scopeLabel,
  scopeMode,
  upsertDiffFrame,
} from "../src/lib/diff";

/**
 * The diff store's surviving pure logic — resolution (with the Windows
 * verbatim-path normalization), phase classification, the empty-state
 * classification, scope vocabulary, and frame upsert. The hand-rolled patch
 * parser / row model / geometry suites died with the code they covered
 * (web-pierre-adoption, ticket 04): both diff surfaces render through the
 * Pierre diffs library now, and their adapters carry their own suites
 * (`changes-diff.test.ts`, `tool-diff.test.ts`). Tests mirror the Rust
 * names (snake_case → camelCase).
 */

describe("resolveDiff", () => {
  type Diff = { readonly checkoutId: string; readonly deviceId: string; readonly cwd: string };
  const diffs: readonly Diff[] = [
    { checkoutId: "co-1", deviceId: "dev-a", cwd: "/repo/one" },
    { checkoutId: "co-2", deviceId: "dev-b", cwd: "/repo/two" },
  ];

  it("diffResolutionPrefersCheckoutIdThenCwd", () => {
    // checkout_id match wins even when cwd points elsewhere.
    expect(
      resolveDiff(diffs, { checkoutId: "co-2", deviceId: "dev-a", cwd: "/repo/one" })?.checkoutId,
    ).toBe("co-2");
    // Unknown checkout falls back to device+cwd.
    expect(
      resolveDiff(diffs, { checkoutId: "co-9", deviceId: "dev-a", cwd: "/repo/one" })?.checkoutId,
    ).toBe("co-1");
    // Wrong device still matches by cwd alone.
    expect(
      resolveDiff(diffs, { checkoutId: null, deviceId: "dev-z", cwd: "/repo/two" })?.checkoutId,
    ).toBe("co-2");
    // Nothing to go on.
    expect(resolveDiff(diffs, { checkoutId: null, deviceId: "dev-a", cwd: null })).toBeNull();
    expect(resolveDiff(diffs, { checkoutId: null, deviceId: "dev-a", cwd: "/elsewhere" })).toBeNull();
  });

  it("fallbackMatchesWindowsVerbatimPrefixAndSeparators", () => {
    // Regression (ticket 01, web-pierre-adoption): engine frames carry the
    // canonicalized cwd — on Windows `std::fs::canonicalize`'s verbatim
    // `\\?\C:\...` form — while chat rows carry plain paths, often
    // forward-slashed. The fallback compared raw strings, so a chat row
    // without a checkout id never matched its frame and the pane spun on
    // "Preparing diff…" forever. Normalization strips the verbatim prefix
    // (UNC form included) and unifies separators before comparing.
    const frames: readonly Diff[] = [
      { checkoutId: "co-1", deviceId: "dev-a", cwd: "\\\\?\\C:\\Users\\x\\repo" },
      { checkoutId: "co-2", deviceId: "dev-a", cwd: "\\\\?\\UNC\\server\\share\\repo" },
    ];
    // Verbatim frame vs plain forward-slashed chat cwd — the live-observed
    // pair (research §2.2).
    expect(
      resolveDiff(frames, { checkoutId: null, deviceId: "dev-a", cwd: "C:/Users/x/repo" })?.checkoutId,
    ).toBe("co-1");
    // The chat cwd may equally arrive backslashed (an engine-set tempdir).
    expect(
      resolveDiff(frames, { checkoutId: null, deviceId: "dev-a", cwd: "C:\\Users\\x\\repo" })?.checkoutId,
    ).toBe("co-1");
    // UNC verbatim (`\\?\UNC\server\share`) vs its plain `\\server\share`.
    expect(
      resolveDiff(frames, { checkoutId: null, deviceId: "dev-a", cwd: "\\\\server\\share\\repo" })?.checkoutId,
    ).toBe("co-2");
    // The device-scoped arm takes the same normalization.
    expect(
      resolveDiff(frames, { checkoutId: null, deviceId: "dev-b", cwd: "C:/Users/x/repo" })?.checkoutId,
    ).toBe("co-1");
    // Prefix similarity alone must not fuse two different folders.
    expect(
      resolveDiff(frames, { checkoutId: null, deviceId: "dev-a", cwd: "C:/Users/x/repository" }),
    ).toBeNull();
  });
});

describe("phases", () => {
  it("preparing clean and list follow the active diff", () => {
    expect(diffPhase(null)).toBe("preparing");
    expect(diffPhase({ patch: "  \n", files: [] })).toBe("clean");
    expect(diffPhase({ patch: "diff --git a/x b/x\n", files: [] })).toBe("list");
    // Engine may report files without patch text (truncation edge).
    expect(diffPhase({ patch: "", files: [{ path: "x" }] })).toBe("list");
  });
});

describe("normalizeCheckoutPath", () => {
  it("stripsVerbatimPrefixesAndUnifiesSeparators", () => {
    // The three live-observed spellings of one folder all share a key.
    expect(normalizeCheckoutPath("\\\\?\\C:\\Users\\x\\repo")).toBe("C:/Users/x/repo");
    expect(normalizeCheckoutPath("C:\\Users\\x\\repo")).toBe("C:/Users/x/repo");
    expect(normalizeCheckoutPath("C:/Users/x/repo")).toBe("C:/Users/x/repo");
    // UNC verbatim folds back to the plain UNC form; plain UNC is itself
    // separator-normalized so both sides still compare equal.
    expect(normalizeCheckoutPath("\\\\?\\UNC\\server\\share\\repo")).toBe("//server/share/repo");
    expect(normalizeCheckoutPath("\\\\server\\share\\repo")).toBe("//server/share/repo");
    // POSIX paths pass through untouched (the common non-Windows case).
    expect(normalizeCheckoutPath("/repo/one")).toBe("/repo/one");
  });
});

describe("classifyDiffEmpty", () => {
  // The shared frame: a same-device chat with a cwd whose watch has
  // delivered — everything varies from here.
  const base = {
    chatCwd: "C:/repo/x",
    chatDeviceId: "dev-a",
    ownDeviceId: "dev-a",
    checkoutId: null,
    watchLoaded: true,
  } satisfies Parameters<typeof classifyDiffEmpty>[0];

  it("remoteDeviceWinsOverEverything", () => {
    // The engine only tracks its own device's chats — a remote-hosted chat
    // never resolves here, so the device mismatch is the answer even while
    // the watch is still loading and no cwd exists.
    expect(classifyDiffEmpty({ ...base, chatDeviceId: "dev-b" })).toBe("remoteDevice");
    expect(
      classifyDiffEmpty({ ...base, chatDeviceId: "dev-b", watchLoaded: false, chatCwd: null }),
    ).toBe("remoteDevice");
    // An unknown own-device id cannot decide the comparison — fall through.
    expect(classifyDiffEmpty({ ...base, ownDeviceId: null })).toBe("notAGitRepository");
  });

  it("noCheckoutFolderBeatsTheWatch", () => {
    // A cwd-less chat has nothing to track no matter what the watch said.
    expect(classifyDiffEmpty({ ...base, chatCwd: null })).toBe("noCheckoutFolder");
    expect(classifyDiffEmpty({ ...base, chatCwd: null, watchLoaded: false })).toBe("noCheckoutFolder");
    // Blank cwd is the same condition.
    expect(classifyDiffEmpty({ ...base, chatCwd: "   " })).toBe("noCheckoutFolder");
  });

  it("undeliveredWatchStaysLoading", () => {
    // The watch's first frame enumerates the engine's tracked checkouts;
    // until it arrives, nothing is knowable — the spinner stays.
    expect(classifyDiffEmpty({ ...base, watchLoaded: false })).toBe("loading");
  });

  it("stampedCheckoutIdStaysLoading", () => {
    // The engine stamped the row's checkout id: the folder IS a git repo
    // and the engine tracks it — an outstanding capture is still loading,
    // never "not a git repository".
    expect(classifyDiffEmpty({ ...base, checkoutId: "co-1" })).toBe("loading");
    expect(
      classifyDiffEmpty({ ...base, checkoutId: "engine:v1:co-1", watchLoaded: true }),
    ).toBe("loading");
  });

  it("deliveredWatchWithNoStampMeansNotAGitRepository", () => {
    // Same device, has a cwd, the watch enumerated its checkouts, and the
    // engine never resolved a git identity for the folder — the smoke
    // harness's plain-tempdir chat.
    expect(classifyDiffEmpty(base)).toBe("notAGitRepository");
  });

  it("messagesNameTheConditionsPlainly", () => {
    expect(diffEmptyMessage("noCheckoutFolder")).toBe("This chat has no checkout folder.");
    expect(diffEmptyMessage("remoteDevice")).toBe(
      "This chat is hosted on another device — its diffs live on its own device.",
    );
    expect(diffEmptyMessage("notAGitRepository")).toBe("This chat's checkout isn't a git repository.");
    // The loading arm's copy is the spinner's — kept here so the suite pins
    // the full vocabulary.
    expect(diffEmptyMessage("loading")).toBe("Preparing diff…");
  });
});

describe("scope helpers", () => {
  it("headerLabelPluralizes", () => {
    expect(scopeLabel({ scope: "workingTree", count: 0 })).toBe("0 Uncommitted changes");
    expect(scopeLabel({ scope: "workingTree", count: 1 })).toBe("1 Uncommitted change");
    expect(scopeLabel({ scope: "workingTree", count: 4 })).toBe("4 Uncommitted changes");
  });

  it("scopeLabelsAndCleanMessages", () => {
    expect(scopeLabel({ scope: "branch", count: 1, base: "main" })).toBe("1 Changed file vs main");
    expect(scopeLabel({ scope: "branch", count: 3 })).toBe("3 Changed files");
    expect(scopeLabel({ scope: "turn", count: 2 })).toBe("2 Changed files this turn");
    expect(scopeLabel({ scope: "commit", count: 2 })).toBe("2 Changed files in this commit");
    expect(cleanMessage("workingTree", null)).toBe("No uncommitted changes");
    expect(cleanMessage("branch", "develop")).toBe("No changes vs develop");
    expect(cleanMessage("branch", null)).toBe("No branch changes");
    expect(cleanMessage("turn", null)).toBe("No changes this turn");
    expect(cleanMessage("commit", null)).toBe("No changes in this commit");
  });

  it("baseRefDefaultsToRepoDefaultThenMain", () => {
    // Engine order puts the repo default first — take it when it isn't the
    // checked-out branch itself.
    expect(defaultBaseRef(["main", "feature"], "feature")).toBe("main");
    // No origin/HEAD: engine "default" is the current branch — fall
    // through to main/master.
    expect(defaultBaseRef(["feature", "main"], "feature")).toBe("main");
    expect(defaultBaseRef(["feature", "master"], "feature")).toBe("master");
    // No main/master: any branch that isn't the current one.
    expect(defaultBaseRef(["feature", "develop"], "feature")).toBe("develop");
    // Checked out ON main: comparing main with itself is the honest default.
    expect(defaultBaseRef(["main", "feature"], "main")).toBe("main");
    // Single-branch repo, and empty list.
    expect(defaultBaseRef(["main"], "main")).toBe("main");
    expect(defaultBaseRef([], "main")).toBeNull();
  });

  it("scopeModesAreWireStable", () => {
    // `mode` is the GetCheckoutDiff wire contract — engine matches on it.
    expect(scopeMode("workingTree")).toBe("workingTree");
    expect(scopeMode("branch")).toBe("branch");
    expect(scopeMode("turn")).toBe("turn");
    expect(scopeMode("commit")).toBe("commit");
    // The scope chips expose the three selectable values; commit is
    // tab-mounted only, history is its own surface (ticket 27).
    expect(DIFF_SCOPE_CHIPS).toEqual(["workingTree", "branch", "turn"]);
  });
});

describe("parseKey", () => {
  it("folds every input that affects parse identity", () => {
    expect(parseKey("co", "ck", "branch", "main")).not.toBe(parseKey("co", "ck", "branch", "dev"));
    expect(parseKey("co", "ck", "branch", "main")).not.toBe(parseKey("co", "ck", "workingTree", "main"));
    expect(parseKey("co", "ck", "branch", "main")).not.toBe(parseKey("co", "other", "branch", "main"));
  });
});

describe("diff frames", () => {
  it("diffFramesReplaceListsAndUpsertSingles", () => {
    type Diff = { readonly checkoutId: string; readonly patch: string };
    let diffs: readonly Diff[] = [];
    const one: Diff = { checkoutId: "co-1", patch: "p1" };
    // Single frame inserts.
    diffs = upsertDiffFrame(diffs, one);
    expect(diffs).toHaveLength(1);
    // Identical frame is a no-op (same list identity).
    expect(upsertDiffFrame(diffs, one)).toBe(diffs);
    // Same checkout upserts in place.
    diffs = upsertDiffFrame(diffs, { checkoutId: "co-1", patch: "p2" });
    expect(diffs).toHaveLength(1);
    expect(diffs[0]!.patch).toBe("p2");
  });
});
