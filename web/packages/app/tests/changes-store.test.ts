import { describe, expect, it } from "vitest";
import type { CheckoutDiff } from "@roboco/proto";
import { methods, RpcError, type WatchHandle } from "@roboco/engine-client";
import { ChangesStore, WATCH_RETRY_MS, type ChangesClient, type ChangesSnapshot } from "../src/state/changes-store";

/**
 * The Changes store against a scripted fake caller (the file-tree suite's
 * in-memory caller idiom — the app-level peer of the engine-client fake
 * server). The empty-state ticket's store contract: the watch's first frame
 * flips `watchLoaded` (the classification's "the engine has enumerated its
 * checkouts" input), frames resolve per chat through the normalized cwd
 * fallback, and a stream end degrades to the banner with the content kept.
 */

/** A working-tree frame, as the engine's canonicalize emits it on Windows. */
function frame(fields: Partial<CheckoutDiff> = {}): CheckoutDiff {
  return {
    checkoutId: "co-1",
    deviceId: "dev-a",
    cwd: "\\\\?\\C:\\Users\\x\\repo",
    patch: "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1,1 +1,2 @@\n-x\n+x\n+y\n",
    files: [],
    additions: 1,
    deletions: 0,
    truncated: false,
    checksum: "sum-1",
    updatedAt: "2026-01-01T00:00:00Z",
    ...fields,
  };
}

interface ScriptedWatch {
  readonly method: string;
  onItem(item: unknown, context: { generation: number }): void;
  onEnd(error: RpcError | undefined): void;
}

/** The scripted caller: records calls, hands the test its live watches. */
function fakeCaller() {
  const watches: ScriptedWatch[] = [];
  const calls: { method: string; params?: unknown }[] = [];
  const branches: string[] = [];
  const caller: ChangesClient = {
    call<T>(method: string, params?: unknown): Promise<T> {
      calls.push({ method, params });
      if (method === methods.LIST_BRANCHES) {
        return Promise.resolve([...branches] as T);
      }
      return Promise.resolve({} as T);
    },
    watch<T>(
      method: string,
      _params: unknown,
      handlers: {
        onItem: (item: T, context: { generation: number }) => void;
        onEnd?: (error: RpcError | undefined) => void;
      },
    ): WatchHandle {
      const watch: ScriptedWatch = {
        method,
        onItem: (item, context) => handlers.onItem(item as T, context),
        onEnd: (error) => handlers.onEnd?.(error),
      };
      watches.push(watch);
      return { method, cancel: () => {} };
    },
  };
  return { caller, watches, calls, branches };
}

/** Deliver a watch frame as the engine would — generation 1, first dial. */
function deliver(watch: ScriptedWatch, item: CheckoutDiff | CheckoutDiff[]): void {
  watch.onItem(item, { generation: 1 });
}

/** The store over a plain-folder chat: no checkout id, forward-slashed cwd. */
function storeOver(caller: ChangesClient): ChangesStore {
  return new ChangesStore(caller, {
    checkoutId: null,
    deviceId: "dev-a",
    cwd: "C:/Users/x/repo",
    chatId: "chat-1",
  });
}

function phasesOf(snapshot: ChangesSnapshot): { phase: string; watchLoaded: boolean } {
  return { phase: snapshot.phase, watchLoaded: snapshot.watchLoaded };
}

describe("ChangesStore against a scripted caller", () => {
  it("stays undelivered until the watch's first frame — the genuine-loading input", () => {
    const { caller } = fakeCaller();
    const store = storeOver(caller);
    // The pane's mount state: nothing knowable yet, so the classification
    // keeps the spinner (its `watchLoaded: false` arm).
    expect(phasesOf(store.getSnapshot())).toEqual({ phase: "preparing", watchLoaded: false });
    expect(store.getSnapshot().resolvedForChat).toBeNull();
    expect(store.getSnapshot().error).toBeNull();
    store.dispose();
  });

  it("flips watchLoaded on the engine's initial enumeration without resolving a foreign frame", () => {
    const { caller, watches } = fakeCaller();
    const store = storeOver(caller);
    deliver(watches[0]!, [frame({ cwd: "\\\\?\\C:\\other\\\\place" })]);
    // The engine enumerated its checkouts (the `[]`-equivalent — a frame
    // for some OTHER folder): watchLoaded is the classification's "the
    // engine has spoken" input, and nothing resolves for this chat.
    const snapshot = store.getSnapshot();
    expect(snapshot.watchLoaded).toBe(true);
    expect(snapshot.resolvedForChat).toBeNull();
    expect(snapshot.phase).toBe("preparing");
    store.dispose();
  });

  it("resolves a verbatim-cwd frame for the checkout-id-less chat and lists it", () => {
    const { caller, watches } = fakeCaller();
    const store = storeOver(caller);
    // The chat row lacks a checkout id; the frame carries the canonical
    // `\\?\`-verbatim cwd while the chat target carries the plain
    // forward-slashed path — the normalized fallback (resolveDiff) matches.
    deliver(watches[0]!, [frame()]);
    const snapshot = store.getSnapshot();
    expect(snapshot.watchLoaded).toBe(true);
    expect(snapshot.resolvedForChat?.checkoutId).toBe("co-1");
    expect(snapshot.phase).toBe("list");
    store.dispose();
  });

  it("cleans when the resolved frame's patch is empty", () => {
    const { caller, watches } = fakeCaller();
    const store = storeOver(caller);
    deliver(watches[0]!, [frame({ patch: "", additions: 0, deletions: 0, checksum: "sum-clean" })]);
    const snapshot = store.getSnapshot();
    expect(snapshot.resolvedForChat).not.toBeNull();
    expect(snapshot.phase).toBe("clean");
    store.dispose();
  });

  it("upserts single frames in place, not just list frames", () => {
    const { caller, watches } = fakeCaller();
    const store = storeOver(caller);
    deliver(watches[0]!, [frame()]);
    // The engine's per-checkout push: one frame upserts the working set.
    deliver(watches[0]!, frame({ checksum: "sum-2", patch: "diff --git a/y b/y\n" }));
    const snapshot = store.getSnapshot();
    expect(snapshot.working).toHaveLength(1);
    expect(snapshot.resolvedForChat?.checksum).toBe("sum-2");
    expect(snapshot.phase).toBe("list");
    store.dispose();
  });

  it("ends the stream into the retry banner while the content stays", () => {
    const { caller, watches } = fakeCaller();
    const store = storeOver(caller);
    deliver(watches[0]!, [frame()]);
    watches[0]!.onEnd(undefined);
    const snapshot = store.getSnapshot();
    expect(snapshot.error).toBe("Diff stream interrupted — retrying");
    // Content stays visible underneath the banner (spawn_watch's loop).
    expect(snapshot.resolvedForChat?.checkoutId).toBe("co-1");
    expect(snapshot.phase).toBe("list");
    store.dispose();

    // The failed-subscribe arm carries the engine's message.
    const second = fakeCaller();
    const storeTwo = storeOver(second.caller);
    second.watches[0]!.onEnd(new RpcError("transport", "socket closed"));
    expect(storeTwo.getSnapshot().error).toBe("Diff watch unavailable: socket closed");
    storeTwo.dispose();
  });

  it("resubscribe drops the watch state back to undelivered", () => {
    const { caller, watches } = fakeCaller();
    const store = storeOver(caller);
    deliver(watches[0]!, [frame()]);
    expect(store.getSnapshot().watchLoaded).toBe(true);
    store.resubscribe();
    // A session swap starts a fresh generation: the first item must land
    // again before anything is knowable — the loading classification arm.
    expect(phasesOf(store.getSnapshot())).toEqual({ phase: "preparing", watchLoaded: false });
    expect(store.getSnapshot().resolvedForChat).toBeNull();
    store.dispose();
  });

  it("exposes the flat watch retry delay the banner's loop promises", () => {
    // `spawn_watch`'s flat 2s retry (changes.rs:1822) — pinned so a silent
    // change to the pacing is a deliberate one.
    expect(WATCH_RETRY_MS).toBe(2000);
  });
});
