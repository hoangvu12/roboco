import { describe, expect, it } from "vitest";
import type { Device, DriveEntry, FolderEntry, Space } from "@roboco/proto";
import { methods, encodeScopedId, parseScopedId } from "@roboco/engine-client";
import type { EngineSession } from "../src/state/engine-session";
import { addSpaceStore, toggleAddSpace, type AddSpaceContext } from "../src/state/add-space";
import { uiSettings } from "../src/state/ui-settings";
import {
  addSpaceCompletion,
  breadcrumbs,
  browserRows,
  childPath,
  completionPrefixLen,
  deviceRows,
  filteredFolders,
  highlightRanges,
  isStaleResponse,
  isTypedPath,
  isWindowsPath,
  locationRows,
  manualPathQuery,
  parentPath,
  pathUnder,
  segmentTarget,
  typedPathTarget,
} from "../src/lib/add-space";

/**
 * Ports of the desktop's picker tests (`crates/ui/src/pickers.rs` test
 * module) plus the add-space derivations from `spaces.rs` the palette
 * renders from: `folder_paths_and_breadcrumbs`, `completion_prefix_lengths`,
 * `segment_target_resolution`,
 * `typed_path_target_expands_absolute_and_home_paths`, the `is_stale` rule
 * (`spaces.rs:238-247`), the step-row filters (`add_space_devices` /
 * `add_space_locations`), the match ranges (`search_match_ranges`,
 * popover.rs), and the device-first flow test
 * `devices_locations_folders_and_back_clear_stale_state`.
 */

describe("folder_paths_and_breadcrumbs (pickers.rs)", () => {
  it("parentPath climbs and stops at the root", () => {
    expect(parentPath("/home/w/dev")).toBe("/home/w");
    expect(parentPath("/home")).toBe("/");
    expect(parentPath("/home/")).toBe("/");
    expect(parentPath("/")).toBe(null);
    expect(parentPath("")).toBe(null);
  });

  it("childPath joins without doubling the separator", () => {
    expect(childPath("/home", "w")).toBe("/home/w");
    expect(childPath("/", "home")).toBe("/home");
  });

  it("breadcrumbs walk root-first, accumulating the full path", () => {
    const crumbs = breadcrumbs("/home/w/dev");
    expect(crumbs.map(([label]) => label)).toEqual(["/", "home", "w", "dev"]);
    expect(crumbs[2]![1]).toBe("/home/w");
    expect(breadcrumbs("/")).toHaveLength(1);
  });

  it("windows_folder_paths_and_breadcrumbs (pickers.rs)", () => {
    expect(parentPath("D:\\Random\\roboco")).toBe("D:\\Random");
    expect(parentPath("D:\\Random")).toBe("D:\\");
    expect(parentPath("D:\\Random\\")).toBe("D:\\");
    expect(parentPath("D:\\")).toBe(null);
    expect(parentPath("D:")).toBe(null);
    expect(childPath("D:\\", "Random")).toBe("D:\\Random");
    expect(childPath("D:\\Random", "roboco")).toBe("D:\\Random\\roboco");
    const crumbs = breadcrumbs("D:\\Random\\roboco");
    expect(crumbs.map(([label]) => label)).toEqual(["D:\\", "Random", "roboco"]);
    expect(crumbs[0]![1]).toBe("D:\\");
    expect(crumbs[1]![1]).toBe("D:\\Random");
    expect(breadcrumbs("D:\\")).toHaveLength(1);
    expect(isWindowsPath("/D:/x")).toBe(false);
    expect(isWindowsPath("ab:/x")).toBe(false);
  });
});

describe("completion_prefix_lengths (pickers.rs)", () => {
  it("is case-insensitive and indexes into the name", () => {
    expect(completionPrefixLen("Documents", "doc")).toBe(3);
    expect("Documents".slice(3)).toBe("uments");
    expect(completionPrefixLen("roboco", "roboco")).toBe(6);
    expect(completionPrefixLen("roboco", "")).toBe(0);
    expect(completionPrefixLen("roboco", "dev")).toBe(null);
    // Longer than the name → not a prefix.
    expect(completionPrefixLen("dev", "devel")).toBe(null);
  });

  it("multibyte names slice on a code-point boundary", () => {
    const len = completionPrefixLen("héllo", "hé");
    expect(len).not.toBe(null);
    expect("héllo".slice(len as number)).toBe("llo");
  });
});

describe("segment_target_resolution (pickers.rs)", () => {
  const names = ["github", "GitHub", "worktree"];

  it("exact casing beats the earlier case-insensitive sibling", () => {
    expect(segmentTarget(names, "GitHub")).toBe(1);
    expect(segmentTarget(names, "github")).toBe(0);
  });

  it("case-insensitive exact still lands without an exact-cased hit", () => {
    expect(segmentTarget(names, "WORKTREE")).toBe(2);
  });

  it("unique prefix descends; an ambiguous one keeps the slash honest", () => {
    expect(segmentTarget(names, "work")).toBe(2);
    expect(segmentTarget(names, "g")).toBe(null);
    expect(segmentTarget(names, "x")).toBe(null);
  });
});

describe("typed_path_target_expands_absolute_and_home_paths (pickers.rs)", () => {
  const home = "/home/wing";

  it("absolute paths trim their trailing slash and need no home", () => {
    expect(typedPathTarget("/disk2/", home)).toBe("/disk2");
    expect(typedPathTarget("/disk2/projects", home)).toBe("/disk2/projects");
    expect(typedPathTarget("/", home)).toBe("/");
    expect(typedPathTarget("/disk2", null)).toBe("/disk2");
  });

  it("home-relative paths expand against home", () => {
    expect(typedPathTarget("~", home)).toBe("/home/wing");
    expect(typedPathTarget("~/", home)).toBe("/home/wing");
    expect(typedPathTarget("~/github/", home)).toBe("/home/wing/github");
  });

  it("~x is a folder name; ~ cannot expand before home is known", () => {
    expect(typedPathTarget("~x", home)).toBe(null);
    expect(typedPathTarget("src", home)).toBe(null);
    expect(typedPathTarget("~/github", null)).toBe(null);
  });

  it("typed_path_target_accepts_windows_drive_paths (pickers.rs)", () => {
    const windowsHome = "C:\\Users\\wing";
    expect(typedPathTarget("D:\\", windowsHome)).toBe("D:\\");
    expect(typedPathTarget("D:", windowsHome)).toBe("D:\\");
    expect(typedPathTarget("D:/", windowsHome)).toBe("D:\\");
    expect(typedPathTarget("D:\\Random\\roboco\\", windowsHome)).toBe("D:\\Random\\roboco");
    // Forward slashes normalise so the crumb trail can match the path.
    expect(typedPathTarget("D:/Random/roboco", null)).toBe("D:\\Random\\roboco");
    expect(isTypedPath("D:\\x")).toBe(true);
    expect(isTypedPath("/x")).toBe(true);
    expect(isTypedPath("~")).toBe(true);
    expect(isTypedPath("src")).toBe(false);
    expect(isTypedPath("ab:/x")).toBe(false);
  });
});

describe("manual_path_query (spaces.rs:254-260)", () => {
  it("recognizes every typed-path shape, trimmed", () => {
    expect(manualPathQuery("/disk2")).toBe(true);
    expect(manualPathQuery("~/x")).toBe(true);
    expect(manualPathQuery("~")).toBe(true);
    expect(manualPathQuery("\\\\server")).toBe(true);
    expect(manualPathQuery("C:\\Users")).toBe(true);
    expect(manualPathQuery("  /disk2 ")).toBe(true);
    expect(manualPathQuery("roboco")).toBe(false);
    expect(manualPathQuery(".hidden")).toBe(false);
  });
});

describe("path_under (spaces.rs)", () => {
  it("is segment-aware — a sibling prefix is not a parent", () => {
    expect(pathUnder("/media/a", "/media")).toBe(true);
    expect(pathUnder("/media", "/media")).toBe(true);
    expect(pathUnder("/media/ab", "/media/a")).toBe(false);
    expect(pathUnder("/media/a", "/media/ab")).toBe(false);
    expect(pathUnder("/anything", "/")).toBe(true);
    expect(pathUnder("/anything", "")).toBe(true);
  });

  it("path_under_handles_posix_and_windows_drive_paths (spaces.rs)", () => {
    expect(pathUnder("/media/a", "/")).toBe(true);
    expect(pathUnder("/media/a", "/media")).toBe(true);
    expect(pathUnder("/media/ab", "/media/a")).toBe(false);
    // A drive-root crumb hides itself, not a sibling drive.
    expect(pathUnder("D:\\", "D:\\")).toBe(true);
    expect(pathUnder("D:\\Random", "D:\\")).toBe(true);
    expect(pathUnder("D:\\Random2", "D:\\Random")).toBe(false);
    expect(pathUnder("C:\\Random", "D:\\")).toBe(false);
  });
});

const entry = (name: string, isDir: boolean, isRepo = false): FolderEntry => ({
  name,
  isDir,
  isRepo,
});

describe("browserRows + filteredFolders + completion (spaces.rs)", () => {
  const entries = [
    entry("dev", true, true),
    entry("notes.txt", false),
    entry("Documents", true),
    entry(".config", true),
    entry("github", true),
  ];

  it("browserRows keeps directories only", () => {
    expect(browserRows(entries).map((row) => row.name)).toEqual(["dev", "Documents", ".config", "github"]);
  });

  it("filtering ranks prefix matches first and hides dotfiles by default", () => {
    const rows = filteredFolders(entries, "");
    expect(rows.map((row) => row.name)).toEqual(["dev", "Documents", "github"]);
  });

  it("a leading dot reveals the dotfiles (client-side insurance)", () => {
    const rows = filteredFolders(entries, ".");
    expect(rows.map((row) => row.name)).toEqual([".config"]);
  });

  it("substring matches come after prefix matches", () => {
    const rows = filteredFolders(entries, "d");
    expect(rows.map((row) => row.name)).toEqual(["dev", "Documents"]);
  });

  it("completion prefers the highlighted row, else the first prefix match", () => {
    const rows = filteredFolders(entries, "");
    expect(addSpaceCompletion(rows, 0, "de")).toEqual({ name: "dev", suffix: "v" });
    expect(addSpaceCompletion(rows, 1, "de")).toEqual({ name: "dev", suffix: "v" });
    expect(addSpaceCompletion(rows, 0, "doc")).toEqual({ name: "Documents", suffix: "uments" });
    // An already-complete match previews nothing; an empty query neither.
    expect(addSpaceCompletion(rows, 0, "dev")).toBe(null);
    expect(addSpaceCompletion(rows, 0, "")).toBe(null);
    expect(addSpaceCompletion(rows, 0, "zzz")).toBe(null);
  });
});

describe("is_stale (spaces.rs:238-247)", () => {
  const flow = { identity: "id-1", engineKey: "engine-a", revision: 3, deviceId: "device-a" };

  it("drops responses from another open, engine, superseded browse, or device", () => {
    const request = { identity: "id-1", engineKey: "engine-a", revision: null, deviceId: "device-a" };
    expect(isStaleResponse(flow, request)).toBe(false);
    expect(isStaleResponse(flow, { ...request, identity: "id-2" })).toBe(true);
    expect(isStaleResponse(flow, { ...request, engineKey: "engine-b" })).toBe(true);
    expect(isStaleResponse(flow, { ...request, revision: 4 })).toBe(true);
    expect(isStaleResponse(flow, { ...request, deviceId: "device-b" })).toBe(true);
    expect(isStaleResponse(flow, { ...request, revision: 99 })).toBe(true);
    expect(isStaleResponse({ ...flow, revision: 99 }, { ...request, revision: 99 })).toBe(false);
  });
});

const drive = (name: string, path: string): DriveEntry => ({ name, path });

describe("step rows: deviceRows + locationRows (spaces.rs)", () => {
  const devices: Device[] = [
    { id: "d-local", name: "Studio", platform: "macos", lastSeenAt: null, createdAt: null },
    { id: "d-remote", name: "Server", platform: "linux", lastSeenAt: null, createdAt: null },
  ];

  it("deviceRows filters and ranks by name, preserving the device row", () => {
    expect(deviceRows(devices, "").map((row) => row.id)).toEqual(["d-local", "d-remote"]);
    expect(deviceRows(devices, "server").map((row) => row.id)).toEqual(["d-remote"]);
    expect(deviceRows(devices, "zzz")).toEqual([]);
  });

  it("locationRows always leads with Home, then the mounted drives", () => {
    const drives = [drive("Projects", "/projects"), drive("System", "/")];
    expect(locationRows(drives, "")).toEqual([
      { name: "Home", path: null },
      { name: "Projects", path: "/projects" },
      { name: "System", path: "/" },
    ]);
    expect(locationRows(drives, "proj")).toEqual([{ name: "Projects", path: "/projects" }]);
    // A failed drive load leaves the list at Home only — no error UI.
    expect(locationRows([], "")).toEqual([{ name: "Home", path: null }]);
  });
});

describe("highlightRanges (popover.rs search_match_ranges)", () => {
  it("inline matches keep adjacent word boundaries", () => {
    const text = "fieldnotes/fix-authentication-redirects";
    const ranges = highlightRanges(text, "authentication");
    expect(ranges).toHaveLength(1);
    expect(text.slice(0, ranges[0]!.start)).toBe("fieldnotes/fix-");
    expect(text.slice(ranges[0]!.start, ranges[0]!.end)).toBe("authentication");
    expect(text.slice(ranges[0]!.end)).toBe("-redirects");
  });

  it("highlights repeated case-insensitive and overlapping words", () => {
    expect(highlightRanges("New chat, new project", "NEW")).toEqual([
      { start: 0, end: 3 },
      { start: 10, end: 13 },
    ]);
    expect(highlightRanges("authentication", "auth authentication")).toEqual([{ start: 0, end: 14 }]);
    expect(highlightRanges("New chat", "  ")).toEqual([]);
    expect(highlightRanges("New chat", "settings")).toEqual([]);
  });

  it("preserves original unicode boundaries after lowercase expansion", () => {
    // UTF-16 code units here — the desktop's ranges are UTF-8 bytes, the
    // spans are the same characters.
    expect(highlightRanges("İstanbul café", "i CAFÉ")).toEqual([
      { start: 0, end: 1 },
      { start: 9, end: 13 },
    ]);
    expect(highlightRanges("🚀 CAFÉ", "café")).toEqual([{ start: 3, end: 7 }]);
  });
});

describe("addSpaceStore + toggleAddSpace (shell.rs)", () => {
  /**
   * The fixed `mod-k` binding's toggle, against the headless store (no
   * session attached: `open()` lands on the Devices step and fires no
   * loads). The exit window ends when the mounted palette reports Base
   * UI's `onOpenChangeComplete(false)` — `unmounted()` here stands in for
   * that callback.
   */
  const reaped = (): void => {
    addSpaceStore.unmounted();
  };

  it("a closed palette opens on the Devices step; a mounted one closes", () => {
    expect(addSpaceStore.getSnapshot().status).toBe("closed");
    expect(addSpaceStore.getSnapshot().flow).toBe(null);

    toggleAddSpace();
    expect(addSpaceStore.getSnapshot().status).toBe("open");
    const flow = addSpaceStore.getSnapshot().flow;
    expect(flow).not.toBe(null);
    expect(flow!.step).toBe("devices");
    expect(flow!.deviceId).toBe(null);

    // Mounted → the same chord closes it (the flow lives through the exit
    // window so the card can paint its way out).
    toggleAddSpace();
    expect(addSpaceStore.getSnapshot().status).toBe("closing");
    reaped();
    expect(addSpaceStore.getSnapshot().status).toBe("closed");
    expect(addSpaceStore.getSnapshot().flow).toBe(null);
  });

  it("the chord re-opens once the close has fully drained", () => {
    addSpaceStore.open();
    addSpaceStore.close();
    reaped();
    toggleAddSpace();
    expect(addSpaceStore.getSnapshot().status).toBe("open");
    // Leave the singleton closed for whichever test runs next.
    addSpaceStore.close();
    reaped();
    expect(addSpaceStore.getSnapshot().status).toBe("closed");
  });
});

describe("device-first New project flow (spaces.rs project_flow_tests)", () => {
  const ENGINE = "https://engine.test";
  const REMOTE = "https://remote.test";

  /** One recorded client call — the fake sessions' wire log. */
  type RecordedCall = { method: string; params: Record<string, unknown> };

  function scopedId(key: string, raw: string): string {
    return encodeScopedId(key, raw);
  }

  /** The merged fleet row for one engine's host device (ticket 87). */
  function hostRow(key: string, raw: string, name: string): Device {
    return { ...device(raw, name), id: scopedId(key, raw) };
  }

  /**
   * The fake engine session: one engine key, one host device (the engine's
   * own machine — the merged fleet lists exactly that row), a recording
   * client whose calls resolve instantly (ListFolders echoes the requested
   * path), and optional pre-existing spaces in its watch cache. The store
   * only reads `cache.getSnapshot()`, `client.engineInfo`, and
   * `client.call`, so that is the whole surface.
   */
  function fakeSession(
    key: string,
    host: string | null,
    devices: Device[],
    calls: RecordedCall[],
    extra: { spaces?: Space[]; failMutate?: boolean } = {},
  ): EngineSession {
    return {
      engine: { baseUrl: key, credential: "cred" },
      client: {
        engineInfo: { deviceId: host },
        call: (method: string, params: Record<string, unknown>): Promise<unknown> => {
          calls.push({ method, params });
          if (method === methods.LIST_FOLDERS) {
            const path = typeof params.path === "string" ? params.path : "/home/studio";
            return Promise.resolve({ path, entries: [], truncated: false });
          }
          if (method === methods.LIST_DRIVES) {
            return Promise.resolve({ drives: [{ name: "Projects", path: "/projects" }] });
          }
          if (method === methods.MUTATE) {
            return extra.failMutate === true ? Promise.reject(new Error("engine refused")) : Promise.resolve({});
          }
          return Promise.resolve({});
        },
      },
      cache: {
        getSnapshot: () => ({
          devices: { rows: devices, loaded: devices.length > 0, error: null },
          spaces: { rows: extra.spaces ?? [], loaded: true, error: null },
        }),
      },
    } as unknown as EngineSession;
  }

  /** The single-engine attach: the routed session, its map entry, its host rows. */
  function attachAddSpace(session: EngineSession | null, devices: Device[]): void {
    if (session === null) {
      addSpaceStore.attach({
        session: null,
        sessions: new Map(),
        devices: [],
        goToCanvas: () => {},
      });
      return;
    }
    const key = session.engine.baseUrl;
    const sessions = new Map<string, EngineSession>([[key, session]]);
    addSpaceStore.attach({ session, sessions, devices, goToCanvas: () => {} });
  }

  /**
   * The two-engine fixture the engine-routing tests share: engine A routed
   * (host "threadripper"), engine B pickable through the merged rows (host
   * "ovh"). `attach()` binds the store with a fresh context object each
   * call — the palette's effect does the same on every registry tick —
   * while the sessions stay the same clients, which is what attach's
   * reset check keys on.
   */
  function twoEngineFleet(
    localCalls: RecordedCall[],
    remoteCalls: RecordedCall[],
    remoteExtra: { spaces?: Space[]; failMutate?: boolean } = {},
  ): { attach: () => void } {
    const local = fakeSession(ENGINE, "threadripper", [device("threadripper", "Threadripper")], localCalls);
    const remote = fakeSession(REMOTE, "ovh", [device("ovh", "OVH")], remoteCalls, remoteExtra);
    const sessions = new Map<string, EngineSession>([
      [ENGINE, local],
      [REMOTE, remote],
    ]);
    const devices = [hostRow(ENGINE, "threadripper", "Threadripper"), hostRow(REMOTE, "ovh", "OVH")];
    return {
      attach: () => {
        const context: AddSpaceContext = { session: local, sessions, devices, goToCanvas: () => {} };
        addSpaceStore.attach(context);
      },
    };
  }

  it("resets an open flow in place when the picked engine's connection is replaced", () => {
    try {
      const sessionA = fakeSession(ENGINE, "d-local", [device("d-local", "Studio")], []);
      addSpaceStore.forceClose();
      attachAddSpace(sessionA, [hostRow(ENGINE, "d-local", "Studio")]);
      addSpaceStore.open();
      addSpaceStore.pickDevice(scopedId(ENGINE, "d-local"));
      expect(addSpaceStore.getSnapshot().flow?.engineKey).toBe(ENGINE);
      // A re-pair replaces the engine's client: the flow it was browsing is
      // dead, but the palette stays open and says so — no silent close.
      const sessionB = fakeSession(ENGINE, "d-local", [device("d-local", "Studio")], []);
      attachAddSpace(sessionB, [hostRow(ENGINE, "d-local", "Studio")]);
      const snapshot = addSpaceStore.getSnapshot();
      expect(snapshot.status).toBe("open");
      expect(snapshot.flow?.step).toBe("locations");
      expect(snapshot.flow?.deviceId).toBe(scopedId(ENGINE, "d-local"));
      expect(snapshot.flow?.listing).toEqual({ error: "Device connection changed. Browse again to continue." });
      expect(snapshot.flow?.submitBusy).toBe(false);
    } finally {
      cleanup();
    }
  });

  it("keeps an open flow alive through attach churn while the picked engine holds", () => {
    try {
      const localCalls: RecordedCall[] = [];
      const remoteCalls: RecordedCall[] = [];
      const fleet = twoEngineFleet(localCalls, remoteCalls);
      addSpaceStore.forceClose();
      fleet.attach();
      addSpaceStore.open();
      addSpaceStore.pickDevice(scopedId(REMOTE, "ovh"));
      expect(addSpaceStore.getSnapshot().flow?.step).toBe("locations");
      // The routed engine stays local; registry ticks re-run attach with a
      // fresh context (the palette's effect). The picked engine's client is
      // unchanged, so the flow must survive — the routed engine never owned it.
      fleet.attach();
      const snapshot = addSpaceStore.getSnapshot();
      expect(snapshot.status).toBe("open");
      expect(snapshot.flow?.step).toBe("locations");
      expect(snapshot.flow?.deviceId).toBe(scopedId(REMOTE, "ovh"));
      expect(snapshot.flow?.listing).toBe("idle");
      expect(localCalls).toEqual([]);
    } finally {
      cleanup();
    }
  });

  const flush = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

  const device = (id: string, name: string): Device => ({
    id,
    name,
    platform: id === "d-remote" ? "linux" : "macos",
    lastSeenAt: null,
    createdAt: null,
  });

  const cleanup = (): void => {
    addSpaceStore.close();
    addSpaceStore.unmounted();
  };

  it("devices → locations → folders and back clear stale state", async () => {
    try {
      const devices = [device("d-remote", "Server")];
      const merged = [hostRow(ENGINE, "d-remote", "Server")];
      const calls: RecordedCall[] = [];
      attachAddSpace(fakeSession(ENGINE, "d-remote", devices, calls), merged);

      // The Devices step: no pick, no loads — the query filters the list.
      addSpaceStore.open();
      let flow = addSpaceStore.getSnapshot().flow!;
      expect(flow.step).toBe("devices");
      expect(flow.deviceId).toBe(null);
      expect(calls).toEqual([]);

      addSpaceStore.setQuery("server");
      expect(deviceRows(merged, addSpaceStore.getSnapshot().flow!.query)).toHaveLength(1);
      addSpaceStore.openActive();
      flow = addSpaceStore.getSnapshot().flow!;
      expect(flow.step).toBe("locations");
      expect(flow.deviceId).toBe(scopedId(ENGINE, "d-remote"));
      expect(flow.query).toBe("");
      await flush();
      flow = addSpaceStore.getSnapshot().flow!;
      expect(flow.drives).toEqual([{ name: "Projects", path: "/projects" }]);
      expect(flow.drivesLoading).toBe(false);
      expect(calls.map((call) => call.method)).toEqual([methods.LIST_DRIVES]);

      addSpaceStore.setQuery("projects");
      addSpaceStore.openActive();
      flow = addSpaceStore.getSnapshot().flow!;
      expect(flow.step).toBe("folders");
      expect(flow.location).toEqual({ name: "Projects", path: "/projects" });
      await flush();
      flow = addSpaceStore.getSnapshot().flow!;
      expect(flow.browserPath).toBe("/projects");
      expect(flow.listing).toEqual({ path: "/projects", entries: [], truncated: false });
      expect(calls.map((call) => call.method)).toEqual([methods.LIST_DRIVES, methods.LIST_FOLDERS]);

      // ← on the location's root retreats to Locations; the listing and
      // the location crumb state clear.
      addSpaceStore.goUp();
      flow = addSpaceStore.getSnapshot().flow!;
      expect(flow.step).toBe("locations");
      expect(flow.location).toBe(null);
      expect(flow.listing).toBe("idle");
      expect(flow.query).toBe("");

      // ← again retreats to Devices: the device, its drives and the
      // resolved home go with it.
      addSpaceStore.goUp();
      flow = addSpaceStore.getSnapshot().flow!;
      expect(flow.step).toBe("devices");
      expect(flow.deviceId).toBe(null);
      expect(flow.drives).toEqual([]);
      expect(flow.home).toBe(null);
      expect(flow.query).toBe("");

      // Slash navigation only applies to folders, never device search.
      addSpaceStore.setQuery("/projects/");
      flow = addSpaceStore.getSnapshot().flow!;
      expect(flow.step).toBe("devices");
      expect(flow.query).toBe("/projects/");
    } finally {
      cleanup();
    }
  });

  it("a folders browse descends and the parent climb stops at the location root", async () => {
    try {
      const devices = [device("d-local", "Studio")];
      const calls: RecordedCall[] = [];
      attachAddSpace(fakeSession(ENGINE, "d-local", devices, calls), [
        hostRow(ENGINE, "d-local", "Studio"),
      ]);

      addSpaceStore.open();
      addSpaceStore.pickDevice(scopedId(ENGINE, "d-local"));
      addSpaceStore.gotoLocation("Projects", "/projects");
      await flush();
      // Descend one level in; ← climbs back to the location root…
      addSpaceStore.descend("/projects/roboco", false);
      await flush();
      let flow = addSpaceStore.getSnapshot().flow!;
      expect(flow.listing).toEqual({ path: "/projects/roboco", entries: [], truncated: false });
      addSpaceStore.goUp();
      await flush();
      flow = addSpaceStore.getSnapshot().flow!;
      expect(flow.step).toBe("folders");
      expect(flow.listing).toEqual({ path: "/projects", entries: [], truncated: false });
      // …and ← on the root retreats to Locations instead of climbing past it.
      addSpaceStore.goUp();
      expect(addSpaceStore.getSnapshot().flow!.step).toBe("locations");
    } finally {
      cleanup();
    }
  });

  it("a deviceless folders load surfaces the error row instead of a forever-skeleton", async () => {
    try {
      // No session attached: the routed engine is gone mid-flow.
      attachAddSpace(null, []);
      addSpaceStore.open();
      addSpaceStore.pickDevice("d-gone");
      addSpaceStore.gotoLocation("Home", null);
      await flush();
      const flow = addSpaceStore.getSnapshot().flow!;
      expect(flow.step).toBe("folders");
      expect(flow.listing).toEqual({ error: "Device is not connected" });
    } finally {
      cleanup();
    }
  });

  // ── Engine-routed browsing (zeron 3e14656f / 460b7c89, ticket 89) ───────

  it("browses and creates on the selected engine, not the routed one", async () => {
    try {
      const localCalls: RecordedCall[] = [];
      const remoteCalls: RecordedCall[] = [];
      twoEngineFleet(localCalls, remoteCalls).attach();
      addSpaceStore.open();
      addSpaceStore.pickDevice(scopedId(REMOTE, "ovh"));
      addSpaceStore.gotoLocation("Home", null);
      await flush();
      expect(localCalls).toEqual([]);
      expect(remoteCalls.map((call) => call.method)).toEqual([methods.LIST_DRIVES, methods.LIST_FOLDERS]);
      expect(addSpaceStore.getSnapshot().flow?.listing).toMatchObject({ path: "/home/studio" });
      addSpaceStore.submit();
      await flush();
      expect(localCalls).toEqual([]);
      expect(remoteCalls[2]?.method).toBe(methods.MUTATE);
      expect(remoteCalls[2]?.params).toMatchObject({
        op: "createSpace",
        deviceId: "ovh",
        path: "/home/studio",
      });
    } finally {
      cleanup();
    }
  });

  it("does not browse a missing scoped engine through the routed engine", async () => {
    try {
      const calls: RecordedCall[] = [];
      attachAddSpace(fakeSession(ENGINE, "threadripper", [device("threadripper", "Threadripper")], calls), [
        hostRow(ENGINE, "threadripper", "Threadripper"),
      ]);
      addSpaceStore.open();
      addSpaceStore.pickDevice(scopedId("https://missing.test", "ovh"));
      addSpaceStore.gotoLocation("Home", null);
      await flush();
      expect(calls).toEqual([]);
      expect(addSpaceStore.getSnapshot().flow?.listing).toEqual({ error: "Device is not connected" });
    } finally {
      cleanup();
    }
  });

  it("rejects a device id that is not the engine's own host, even when scoped", async () => {
    try {
      const calls: RecordedCall[] = [];
      attachAddSpace(fakeSession(ENGINE, "threadripper", [device("threadripper", "Threadripper")], calls), [
        hostRow(ENGINE, "threadripper", "Threadripper"),
      ]);
      addSpaceStore.open();
      // Scoped to the connected engine, but the device is not its host —
      // the merged fleet never lists such a row, and the store must not
      // fall back to routing it through that engine's client.
      addSpaceStore.pickDevice(scopedId(ENGINE, "ovh"));
      addSpaceStore.gotoLocation("Home", null);
      await flush();
      expect(calls).toEqual([]);
      expect(addSpaceStore.getSnapshot().flow?.listing).toEqual({ error: "Device is not connected" });
    } finally {
      cleanup();
    }
  });

  it("lands in an existing space matched by raw device id on the owning engine", async () => {
    try {
      const localCalls: RecordedCall[] = [];
      const remoteCalls: RecordedCall[] = [];
      const existing: Space = {
        id: "space-1",
        deviceId: "ovh",
        path: "/home/studio",
        name: "Studio",
        gitDetected: false,
        gitCheckedAt: null,
        checkoutId: null,
        createdAt: "2026-01-01T00:00:00Z",
      };
      twoEngineFleet(localCalls, remoteCalls, { spaces: [existing] }).attach();
      addSpaceStore.open();
      addSpaceStore.pickDevice(scopedId(REMOTE, "ovh"));
      addSpaceStore.gotoLocation("Home", null);
      await flush();
      addSpaceStore.submit();
      await flush();
      // No createSpace — the owning engine's cache already carries the
      // space for this raw device id and path, so submit lands in it.
      expect(localCalls).toEqual([]);
      expect(remoteCalls.map((call) => call.method)).toEqual([methods.LIST_DRIVES, methods.LIST_FOLDERS]);
      expect(addSpaceStore.getSnapshot().pendingSpaces).toEqual([]);
      expect(addSpaceStore.getSnapshot().status).toBe("closing");
      // The landed target carries the owning engine's scope, so the merged
      // sidebar (scoped ids) actually selects it.
      expect(uiSettings.getSnapshot().lastSpaceId).toBe(scopedId(REMOTE, "space-1"));
    } finally {
      uiSettings.update({ lastSpaceId: null }, "immediate");
      cleanup();
    }
  });

  it("rolls the optimistic space row back when createSpace fails on the owning engine", async () => {
    try {
      const localCalls: RecordedCall[] = [];
      const remoteCalls: RecordedCall[] = [];
      twoEngineFleet(localCalls, remoteCalls, { failMutate: true }).attach();
      addSpaceStore.open();
      addSpaceStore.pickDevice(scopedId(REMOTE, "ovh"));
      addSpaceStore.gotoLocation("Home", null);
      await flush();
      addSpaceStore.submit();
      // The optimistic row is minted under the OWNING engine's scope — the
      // merged sidebar replaces it by id once the watch frame confirms.
      const optimistic = addSpaceStore.getSnapshot().pendingSpaces[0]!;
      expect(parseScopedId(optimistic.id).engine).toBe(REMOTE);
      expect(parseScopedId(optimistic.deviceId)).toEqual({ engine: REMOTE, rawId: "ovh" });
      await flush();
      const flow = addSpaceStore.getSnapshot().flow!;
      expect(flow.submitBusy).toBe(false);
      expect(flow.error).toBe("engine refused");
      // The rollback must remove that same scoped row, not a raw id that
      // never matched it.
      expect(localCalls).toEqual([]);
      expect(addSpaceStore.getSnapshot().pendingSpaces).toEqual([]);
    } finally {
      cleanup();
    }
  });
});
