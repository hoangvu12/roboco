import { describe, expect, it } from "vitest";
import { createElement } from "react";
import { renderToString } from "react-dom/server";
import { ChangesSurface, ChangesToolbar } from "../src/routes/changes-page";
import { ChangesSurfaceStore } from "../src/state/changes-surface";

/**
 * A render-path smoke for the Changes surface: the smoke ENGINE fixture has
 * no git checkout, so a Diffs tab cannot be minted in the browser harness
 * (the picker's git gate). Server-rendering the two trees exercises the
 * mount path — imports, hook wiring, the store binding — beyond what the
 * pure-logic suite covers.
 */
describe("Changes surface render smoke", () => {
  it("renders the toolbar with the scope chips and the trailing tools", () => {
    const html = renderToString(createElement(ChangesToolbar, { chatId: "c1", surfaceId: "d1" }));
    expect(html).toContain("Working tree");
    expect(html).toContain("Branch changes");
    expect(html).toContain("Latest turn");
    expect(html).toContain('id="changes-split"');
    expect(html).toContain('id="changes-wrap"');
    expect(html).toContain('id="changes-fold-all"');
  });

  it("renders the surface's no-engine gate without a session", () => {
    const html = renderToString(createElement(ChangesSurface, { chatId: "c1", surfaceId: "d1" }));
    expect(html).toContain("Pair an engine to view its changes.");
  });
});

/**
 * A commit-pinned tab (`Changes::for_commit`, ticket 27's click target): the
 * scope lands on `commit` with the pinned sha and never moves off it — there
 * is no scope chip to take it back.
 */
describe("commit-pinned Changes surface state", () => {
  it("pins the commit scope and ignores later scope switches", () => {
    const store = new ChangesSurfaceStore();
    store.pinCommit("c1", "d27", "896e31f0abcd");
    const pinned = store.snapshotFor("c1", "d27");
    expect(pinned.scope).toBe("commit");
    expect(pinned.commitSha).toBe("896e31f0abcd");

    store.setScope("c1", "d27", "branch");
    store.setScope("c1", "d27", "workingTree");
    const after = store.snapshotFor("c1", "d27");
    expect(after.scope).toBe("commit");
    expect(after.commitSha).toBe("896e31f0abcd");

    store.dispose("c1", "d27");
    expect(store.snapshotFor("c1", "d27").scope).toBe("workingTree");
  });
});

