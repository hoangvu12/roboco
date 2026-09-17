import { describe, expect, it, vi } from "vitest";
import type { SessionMessageEntry, TranscriptUpdate } from "@roboco/proto";
import {
  EchoStore,
  TranscriptStore,
  UNDELIVERED_GRACE_MS,
  pendingSendStatus,
  type PendingSend,
  type TranscriptClient,
} from "../src/state/transcript-store";
import { sendRun, type DraftConfig } from "../src/lib/composer-actions";

/**
 * Ports of the desktop's echo / pending-send tests (`crates/ui/src/state.rs`,
 * research 14-state-behavior §4.3), plus the web-only "one id per send"
 * invariant that makes the ack possible at all.
 */

const CHAT = "chat-1";

function pending(overrides: Partial<PendingSend> = {}): PendingSend {
  return {
    messageId: "msg-1",
    chatId: CHAT,
    startedAtMs: 1_000,
    text: "hello",
    attachmentPaths: [],
    ...overrides,
  };
}

function userEntry(id: string): SessionMessageEntry {
  return {
    id,
    role: "user",
    parts: [{ kind: "text", id: `${id}#0`, text: "hello" }],
    createdAt: 1_000,
    deviceId: "device-1",
  };
}

describe("echo overlay", () => {
  it("echoes_show_until_doc_frame_confirms", () => {
    const store = new EchoStore();
    store.pushEcho(pending());
    expect(store.forChat(CHAT)).toHaveLength(1);

    // A frame that does NOT name the id leaves the echo alone.
    store.ackFromFrame(CHAT, ["someone-else"]);
    expect(store.forChat(CHAT)).toHaveLength(1);

    store.ackFromFrame(CHAT, ["msg-1"]);
    expect(store.forChat(CHAT)).toHaveLength(0);
  });

  it("send_pending_overlays_working_until_the_grace_window", () => {
    const send = pending({ startedAtMs: 0 });
    expect(pendingSendStatus(send, 0)).toBe("pending");
    expect(pendingSendStatus(send, UNDELIVERED_GRACE_MS)).toBe("pending");
    expect(pendingSendStatus(send, UNDELIVERED_GRACE_MS + 1)).toBe("undelivered");
    // The AND-in point for a future `chat_delivery_degraded`: degraded
    // delivery keeps the send quiet however long it has been waiting.
    expect(pendingSendStatus(send, UNDELIVERED_GRACE_MS + 1, true)).toBe("pending");
  });

  it("send_pending_acked_when_the_host_writes_the_message_back", () => {
    const store = new EchoStore();
    // Well past the grace window — acking is purely an id match, and timing
    // plays no part in it.
    store.pushEcho(pending({ startedAtMs: Date.now() - UNDELIVERED_GRACE_MS * 10 }));
    store.ackFromFrame(CHAT, new Set(["msg-1"]));
    expect(store.forChat(CHAT)).toHaveLength(0);
  });

  it("send_failure_cleanup_only_ends_its_own_overlay", () => {
    const store = new EchoStore();
    store.pushEcho(pending({ messageId: "msg-1" }));
    store.pushEcho(pending({ messageId: "msg-2", text: "second" }));
    expect(store.forChat(CHAT)).toHaveLength(2);

    store.removeEcho("msg-1");
    const left = store.forChat(CHAT);
    expect(left).toHaveLength(1);
    expect(left[0]?.messageId).toBe("msg-2");
    expect(left[0]?.text).toBe("second");
  });

  it("retry_restarts_the_grace_window_with_a_new_id", () => {
    const store = new EchoStore();
    store.pushEcho(pending({ startedAtMs: 0 }));
    expect(pendingSendStatus(store.forChat(CHAT)[0]!, UNDELIVERED_GRACE_MS + 1)).toBe("undelivered");

    const next = store.retry("msg-1", { mintMessageId: () => "msg-2", nowMs: 500_000 });
    expect(next).not.toBeNull();
    expect(next!.messageId).toBe("msg-2");
    expect(next!.startedAtMs).toBe(500_000);
    expect(next!.text).toBe("hello");
    // Swapped IN PLACE: one bubble before, one bubble after.
    expect(store.forChat(CHAT)).toHaveLength(1);
    expect(store.get("msg-1")).toBeNull();
    // And the clock restarted: it is quiet again at the same wall time.
    expect(pendingSendStatus(next!, 500_000 + 1_000)).toBe("pending");
  });

  it("retrying an already-acked send is a no-op", () => {
    const store = new EchoStore();
    expect(store.retry("gone")).toBeNull();
  });

  it("a push of an id already on the overlay does not double the bubble", () => {
    const store = new EchoStore();
    store.pushEcho(pending());
    store.pushEcho(pending({ text: "different text, same id" }));
    expect(store.forChat(CHAT)).toHaveLength(1);
    expect(store.forChat(CHAT)[0]?.text).toBe("hello");
  });

  it("acks are chat-scoped: a frame on one chat never clears another's echo", () => {
    const store = new EchoStore();
    store.pushEcho(pending({ messageId: "msg-1", chatId: "chat-a" }));
    store.pushEcho(pending({ messageId: "msg-1", chatId: "chat-b" }));
    store.ackFromFrame("chat-a", ["msg-1"]);
    expect(store.forChat("chat-a")).toHaveLength(0);
    expect(store.forChat("chat-b")).toHaveLength(1);
  });
});

describe("TranscriptStore acks the overlay from the stream", () => {
  function fakeClient(): {
    client: TranscriptClient;
    emit: (update: TranscriptUpdate) => void;
  } {
    let onItem: ((item: TranscriptUpdate, ctx: { generation: number }) => void) | null = null;
    return {
      client: {
        watch<T>(
          _method: string,
          _params: unknown,
          handlers: { onItem: (item: T, ctx: { generation: number }) => void },
        ) {
          onItem = handlers.onItem as (item: TranscriptUpdate, ctx: { generation: number }) => void;
          return { cancel: () => {} };
        },
      } as TranscriptClient,
      emit: (update) => onItem?.(update, { generation: 1 }),
    };
  }

  it("a reset frame carrying the id clears the echo", () => {
    const echoes = new EchoStore();
    echoes.pushEcho(pending());
    const { client, emit } = fakeClient();
    const store = new TranscriptStore(client, CHAT, { echoes });

    emit({ contextUsage: null, reset: [userEntry("msg-1")] });
    expect(echoes.forChat(CHAT)).toHaveLength(0);
    expect(store.getSnapshot().entries).toHaveLength(1);
    store.dispose();
  });

  it("a delta upsert carrying the id clears the echo", () => {
    const echoes = new EchoStore();
    echoes.pushEcho(pending());
    const { client, emit } = fakeClient();
    const store = new TranscriptStore(client, CHAT, { echoes });

    emit({ contextUsage: null, reset: [] });
    emit({
      contextUsage: null,
      upsert: [{ after: null, entry: userEntry("msg-1") }],
      append: [],
      remove: [],
      count: 1,
    });
    expect(echoes.forChat(CHAT)).toHaveLength(0);
    store.dispose();
  });
});

describe("sendRun mints exactly one message id", () => {
  const DRAFT: DraftConfig = {
    harness: "claude-code",
    model: "sonnet",
    reasoning: "medium",
    sandbox: "workspace-write",
    modelOptions: {},
  };

  it("the command envelope and the SendResult carry the same id", async () => {
    const mint = vi.fn(() => "msg-1");
    const calls: { method: string; params: unknown }[] = [];
    const caller = {
      call: async <T,>(method: string, params?: unknown): Promise<T> => {
        calls.push({ method, params });
        return { commandId: "cmd-1" } as T;
      },
    };

    const result = await sendRun(caller, CHAT, DRAFT, "hi", "/tmp/proj", {
      currentConfig: null,
      mintMessageId: mint,
    });

    // ONE mint per send — the bug this ticket fixes called it three times.
    expect(mint).toHaveBeenCalledTimes(1);
    expect(result.messageId).toBe("msg-1");
    const queued = calls.find((entry) => entry.method.toLowerCase().includes("queue"));
    expect(queued).toBeDefined();
    const command = (queued!.params as { command: { messageId: string } }).command;
    expect(command.messageId).toBe("msg-1");
  });
});
