import { describe, expect, it } from "vitest";
import { RpcError } from "@roboco/engine-client";
import { methods } from "@roboco/engine-client";
import type { ProjectActionRun, TerminalSession, WorktreeSpec } from "@roboco/proto";
import {
  WORKTREE_SETUP_POLL_ATTEMPTS,
  WORKTREE_SETUP_POLL_INTERVAL_MS,
  WorktreeSetupSurfacer,
  pollWorktreeSetupOutcome,
  type WorktreeSetupTerminals,
} from "../src/lib/worktree-setup";

/**
 * The worktree-setup outcome path (composer.rs:8625-8703's poll +
 * shell/actions_ui.rs:42-83's attach), web-shaped: after a send whose run
 * carries a worktree spec (space-scoped, so the host runs the space's
 * setup Action), the composer polls `TakeProjectActionSetup` on the
 * desktop's cadence; a ready outcome attaches and selects a terminal tab
 * titled with the action name, a failed setup posts the sidebar notice.
 *
 * The send-side worktree-spec payload shape is deliberately out of scope
 * (the spec's audit note) — these suites drive the poll and the attach
 * directly, against a scripted fake engine session.
 */

const NO_SLEEP = (): Promise<void> => Promise.resolve();

/** A scripted RPC fake — the owning engine's client (project-actions' shape). */
class FakeClient {
  readonly calls: { method: string; params: Record<string, unknown> }[] = [];
  #scripted: ((call: number) => Promise<unknown>) | null = null;

  /** One handler per test, indexed by call number. */
  replyWith(handler: (call: number) => Promise<unknown>): void {
    this.#scripted = handler;
  }

  call<T>(method: string, params: unknown = {}): Promise<T> {
    const record = params as Record<string, unknown>;
    this.calls.push({ method, params: record });
    if (this.#scripted === null) {
      return Promise.resolve({ ready: false } as T);
    }
    return this.#scripted(this.calls.length - 1) as Promise<T>;
  }
}

function terminal(id: string): TerminalSession {
  return { id, pid: 4242, cols: 80, rows: 24 } as unknown as TerminalSession;
}

function run(actionName = "npm install"): ProjectActionRun {
  return { actionId: "act-1", actionName, terminal: terminal("t-1") };
}

function spec(): WorktreeSpec {
  return { repoPath: "/repo", base: "HEAD", spaceId: "space-1" };
}

/** The terminal drawer fake — reserve/attach/select recorded in order. */
class FakeTerminals implements WorktreeSetupTerminals {
  readonly log: string[] = [];
  readonly reserved: { chatId: string; title: string }[] = [];
  #attached = new Set<string>();
  #tabs = new Map<string, string>(); // tab key -> title
  #failAttach = false;
  #next = 0;

  reserveTabForChat(chatId: string, title: string): string | null {
    const key = `tab-${++this.#next}`;
    this.#tabs.set(key, title);
    this.reserved.push({ chatId, title });
    this.log.push(`reserve:${key}:${title}`);
    return key;
  }

  attachReservedSession(chatId: string, key: string, session: TerminalSession): boolean {
    if (this.#failAttach || !this.#tabs.has(key)) {
      this.log.push(`attach-fail:${key}`);
      return false;
    }
    this.#attached.add(key);
    this.log.push(`attach:${key}:${session.id}`);
    return true;
  }

  selectTabByKey(chatId: string, key: string): void {
    this.log.push(`select:${key}`);
  }

  failNextAttach(): void {
    this.#failAttach = true;
  }

  /** The user closes a tab (the mid-flight close the attach must survive). */
  closeTab(key: string): void {
    this.#tabs.delete(key);
  }
}

describe("pollWorktreeSetupOutcome (composer.rs's handoff poll)", () => {
  it("carries the desktop's cadence", () => {
    expect(WORKTREE_SETUP_POLL_INTERVAL_MS).toBe(250);
    expect(WORKTREE_SETUP_POLL_ATTEMPTS).toBe(480);
  });

  it("is inert for a specless send and a spaceless spec", async () => {
    const client = new FakeClient();
    expect(await pollWorktreeSetupOutcome(client, { chatId: "chat-1", commandId: "cmd-1", worktree: null, sleep: NO_SLEEP })).toBeNull();
    expect(await pollWorktreeSetupOutcome(client, { chatId: "chat-1", commandId: "cmd-1", worktree: { repoPath: "/repo", base: "HEAD", spaceId: null }, sleep: NO_SLEEP })).toBeNull();
    expect(client.calls).toEqual([]);
  });

  it("polls TakeProjectActionSetup with the chat and command, plus the routing hint", async () => {
    const client = new FakeClient();
    client.replyWith(() => Promise.resolve({ ready: true, setupAction: run(), setupError: null }));
    const outcome = await pollWorktreeSetupOutcome(client, {
      chatId: "chat-1",
      commandId: "cmd-9",
      worktree: spec(),
      targetDeviceId: "dev-b",
      sleep: NO_SLEEP,
    });
    expect(outcome).toEqual({ setupAction: run(), setupError: null });
    expect(client.calls).toEqual([
      { method: methods.TAKE_PROJECT_ACTION_SETUP, params: { chatId: "chat-1", commandId: "cmd-9", targetDeviceId: "dev-b" } },
    ]);
    // Without a routing hint the param stays off (projectActionParams' rule).
    const bare = new FakeClient();
    bare.replyWith(() => Promise.resolve({ ready: true, setupAction: run(), setupError: null }));
    await pollWorktreeSetupOutcome(bare, { chatId: "chat-1", commandId: "cmd-9", worktree: spec(), sleep: NO_SLEEP });
    expect(bare.calls[0]!.params).toEqual({ chatId: "chat-1", commandId: "cmd-9" });
  });

  it("keeps polling until the host publishes the outcome, then stops", async () => {
    const client = new FakeClient();
    client.replyWith((call) =>
      Promise.resolve(call < 2 ? { ready: false } : { ready: true, setupAction: null, setupError: "setup exploded" }),
    );
    const outcome = await pollWorktreeSetupOutcome(client, { chatId: "chat-1", commandId: "cmd-1", worktree: spec(), sleep: NO_SLEEP });
    expect(outcome).toEqual({ setupAction: null, setupError: "setup exploded" });
    expect(client.calls).toHaveLength(3);
  });

  it("rides out transient errors but stops dead on an old engine's UnknownMethod", async () => {
    const flaky = new FakeClient();
    flaky.replyWith((call) =>
      call === 0
        ? Promise.reject(new Error("transport hiccup"))
        : Promise.resolve({ ready: true, setupAction: run(), setupError: null }),
    );
    expect(
      await pollWorktreeSetupOutcome(flaky, { chatId: "chat-1", commandId: "cmd-1", worktree: spec(), sleep: NO_SLEEP }),
    ).toEqual({ setupAction: run(), setupError: null });
    expect(flaky.calls).toHaveLength(2);

    const stale = new FakeClient();
    stale.replyWith(() => Promise.reject(new RpcError("unknown-method", "unknown method: TakeProjectActionSetup", "TakeProjectActionSetup")));
    expect(await pollWorktreeSetupOutcome(stale, { chatId: "chat-1", commandId: "cmd-1", worktree: spec(), sleep: NO_SLEEP })).toBeNull();
    expect(stale.calls).toHaveLength(1);
  });

  it("gives up after the bounded attempt count", async () => {
    const client = new FakeClient();
    client.replyWith(() => Promise.resolve({ ready: false }));
    expect(await pollWorktreeSetupOutcome(client, { chatId: "chat-1", commandId: "cmd-1", worktree: spec(), sleep: NO_SLEEP })).toBeNull();
    expect(client.calls).toHaveLength(WORKTREE_SETUP_POLL_ATTEMPTS);
  });

  it("sleeps the interval between attempts (never before the first)", async () => {
    const sleeps: number[] = [];
    const client = new FakeClient();
    client.replyWith((call) => Promise.resolve(call < 1 ? { ready: false } : { ready: true, setupAction: run(), setupError: null }));
    await pollWorktreeSetupOutcome(client, {
      chatId: "chat-1",
      commandId: "cmd-1",
      worktree: spec(),
      sleep: (ms) => {
        sleeps.push(ms);
        return Promise.resolve();
      },
    });
    expect(sleeps).toEqual([WORKTREE_SETUP_POLL_INTERVAL_MS]);
  });
});

describe("WorktreeSetupSurfacer (attach_worktree_setup)", () => {
  it("reserves a tab titled with the action name, attaches, and selects it", () => {
    const terminals = new FakeTerminals();
    const surfacer = new WorktreeSetupSurfacer(terminals);
    const notices: string[] = [];
    surfacer.surface("chat-1", { setupAction: run("npm install"), setupError: null }, (message) => notices.push(message));
    expect(terminals.reserved).toEqual([{ chatId: "chat-1", title: "npm install (setup)" }]);
    expect(terminals.log).toEqual(["reserve:tab-1:npm install (setup)", "attach:tab-1:t-1", "select:tab-1"]);
    expect(notices).toEqual([]);
  });

  it("posts the desktop's failure copy for a failed setup", () => {
    const terminals = new FakeTerminals();
    const surfacer = new WorktreeSetupSurfacer(terminals);
    const notices: string[] = [];
    surfacer.surface("chat-1", { setupAction: null, setupError: "no space" }, (message) => notices.push(message));
    expect(notices).toEqual(["Setup action failed: no space"]);
    expect(terminals.log).toEqual([]);
  });

  it("re-reserves after the remembered tab closed, and posts the notice when no attach can land", () => {
    const terminals = new FakeTerminals();
    const surfacer = new WorktreeSetupSurfacer(terminals);
    const notices: string[] = [];
    surfacer.surface("chat-1", { setupAction: run(), setupError: null }, (message) => notices.push(message));
    // The user closed the tab between outcomes: the attach misses, the
    // surfacer re-reserves, and the fresh tab takes the session.
    terminals.closeTab("tab-1");
    surfacer.surface("chat-1", { setupAction: run(), setupError: null }, (message) => notices.push(message));
    expect(notices).toEqual([]);
    expect(terminals.reserved).toHaveLength(2);
    // A fresh reserve whose attach still cannot land (no engine session,
    // a closed-again tab) is the desktop's fallback notice.
    terminals.failNextAttach();
    surfacer.surface("chat-1", { setupAction: run(), setupError: null }, (message) => notices.push(message));
    expect(notices).toEqual(["Setup action started, but its terminal could not be attached"]);
  });

  it("repeated outcomes attach the same surface rather than duplicating tabs", () => {
    const terminals = new FakeTerminals();
    const surfacer = new WorktreeSetupSurfacer(terminals);
    const second = run("npm install");
    second.terminal = terminal("t-2");
    surfacer.surface("chat-1", { setupAction: run("npm install"), setupError: null }, () => {});
    surfacer.surface("chat-1", { setupAction: second, setupError: null }, () => {});
    expect(terminals.reserved).toEqual([{ chatId: "chat-1", title: "npm install (setup)" }]);
    expect(terminals.log.filter((entry) => entry.startsWith("attach:"))).toEqual(["attach:tab-1:t-1", "attach:tab-1:t-2"]);
    // A different action name is a different surface.
    surfacer.surface("chat-1", { setupAction: run("cargo test"), setupError: null }, () => {});
    expect(terminals.reserved).toHaveLength(2);
  });
});
