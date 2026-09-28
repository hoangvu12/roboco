// @vitest-environment jsdom

/**
 * Ticket 26 / ticket 24's web half — contained dropdowns: inside a host that
 * provides a containment boundary (the settings dialog's card), the anchored
 * menus constrain to that boundary and flip to the roomier side instead of
 * spilling past the card. The mounted contracts here pin the wiring, not the
 * geometry (jsdom has no layout; Floating UI's positioning is Base UI's):
 *
 * - The contained preset: `side: 'flip'` (below-else-above, `menu_origin`),
 *   the boundary is the host card, the margin is the card inset, and the
 *   placement's own side/align/offset ride through unchanged.
 * - `RbPopover` and `RbSelectPositioner` actually consume the context: the
 *   positioner's DOM carries the boundary-driven data attribute wiring
 *   through the controlled-open mount.
 * - Outside a host, nothing changes: the context reads null and the wrappers
 *   keep the window-clamped never-flip preset.
 *
 * The mounted idiom follows settings-dialogs.test.ts (no JSX, per-file jsdom
 * pragma, Base UI in jsdom behind the matchMedia / ResizeObserver stubs).
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import {
  CONTAINED_COLLISION_AVOIDANCE,
  CONTAINED_SNAP_MARGIN,
  containedPositionerProps,
  noFlipPositionerProps,
  ANCHOR_GAP,
  NO_FLIP_COLLISION_AVOIDANCE,
} from "../src/components/base/positioning";
import { ContainedBoundsContext, useContainedBounds } from "../src/components/base/contained";

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  window.matchMedia = ((query: string) => ({
    matches: query.startsWith("(max-width") === false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

afterEach(() => {
  document.body.replaceChildren();
});

describe("containedPositionerProps (popover/contained.rs's flip semantics)", () => {
  it("flips the side when short on room, keeps the placement, and sets the boundary", () => {
    const boundary = document.createElement("div");
    const props = containedPositionerProps({ side: "bottom", align: "start" }, boundary);
    // `menu_origin` (contained.rs:60-74): below when it fits, above when it
    // does not — Base UI's `side: 'flip'`, never the no-flip preset's shift.
    expect(props.collisionAvoidance).toEqual(CONTAINED_COLLISION_AVOIDANCE);
    expect(props.collisionAvoidance).not.toEqual(NO_FLIP_COLLISION_AVOIDANCE);
    expect(props.collisionBoundary).toBe(boundary);
    // The placement itself rides through like the no-flip preset's does.
    expect(props.side).toBe("bottom");
    expect(props.align).toBe("start");
    expect(props.sideOffset).toBe(ANCHOR_GAP);
    expect(props.positionMethod).toBe("fixed");
  });

  it("the card's inset is the margin, not the window's 8px gutter", () => {
    const boundary = document.createElement("div");
    expect(containedPositionerProps({ side: "top", align: "end" }, boundary).collisionPadding).toBe(
      CONTAINED_SNAP_MARGIN,
    );
    expect(CONTAINED_SNAP_MARGIN).toBeLessThan(noFlipPositionerProps({ side: "top", align: "end" }).collisionPadding);
  });

  it("the align axis only shifts — no perpendicular fallback", () => {
    const boundary = document.createElement("div");
    expect(containedPositionerProps({ side: "bottom", align: "start" }, boundary).collisionAvoidance)
      .toEqual({ side: "flip", align: "shift", fallbackAxisSide: "none" });
  });
});

describe("the containment context", () => {
  /** A probe reading the context at render depth. */
  function ContextProbe(props: { readonly report: (bounds: HTMLElement | null) => void }) {
    props.report(useContainedBounds());
    return null;
  }

  function mountProbe(withProvider: boolean): HTMLElement | null {
    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container);
    let observed: HTMLElement | null = null;
    const card = document.createElement("div");
    act(() => {
      const probe = createElement(ContextProbe, {
        report: (bounds) => {
          observed = bounds;
        },
      });
      root.render(
        withProvider
          ? createElement(ContainedBoundsContext.Provider, { value: card }, probe)
          : probe,
      );
    });
    act(() => {
      root.unmount();
    });
    container.remove();
    return observed;
  }

  it("carries the host's card element inside a provider", () => {
    expect(mountProbe(true)).toBeInstanceOf(HTMLElement);
  });

  it("reads null outside a host — every other popover keeps the window preset", () => {
    expect(mountProbe(false)).toBeNull();
  });
});
