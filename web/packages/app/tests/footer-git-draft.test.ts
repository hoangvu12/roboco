// @vitest-environment jsdom

/**
 * wpn-02 — the composer footer's draft git state, at its two seams:
 *
 * - `applyRefPick` — the `pick_ref` recording port (pickers.rs:1588-1610):
 *   reusing a ref's existing worktree records the branch AND flips the
 *   checkout kind to Local ("Current worktree", :1599-1604); a plain ref
 *   under NewWorktree and the already-current ref record the branch only
 *   (the chip keeps reading "New worktree" / the bare name).
 * - `applyCheckoutPick` — the `pick_checkout` drop rule (pickers.rs:
 *   1359-1373): back to Local with a non-current plain ref picked drops
 *   the pick; any other kind change keeps it.
 * - `useDraftGitState` — the space/device invalidation (pickers.rs:700-737)
 *   collapsed onto one owner key: a key change resets the branch pick, the
 *   checkout kind, and the loaded refs (the desktop's `refs = Idle` +
 *   `refs_space = None`), while a re-render on the SAME key keeps them.
 *
 * The hook half mounts through the jsdom idiom of picker-card-phone.test.ts
 * (act + createRoot, no JSX).
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import type { RepoRef } from "@roboco/proto";
import {
  applyCheckoutPick,
  applyRefPick,
  useDraftGitState,
  type DraftGitState,
} from "../src/lib/footer-git-draft";

// ── Fixtures ───────────────────────────────────────────────────────────────

/** A plain (non-current, non-worktree) ref. */
const PLAIN: RepoRef = { name: "feat/theme", current: false, worktreePath: null };
/** The repo's current branch. */
const CURRENT: RepoRef = { name: "main", current: true, worktreePath: null };
/** A ref materialized as a linked worktree ("Current worktree"). */
const WORKTREE: RepoRef = {
  name: "wt/search",
  current: false,
  worktreePath: "/repo/.worktrees/search",
};

const REFS: readonly RepoRef[] = [PLAIN, CURRENT, WORKTREE];

function state(overrides: Partial<DraftGitState> = {}): DraftGitState {
  return { branch: null, checkout: "local", refs: [], ...overrides };
}

// ── applyRefPick — pick_ref's recording half ──────────────────────────────

describe("applyRefPick (pick_ref, pickers.rs:1588-1610)", () => {
  it("reusing a worktree row records the branch and flips the checkout kind to local", () => {
    const next = applyRefPick(WORKTREE, state({ checkout: "newWorktree", refs: REFS }));
    // The chip pair reads "Current worktree" + the bare name (the desktop's
    // `config.checkout = CheckoutKind::Local`, :1599-1604).
    expect(next.branch).toBe("wt/search");
    expect(next.checkout).toBe("local");
    expect(next.refs).toBe(REFS);
  });

  it("a plain ref under new-worktree records the branch and keeps the kind", () => {
    const next = applyRefPick(PLAIN, state({ checkout: "newWorktree", refs: REFS }));
    expect(next.branch).toBe("feat/theme");
    expect(next.checkout).toBe("newWorktree");
  });

  it("the current branch row records the name only", () => {
    const next = applyRefPick(CURRENT, state({ checkout: "local", refs: REFS }));
    expect(next.branch).toBe("main");
    expect(next.checkout).toBe("local");
  });
});

// ── applyCheckoutPick — pick_checkout's drop rule ─────────────────────────

describe("applyCheckoutPick (pick_checkout, pickers.rs:1359-1373)", () => {
  it("back to local with a non-current plain ref picked drops the pick", () => {
    const next = applyCheckoutPick("local", state({ branch: "feat/theme", checkout: "newWorktree", refs: REFS }));
    expect(next.branch).toBeNull();
    expect(next.checkout).toBe("local");
  });

  it("any other kind change keeps the pick", () => {
    const next = applyCheckoutPick("newWorktree", state({ branch: "main", checkout: "local", refs: REFS }));
    expect(next.branch).toBe("main");
    expect(next.checkout).toBe("newWorktree");
  });
});

// ── useDraftGitState — the space/device invalidation ──────────────────────

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

afterEach(() => {
  document.body.replaceChildren();
});

interface DraftHarness {
  read(): [string, string, string];
  pick(row: RepoRef): void;
  rerender(ownerKey: string): void;
  unmount(): void;
}

/** One mounted `useDraftGitState`, read through its rendered projection. */
function mountDraft(ownerKey: string): DraftHarness {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root: Root = createRoot(container);
  let row: RepoRef = WORKTREE;

  function Host({ owner }: { readonly owner: string }) {
    const [draft, setDraft] = useDraftGitState(owner);
    return createElement(
      "div",
      null,
      createElement(
        "button",
        {
          type: "button",
          id: "draft-pick",
          onClick: () => setDraft((current) => applyRefPick(row, current)),
        },
        "pick",
      ),
      createElement(
        "output",
        { id: "draft-state" },
        JSON.stringify([draft.branch ?? "-", draft.checkout, String(draft.refs.length)]),
      ),
    );
  }

  act(() => {
    root.render(createElement(Host, { owner: ownerKey }));
  });

  return {
    read() {
      const out = document.querySelector<HTMLElement>("#draft-state");
      return JSON.parse(out?.textContent ?? "null") as [string, string, string];
    },
    pick(next: RepoRef) {
      row = next;
      act(() => {
        document.querySelector<HTMLElement>("#draft-pick")!.click();
      });
    },
    rerender(owner: string) {
      act(() => {
        root.render(createElement(Host, { owner }));
      });
    },
    unmount() {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
}

describe("useDraftGitState (the pickers.rs:700-737 invalidation)", () => {
  it("starts at the empty draft — no branch, local checkout, no refs", () => {
    const handle = mountDraft("space-a");
    expect(handle.read()).toEqual(["-", "local", "0"]);
    handle.unmount();
  });

  it("a key change resets the branch pick, the checkout kind, and the refs", () => {
    const handle = mountDraft("space-a");
    handle.pick(WORKTREE);
    expect(handle.read()).toEqual(["wt/search", "local", "0"]);
    // Dirty the checkout kind and refs too — every field must fall back.
    handle.rerender("space-a");
    handle.pick(PLAIN);
    handle.rerender("space-b");
    expect(handle.read()).toEqual(["-", "local", "0"]);
    handle.unmount();
  });

  it("a re-render on the same key keeps the pick", () => {
    const handle = mountDraft("space-a");
    handle.pick(WORKTREE);
    handle.rerender("space-a");
    expect(handle.read()).toEqual(["wt/search", "local", "0"]);
    handle.unmount();
  });
});
