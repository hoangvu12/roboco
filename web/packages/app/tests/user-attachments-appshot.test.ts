// @vitest-environment jsdom

/**
 * The user-bubble's Appshot card (parity spec: cross-device rendering, the
 * display half of crates/ui/src/appshots.rs). A desktop-sent message's
 * attachment strip presents the appshot with its label — "{app} · Appshot"
 * plus the window title — instead of a bare filename thumbnail. The REAL
 * UserAttachments strip mounts over the REAL attachment cache (seeded, like
 * the composer seeds a just-sent image); no network, no engine.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import type { EngineClient } from "@roboco/engine-client";
import { UserAttachments } from "../src/components/attachments/user-attachments";
import { appshotPresentationTitle, type AppshotPresentation, type UserImageAttachment } from "../src/lib/attachments";
import { seedAttachment } from "../src/state/attachment-cache";

/** A 1×1 transparent PNG — a loadable image for the seeded cache. */
const PNG = new Uint8Array([
  0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
  0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4,
  0x89, 0x00, 0x00, 0x00, 0x0a, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63, 0x00, 0x01, 0x00, 0x00,
  0x05, 0x00, 0x01, 0x0d, 0x0a, 0x2d, 0xb4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae,
  0x42, 0x60, 0x82,
]);

const DEVICE = "dev-1";

const noClient = { call: async () => { throw new Error("no engine in this test"); } } as unknown as EngineClient;

const appshot: AppshotPresentation = {
  appName: "Safari & Notes",
  windowTitle: 'A "window"',
  bundleIdentifier: null,
};

function attachment(fields: Partial<UserImageAttachment> & { readonly path: string }): UserImageAttachment {
  return {
    id: `0:${fields.path}`,
    name: "appshot.png",
    appshot: null,
    ...fields,
  };
}

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

afterEach(() => {
  document.body.replaceChildren();
});

function mountStrip(attachments: readonly UserImageAttachment[]): HTMLElement {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root: Root = createRoot(container);
  act(() => {
    root.render(
      createElement(UserAttachments, { client: noClient, deviceId: DEVICE, attachments }),
    );
  });
  return container;
}

describe("the Appshot attachment card (transcript.rs:6159-6290)", () => {
  it("an appshot-bearing attachment renders the labeled card, not the bare thumb (story 20)", () => {
    seedAttachment(DEVICE, "/a/appshot.png", { name: "appshot.png", mime: "image/png", bytes: PNG });
    seedAttachment(DEVICE, "/b/plain.png", { name: "plain.png", mime: "image/png", bytes: PNG });
    const container = mountStrip([
      attachment({ path: "/a/appshot.png", appshot }),
      attachment({ path: "/b/plain.png" }),
    ]);

    // The appshot: a wide labeled card — the app line, the title, and the
    // preview aria-label the desktop writes.
    const card = container.querySelector<HTMLElement>(".user-attachments-appshot");
    expect(card).not.toBeNull();
    expect(card!.textContent).toContain("Safari & Notes · Appshot");
    expect(card!.textContent).toContain('A "window"');
    expect(card!.getAttribute("aria-label")).toContain("Appshot");
    expect(card!.querySelector("img")).not.toBeNull();
    // The ordinary image beside it stays the plain 112×80 thumbnail.
    expect(container.querySelectorAll(".user-attachments-thumb")).toHaveLength(1);
  });

  it("an unavailable appshot says so — never a silent blank", async () => {
    const container = mountStrip([attachment({ path: "/gone.png", appshot })]);
    // The load fails through the stub client: settle the rejection.
    await act(async () => {});
    const card = container.querySelector<HTMLElement>(".user-attachments-appshot");
    expect(card).not.toBeNull();
    expect(card!.textContent).toContain("Appshot unavailable");
    // The label still presents the app it came from.
    expect(card!.textContent).toContain("Safari & Notes · Appshot");
    expect(appshotPresentationTitle(appshot)).toBe('A "window"');
  });
});
