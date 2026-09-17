import { describe, expect, it } from "vitest";
import { createElement } from "react";
import { renderToString } from "react-dom/server";
import { ChangesSurface, ChangesToolbar } from "../src/routes/changes-page";

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
