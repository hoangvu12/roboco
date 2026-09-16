import { describe, expect, it } from "vitest";
import { filterAndSort, matchRank } from "../src/lib/picker-search";

describe("matchRank", () => {
  it("returns -1 for a non-match", () => {
    expect(matchRank("Claude", "zeta")).toBe(-1);
  });

  it("returns 0 for an exact prefix (case-insensitive)", () => {
    expect(matchRank("claude-code", "claude")).toBe(0);
    expect(matchRank("Claude Code", "cLaUdE")).toBe(0);
  });

  it("returns 1 + offset for a substring match (earlier offsets win)", () => {
    expect(matchRank("xclaude", "claude")).toBe(2);
    expect(matchRank("xxxclaude", "claude")).toBeGreaterThan(matchRank("xclaude", "claude")!);
  });

  it("returns 0 for an empty query", () => {
    expect(matchRank("anything", "")).toBe(0);
  });
});

describe("filterAndSort", () => {
  it("returns the full list for an empty query", () => {
    expect(filterAndSort(["a", "b", "c"], (item) => item, "")).toEqual(["a", "b", "c"]);
  });

  it("sorts prefix matches ahead of substring matches", () => {
    const labels = ["xclaude", "claude-code", "anthropic", "Claude-Sonnet"];
    const sorted = filterAndSort(labels, (item) => item, "claude");
    expect(sorted[0]).toBe("claude-code");
    expect(sorted).toContain("xclaude");
    expect(sorted).toContain("Claude-Sonnet");
    // Substring hits land after the prefix.
    expect(sorted.indexOf("claude-code")).toBeLessThan(sorted.indexOf("xclaude"));
  });

  it("is case-insensitive", () => {
    const sorted = filterAndSort(["Claude-Code", "claude-code"], (item) => item, "CLAUDE");
    expect(sorted).toEqual(["Claude-Code", "claude-code"]);
  });

  it("preserves input order on ties", () => {
    const sorted = filterAndSort(["a", "b", "c"], (item) => item, "");
    expect(sorted).toEqual(["a", "b", "c"]);
  });

  it("returns an empty array when nothing matches", () => {
    expect(filterAndSort(["alpha", "beta"], (item) => item, "zz")).toEqual([]);
  });
});
