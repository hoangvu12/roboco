import { describe, expect, it } from "vitest";
import type { ChangeRequestState, ChangeRequestSummary } from "@roboco/proto";
import {
  badgeModel,
  changeRequestCreateUrl,
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
  it("renders the Open / Merged / Closed labels and tones", () => {
    const cases: Array<[ChangeRequestState, string, "open" | "merged" | "closed"]> = [
      ["open", "Open", "open"],
      ["merged", "Merged", "merged"],
      ["closed", "Closed", "closed"],
    ];
    for (const [state, label, tone] of cases) {
      const model = badgeModel(summary(state));
      expect(model.number).toBe("#90");
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

describe("changeRequestCreateUrl", () => {
  it("builds a github compare URL when the provider is github", () => {
    expect(
      changeRequestCreateUrl("github", "main", "feature/pr", "acme/roboco"),
    ).toBe("https://github.com/acme/roboco/compare/main...feature%2Fpr?expand=1");
  });

  it("handles absolute Windows-style cwd paths by normalising", () => {
    expect(
      changeRequestCreateUrl("gitlab", "main", "feature/pr", "C:\\repo\\acme\\roboco"),
    ).toBe("https://gitlab.com/C:/repo/acme/roboco/-/merge_requests/new?merge_request[source_branch]=feature%2Fpr&merge_request[target_branch]=main");
  });

  it("strips a trailing .git suffix", () => {
    expect(
      changeRequestCreateUrl("bitbucket", "main", "feature/pr", "acme/roboco.git"),
    ).toBe("https://bitbucket.org/acme/roboco/pull-requests/new?source=feature%2Fpr&dest=main");
  });

  it("returns null when ref is missing", () => {
    expect(changeRequestCreateUrl("github", "", "feature/pr", "acme/roboco")).toBeNull();
    expect(changeRequestCreateUrl("github", "main", "  ", "acme/roboco")).toBeNull();
  });

  it("returns null for unknown providers", () => {
    expect(changeRequestCreateUrl("bogus", "main", "feature/pr", "acme/roboco")).toBeNull();
  });
});