import { afterEach, describe, expect, it, vi } from "vitest";
import {
  beginAttachmentLoad,
  getAttachmentSnapshot,
  loadAttachment,
  seedAttachment,
  storeAttachmentError,
  subscribeAttachment,
  __resetAttachmentCacheForTests,
} from "../src/state/attachment-cache";

afterEach(() => {
  __resetAttachmentCacheForTests();
});

const FAKE_IMAGE = { name: "a.png", mime: "image/png", bytes: new Uint8Array([1, 2, 3]) };

describe("attachment-cache snapshot lifecycle", () => {
  it("begins in the loading state until a load settles", () => {
    const snap = getAttachmentSnapshot("dev-1", "/host/a.png");
    expect(snap.state).toBe("loading");
    expect(snap.image).toBeNull();
  });

  it("seeds a loaded entry and observes it via subscription", () => {
    const calls: number[] = [];
    const sub = subscribeAttachment("dev-1", "/host/a.png", () => calls.push(1));
    expect(getAttachmentSnapshot("dev-1", "/host/a.png").state).toBe("loading");

    seedAttachment("dev-1", "/host/a.png", FAKE_IMAGE);
    expect(calls).toEqual([1]);
    const snap = getAttachmentSnapshot("dev-1", "/host/a.png");
    expect(snap.state).toBe("loaded");
    expect(snap.image).toEqual(FAKE_IMAGE);
    sub();
  });

  it("hides a load that was already claimed", () => {
    expect(beginAttachmentLoad("dev-1", "/host/a.png")).toBe(true);
    expect(beginAttachmentLoad("dev-1", "/host/a.png")).toBe(false);
  });

  it("transitions to error with a 2-second retry hint", () => {
    // Pretend a load was kicked.
    expect(beginAttachmentLoad("dev-1", "/host/a.png")).toBe(true);
    storeAttachmentError("dev-1", "/host/a.png");
    const snap = getAttachmentSnapshot("dev-1", "/host/a.png");
    expect(snap.state).toBe("error");
    expect(snap.retryIn).toBeGreaterThan(0);
    expect(snap.retryIn).toBeLessThanOrEqual(2000);
  });

  it("the 2s→15s retry ladder is bounded", async () => {
    expect(beginAttachmentLoad("dev-1", "/host/a.png")).toBe(true);
    // 1 error → ~2s wait
    storeAttachmentError("dev-1", "/host/a.png");
    const first = getAttachmentSnapshot("dev-1", "/host/a.png");
    expect(first.retryIn).toBeLessThanOrEqual(2000);

    // A later error after the wait elapsed ~ 4s, capped at 15s
    storeAttachmentError("dev-1", "/host/a.png");
    storeAttachmentError("dev-1", "/host/a.png");
    const later = getAttachmentSnapshot("dev-1", "/host/a.png");
    expect(later.retryIn).toBeLessThanOrEqual(15_000);
  });

  it("loadAttachment resolves with the read result when the call succeeds", async () => {
    // Build a tiny client mock that returns a single chunk with done=true.
    const client = {
      async call(method: string, params: { offset?: number }): Promise<unknown> {
        if (method === "ReadAttachmentChunk") {
          return {
            name: "shot.png",
            mimeType: "image/png",
            data: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
            nextOffset: params.offset ?? 0,
            done: true,
          };
        }
        throw new Error(`unexpected method: ${method}`);
      },
    };

    await loadAttachment(client, "dev-1", "/host/shot.png");
    const snap = getAttachmentSnapshot("dev-1", "/host/shot.png");
    expect(snap.state).toBe("loaded");
    expect(snap.image?.name).toBe("shot.png");
    expect(snap.image?.bytes.byteLength).toBeGreaterThan(0);
  });

  it("loadAttachment marks the entry as errored when the call throws", async () => {
    const client = {
      async call(): Promise<unknown> {
        throw new Error("rpc failure");
      },
    };
    await loadAttachment(client, "dev-1", "/host/missing.png");
    const snap = getAttachmentSnapshot("dev-1", "/host/missing.png");
    expect(snap.state).toBe("error");
  });

  it("loadAttachment returns silently when the load was already claimed", async () => {
    const callMock = vi.fn(async (): Promise<unknown> => ({}));
    const client = { call: callMock };
    expect(beginAttachmentLoad("dev-1", "/host/a.png")).toBe(true);
    await loadAttachment(client, "dev-1", "/host/a.png");
    expect(callMock).not.toHaveBeenCalled();
  });
});

describe("attachment-cache unsubscribe", () => {
  it("drops the listener when the unsubscribe function is called", () => {
    let count = 0;
    const sub = subscribeAttachment("dev-1", "/host/x.png", () => {
      count += 1;
    });
    seedAttachment("dev-1", "/host/x.png", FAKE_IMAGE);
    expect(count).toBe(1);
    sub();
    seedAttachment("dev-1", "/host/x.png", FAKE_IMAGE);
    expect(count).toBe(1);
  });
});