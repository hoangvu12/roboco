import { describe, expect, it } from "vitest";
import { createElement } from "react";
import { renderToString } from "react-dom/server";
import type { ChangeRequestState, ChangeRequestSummary } from "@roboco/proto";
import { ChangeRequestBadge } from "../src/components/change-request-badge";
import {
  PROVIDERS,
  badgeModel,
  normalizeProvider,
  toneFor,
} from "../src/lib/change-requests";

function summary(state: ChangeRequestState): ChangeRequestSummary {
  return {
    provider: "github",
    number: 90,
    title: "First line\nSecond line",
    url: "https://github.com/acme/roboco/pull/90",
    state,
    baseRef: "main",
    headRef: "feature/pr",
  };
}

describe("badgeModel", () => {
  it("renders the Open / Merged / Closed labels and tones with the bare number", () => {
    const cases: Array<[ChangeRequestState, string, "open" | "merged" | "closed"]> = [
      ["open", "Open", "open"],
      ["merged", "Merged", "merged"],
      ["closed", "Closed", "closed"],
    ];
    for (const [state, label, tone] of cases) {
      const model = badgeModel(summary(state));
      // Upstream f8f9c97f: the badge model carries the bare number — the `#`
      // lives only in the tooltip's "PR #N".
      expect(model.number).toBe("90");
      expect(model.stateLabel).toBe(label);
      expect(model.tone).toBe(tone);
      expect(model.title).toBe("First line Second line");
    }
  });

  it("maps state to tone", () => {
    expect(toneFor("open")).toBe("open");
    expect(toneFor("merged")).toBe("merged");
    expect(toneFor("closed")).toBe("closed");
  });
});

// The create-PR compare-URL builder and its tests are gone (ticket 04): the
// desktop has no create flow at all, so guessing a provider's compare URL was
// web-only invention.

describe("normalizeProvider", () => {
  it("knows the provider keys it can normalize", () => {
    expect(PROVIDERS).toEqual(["github", "gitlab", "bitbucket", "azuredevops", "codeberg"]);
  });

  it("lowercases known provider names", () => {
    expect(normalizeProvider("GitHub")).toBe("github");
    expect(normalizeProvider("GITLAB")).toBe("gitlab");
    expect(normalizeProvider("bitbucket")).toBe("bitbucket");
  });

  it("returns null for missing or whitespace-only input", () => {
    expect(normalizeProvider(null)).toBeNull();
    expect(normalizeProvider(undefined)).toBeNull();
    expect(normalizeProvider("")).toBeNull();
    expect(normalizeProvider("   ")).toBeNull();
  });

  it("passes an unknown host through lower-cased rather than guessing a key", () => {
    expect(normalizeProvider("gitlab.example.com")).toBe("gitlab.example.com");
  });
});

describe("ChangeRequestBadge render (upstream f8f9c97f)", () => {
  // Server-render both sizes like the changes-surface smoke: the node suite
  // cannot dispatch DOM events, and the badge's own behavior (glyph, number,
  // tooltip copy) is fully visible in the static markup.
  it("always renders the PR glyph and the bare number; the tooltip keeps PR #N", () => {
    for (const size of ["sidebar", "composer"] as const) {
      const html = renderToString(
        createElement(ChangeRequestBadge, { summary: summary("open"), size }),
      );
      // The glyph rides along in every state and surface, not just the
      // composer's (upstream f8f9c97f).
      expect(html).toContain("cr-badge-glyph");
      // The badge itself shows the bare number.
      expect(html).toContain(">90</span>");
      expect(html).not.toContain(">#90</span>");
      // The `#` survives only in the tooltip's "PR #N · State".
      expect(html).toContain("PR #90 · Open");
    }
  });

  it("maps the state tone onto the badge and tooltip line classes", () => {
    const html = renderToString(
      createElement(ChangeRequestBadge, { summary: summary("closed"), size: "sidebar" }),
    );
    expect(html).toContain("cr-badge-closed");
    expect(html).toContain("cr-tooltip-line-closed");
    expect(html).toContain("PR #90 · Closed");
  });
});
