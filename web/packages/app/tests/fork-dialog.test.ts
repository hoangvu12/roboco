import { afterEach, describe, expect, it } from "vitest";
import { encodeScopedId, type EngineClient } from "@roboco/engine-client";
import type { Chat, HarnessDescriptor } from "@roboco/proto";
import { forkConfirmOverrides, forkOfferedHarnesses, forkSpecFor } from "../src/lib/fork-dialog";
import { forkDialogStore } from "../src/state/fork-dialog";
import { clearPendingSideChat, pendingSideChat } from "../src/state/side-chats";
import { rightPaneStore } from "../src/state/right-pane";
import { resetSideChatCreate } from "../src/lib/side-chat-actions";

/**
 * The fork dialog (t3-orchestration-v2 tickets 21+22) — the pure offered
 * set and confirm-time override projection, plus the headless state
 * machine both web fork entry points open (the files panel's classic
 * button and the transcript's "Fork from here"): defaults resolve from the
 * source's own config, picks reset per the desktop's rules, and confirm
 * performs the fork through the FORK_SIDE_CHAT RPC, the pending-side-chat
 * seed, and the right-pane surface — only the picks that differ riding
 * the wire. Names port `shell/fork_dialog.rs`'s tests.
 */

const ENGINE = "ws://engine.local";
/** The engine's own device, in the fleet's scoped id form. */
const OWN_DEVICE = encodeScopedId(ENGINE, "dev-1");

function descriptor(id: HarnessDescriptor["id"], installed = true): HarnessDescriptor {
  return {
    id,
    name: id,
    supportsSteering: true,
    steeringMode: "step-boundary",
    reasoningLevels: [],
    installed,
    canInstall: false,
    enabled: true,
  };
}

const CATALOG = [descriptor("codex"), descriptor("pi"), descriptor("claude-code")];

function chatRow(fields: Partial<Chat> = {}): Chat {
  return {
    id: "source-1",
    deviceId: OWN_DEVICE,
    title: "The source chat",
    archived: false,
    cwd: "/repo",
    branch: "main",
    checkoutId: null,
    config: {
      harness: "codex",
      model: "gpt-6-codex",
      reasoning: null,
      sandbox: "workspace-write",
      modelOptions: {},
    },
    lastMessagePreview: null,
    lastMessageAt: null,
    createdAt: "2026-10-16T10:00:00Z",
    spaceId: "space-1",
    parentChatId: null,
    ...fields,
  };
}

/** A recording engine client with catalog/model replies and a fork reply. */
function fakeClient(forkReply: () => Chat | Promise<Chat>): {
  client: EngineClient;
  calls: { method: string; params: Record<string, unknown> }[];
} {
  const calls: { method: string; params: Record<string, unknown> }[] = [];
  const client = {
    engineInfo: { deviceId: "dev-1" },
    async call<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
      calls.push({ method, params });
      switch (method) {
        case "ListHarnesses":
          return CATALOG as T;
        case "ListModels":
          return [{ id: "glm-5.3", label: "GLM 5.3", reasoningLevels: [], options: [] }] as T;
        case "ForkSideChat":
          return (await forkReply()) as T;
        default:
          return {} as T;
      }
    },
  } as unknown as EngineClient;
  return { client, calls };
}

/** Flush the store's promise chains (the loads and the confirm). */
async function flush(times = 4): Promise<void> {
  for (let ix = 0; ix < times; ix++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
}

const CLEANUPS: string[] = [];

afterEach(() => {
  forkDialogStore.close();
  resetSideChatCreate();
  for (const id of CLEANUPS.splice(0)) {
    clearPendingSideChat(id);
  }
});

function rememberSeed(id: string): void {
  CLEANUPS.push(id);
}

// ── The pure halves (fork_dialog.rs's free functions) ────────────────────

describe("forkSpecFor", () => {
  it("hangs a top-level fork under the source, a side chat's own fork under its parent (the sibling rule)", () => {
    const { client } = fakeClient(() => chatRow({ id: "fork-9" }));
    const top = forkSpecFor(chatRow(), "pane-1", "a1", client, ENGINE);
    expect(top.parentId).toBe("source-1");
    expect(top.boundary).toBe("a1");
    expect(top.paneChatId).toBe("pane-1");
    expect(top.client).toBe(client);
    const side = chatRow({ id: "side-2", parentChatId: "source-1" });
    expect(forkSpecFor(side, "pane-1", null, client, ENGINE).parentId).toBe("source-1");
  });
});

describe("forkOfferedHarnesses", () => {
  it("keeps the source's harness pickable when the catalog carries it unoffered", () => {
    const catalog = [descriptor("codex"), descriptor("pi"), descriptor("claude-code", false)];
    expect(forkOfferedHarnesses(catalog, descriptor("codex").id).map((row) => row.id)).toEqual([
      "codex",
      "pi",
    ]);
    // The source's harness is not offered (its CLI is gone): it still rides
    // the list, behind the offered ones.
    expect(forkOfferedHarnesses(catalog, "claude-code").map((row) => row.id)).toEqual([
      "codex",
      "pi",
      "claude-code",
    ]);
    // Not in the catalog at all: nothing to append.
    expect(forkOfferedHarnesses(catalog, "grok").map((row) => row.id)).toEqual([
      "codex",
      "pi",
    ]);
    expect(forkOfferedHarnesses([], "pi")).toEqual([]);
    // No fallback: the plain offered set.
    expect(forkOfferedHarnesses(catalog, null).map((row) => row.id)).toEqual(["codex", "pi"]);
  });
});

describe("forkConfirmOverrides", () => {
  it("sends only the picks that differ — untouched defaults are the classic fork", () => {
    const source = chatRow();
    expect(forkConfirmOverrides(source, "codex", "gpt-6-codex")).toEqual({
      harness: null,
      model: null,
    });
    // Another harness: the harness rides, and a model equal to the
    // source's still rides with it (the new harness needs it spelled out).
    expect(forkConfirmOverrides(source, "pi", "gpt-6-codex")).toEqual({
      harness: "pi",
      model: "gpt-6-codex",
    });
    expect(forkConfirmOverrides(source, "pi", null)).toEqual({ harness: "pi", model: null });
    // Same harness, another model.
    expect(forkConfirmOverrides(source, "codex", "gpt-6-mini")).toEqual({
      harness: null,
      model: "gpt-6-mini",
    });
    // A blank model pick counts as none.
    expect(forkConfirmOverrides(source, "codex", "  ")).toEqual({ harness: null, model: null });
    // A config-less source: any resolved pick is an override.
    expect(forkConfirmOverrides(chatRow({ config: null }), "codex", null)).toEqual({
      harness: "codex",
      model: null,
    });
    // No pick at all: no overrides, whatever the source carries.
    expect(forkConfirmOverrides(source, null, null)).toEqual({ harness: null, model: null });
  });
});

// ── The state machine (Shell::fork_dialog / fork_chat) ───────────────────

describe("ForkDialogStore", () => {
  it("opens with the source's own harness and model, and loads its device's catalog", async () => {
    const { client, calls } = fakeClient(() => chatRow({ id: "fork-9" }));
    forkDialogStore.open({
      source: chatRow(),
      parentId: "source-1",
      boundary: null,
      paneChatId: "pane-a",
      client,
      engineBaseUrl: ENGINE,
    });
    let state = forkDialogStore.getSnapshot();
    expect(state.open).toBe(true);
    expect(state.harness).toBe("codex");
    expect(state.model).toBe("gpt-6-codex");
    await flush();
    state = forkDialogStore.getSnapshot();
    // The source's device IS the engine's own (both scoped): no routing
    // rides either load.
    expect(calls[0]).toEqual({ method: "ListHarnesses", params: {} });
    expect(calls[1]).toEqual({ method: "ListModels", params: { harness: "codex" } });
    // The catalog landed and the model list loaded for the default pick.
    expect(state.harnesses).toEqual({ rows: CATALOG });
    expect(state.models.get("codex")).toEqual({
      rows: [{ id: "glm-5.3", label: "GLM 5.3", reasoningLevels: [], options: [] }],
    });
  });

  it("routes the catalog to the source's device when it is not the engine's own", async () => {
    const remote = encodeScopedId(ENGINE, "dev-2");
    const { client, calls } = fakeClient(() => chatRow({ id: "fork-9" }));
    forkDialogStore.open({
      source: chatRow({ deviceId: remote }),
      parentId: "source-1",
      boundary: null,
      paneChatId: "pane-b",
      client,
      engineBaseUrl: ENGINE,
    });
    await flush();
    expect(calls[0]).toEqual({
      method: "ListHarnesses",
      params: { targetDeviceId: remote },
    });
    expect(calls[1]?.params).toMatchObject({ harness: "codex", targetDeviceId: remote });
  });

  it("a configless source defaults to the first offered harness once the catalog lands", async () => {
    const { client } = fakeClient(() => chatRow({ id: "fork-9" }));
    forkDialogStore.open({
      source: chatRow({ config: null }),
      parentId: "source-1",
      boundary: null,
      paneChatId: "pane-c",
      client,
      engineBaseUrl: ENGINE,
    });
    expect(forkDialogStore.getSnapshot().harness).toBeNull();
    await flush();
    expect(forkDialogStore.getSnapshot().harness).toBe("codex");
  });

  it("picking another harness resets the model; picking the source's back restores it", async () => {
    const { client } = fakeClient(() => chatRow({ id: "fork-9" }));
    forkDialogStore.open({
      source: chatRow(),
      parentId: "source-1",
      boundary: null,
      paneChatId: "pane-d",
      client,
      engineBaseUrl: ENGINE,
    });
    await flush();
    forkDialogStore.pickHarness("pi");
    expect(forkDialogStore.getSnapshot().model).toBeNull();
    forkDialogStore.pickModel("glm-5.3");
    expect(forkDialogStore.getSnapshot().model).toBe("glm-5.3");
    forkDialogStore.pickHarness("codex");
    expect(forkDialogStore.getSnapshot().model).toBe("gpt-6-codex");
    forkDialogStore.close();
    expect(forkDialogStore.getSnapshot().open).toBe(false);
  });

  it("confirm forks with only the differing picks, seeds the row, and opens the pane surface", async () => {
    const reply = chatRow({ id: "fork-9", parentChatId: "source-1" });
    const { client, calls } = fakeClient(() => reply);
    forkDialogStore.open({
      source: chatRow(),
      parentId: "source-1",
      boundary: "a1",
      paneChatId: "pane-e",
      client,
      engineBaseUrl: ENGINE,
    });
    await flush();
    forkDialogStore.pickHarness("pi");
    forkDialogStore.pickModel("glm-5.3");
    forkDialogStore.confirm();
    expect(forkDialogStore.getSnapshot().creating).toBe(true);
    await flush();

    const fork = calls.find((call) => call.method === "ForkSideChat");
    expect(fork).toBeDefined();
    expect(fork!.params).toMatchObject({
      sourceChatId: "source-1",
      parentChatId: "source-1",
      boundary: "a1",
      harness: "pi",
      model: "glm-5.3",
      targetDeviceId: OWN_DEVICE,
    });
    expect(typeof fork!.params.chatId).toBe("string");
    // The seed bridges the beat before the registry frame lands the row —
    // scoped to the engine that minted it.
    const scoped = encodeScopedId(ENGINE, "fork-9");
    expect(pendingSideChat(scoped)?.parentChatId).toBe("source-1");
    rememberSeed(scoped);
    // The pane the fork started from owns the surface, and the dialog is
    // closed (the surface opening is the feedback).
    const tabs = rightPaneStore.stateFor("pane-e").tabs.filter((tab) => tab.kind === "sidechat");
    expect(tabs).toHaveLength(1);
    const opened = rightPaneStore.sideChatSurfaceOf((tabs[0] as { id: string }).id);
    expect(opened).toEqual({ chatId: scoped, title: "The source chat" });
    expect(forkDialogStore.getSnapshot().open).toBe(false);
  });

  it("an untouched confirm sends the classic fork payload — no overrides ride", async () => {
    const { client, calls } = fakeClient(() => chatRow({ id: "fork-10" }));
    forkDialogStore.open({
      source: chatRow(),
      parentId: "source-1",
      boundary: null,
      paneChatId: "pane-f",
      client,
      engineBaseUrl: ENGINE,
    });
    await flush();
    forkDialogStore.confirm();
    await flush();
    const fork = calls.find((call) => call.method === "ForkSideChat")!;
    expect(fork.params).not.toHaveProperty("boundary");
    expect(fork.params).not.toHaveProperty("harness");
    expect(fork.params).not.toHaveProperty("model");
    rememberSeed(encodeScopedId(ENGINE, "fork-10"));
  });

  it("a failed fork keeps the dialog open with the reason inline", async () => {
    const { client } = fakeClient(() => {
      throw new Error("Wait for a completed response before starting a side chat");
    });
    forkDialogStore.open({
      source: chatRow(),
      parentId: "source-1",
      boundary: null,
      paneChatId: "pane-g",
      client,
      engineBaseUrl: ENGINE,
    });
    await flush();
    forkDialogStore.confirm();
    await flush();
    const state = forkDialogStore.getSnapshot();
    expect(state.open).toBe(true);
    expect(state.creating).toBe(false);
    expect(state.error).toBe("Wait for a completed response before starting a side chat");
    // The guard settled: a retry is possible.
    expect(forkDialogStore.getSnapshot().creating).toBe(false);
  });

  it("a reopen under an in-flight load drops the stale landing", async () => {
    const { client } = fakeClient(() => chatRow({ id: "fork-11" }));
    forkDialogStore.open({
      source: chatRow(),
      parentId: "source-1",
      boundary: null,
      paneChatId: "pane-h",
      client,
      engineBaseUrl: ENGINE,
    });
    forkDialogStore.close();
    forkDialogStore.open({
      source: chatRow({ id: "source-2" }),
      parentId: "source-2",
      boundary: "b2",
      paneChatId: "pane-h",
      client,
      engineBaseUrl: ENGINE,
    });
    await flush();
    const state = forkDialogStore.getSnapshot();
    expect(state.spec?.source.id).toBe("source-2");
    expect(state.harness).toBe("codex");
  });
});
