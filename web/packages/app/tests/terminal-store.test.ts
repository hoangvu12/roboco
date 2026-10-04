// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import type { EngineSession } from "../src/state/engine-session";
import { TerminalStore } from "../src/terminal/store";

vi.mock("@xterm/xterm", () => ({
  Terminal: class {
    options = {};
    loadAddon() {}
    attachCustomKeyEventHandler() {}
    onData() {}
    onTitleChange() {}
    dispose = vi.fn();
  },
}));
vi.mock("@xterm/addon-fit", () => ({ FitAddon: class { fit() {} } }));

describe("TerminalStore engine ownership", () => {
  it("keeps A's tabs alive when B is viewed, and disposes only replaced sessions", () => {
    const store = new TerminalStore("drawer");
    const a = { engine: { baseUrl: "https://a.test" }, client: {} } as EngineSession;
    const b = { engine: { baseUrl: "https://b.test" }, client: {} } as EngineSession;
    store.bindSession(a);
    const keyA = store.reserveTabForChat("chat", "A");
    expect(keyA).not.toBeNull();
    const tabA = store.stateFor("chat")!.tabs[0]!;
    const disposeA = vi.spyOn(tabA.term, "dispose");
    store.bindSession(b);
    expect(store.stateFor("chat")).toBeUndefined();
    store.reserveTabForChat("chat", "B");
    const tabB = store.stateFor("chat")!.tabs[0]!;
    store.bindSession(a);
    expect(store.stateFor("chat")!.tabs[0]).toBe(tabA);
    expect(disposeA).not.toHaveBeenCalled();
    store.bindSession({ ...a }); // metadata-only wrapper refresh retains the connection
    expect(disposeA).not.toHaveBeenCalled();
    store.bindSession({ ...a, client: {} } as EngineSession);
    expect(disposeA).toHaveBeenCalledOnce();
    store.bindSession(b);
    expect(store.stateFor("chat")!.tabs[0]).toBe(tabB);
    store.dispose();
  });
});
