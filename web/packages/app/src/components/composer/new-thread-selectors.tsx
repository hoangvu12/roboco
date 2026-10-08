import { useCallback, useMemo, useSyncExternalStore } from "react";
import type { Device, RepoRef, Space } from "@roboco/proto";
import { encodeScopedId } from "@roboco/engine-client";
import { Icon } from "@roboco/icons";
import { useEngineSession } from "../../state/session-provider";
import { useNow } from "../../state/hooks";
import { useFleetSnapshot } from "../../state/fleet";
import { composerDefaults } from "../../lib/composer-draft";
import {
  applyCheckoutPick,
  applyRefPick,
  effectiveRefWorktree,
  useDraftGitState,
} from "../../lib/footer-git-draft";
import { resolveNewChatTarget } from "../../lib/new-chat-target";
import { spacesSorted } from "../../lib/view";
import { useSidebar } from "../../state/sidebar";
import { useFleet } from "../../state/fleet";
import { drawerTerminalStore } from "../../terminal/store";
import { canvasTerminalKey, terminalOpenCwd } from "../../terminal/session";
import { CheckoutChip, DeviceChip, ProjectChip, RefChip } from "../composer-footer";

/**
 * The new-thread canvas's target rows — the desktop's
 * `pickers.rs::render_new_thread_target_selectors` (2426-2498, the floating
 * 20px row above the pill: device + project chips) and
 * `render_new_thread_git_selectors` (2502-2566, the footer slot's Layer A:
 * checkout + ref chips).
 *
 * The chips and their popovers are ticket 10's (exported from
 * `../composer-footer.tsx`); this file owns the ROWS that place them and the
 * canvas TARGET they read: the remembered device/project/no-project picks
 * (`composerDefaults` — the web peer of `restore_composer_target`,
 * state.rs:1283-1302) resolved through `effective_device_id`
 * (state.rs:1314-1320): the picked project's host when one is selected,
 * else the explicit device pick, else this device.
 */

/** `useSyncExternalStore` plumbing for the composer-defaults store. */
const subscribeDefaults = (listener: () => void) => composerDefaults.subscribe(listener);
const getDefaults = () => composerDefaults.getSnapshot();

/** The new-chat canvas's resolved run target. */
export interface NewThreadTarget {
  readonly devices: readonly Device[];
  readonly spaces: readonly Space[];
  readonly ownDeviceId: string | null;
  /** The picked space row, or null ("no project" / nothing remembered). */
  readonly space: Space | null;
  /**
   * The picked project's id — `selected_space` in the desktop's state: set
   * even while the row has not landed, so per-space chrome (the terminal
   * canvas key) keeps its bucket. Null = project-less.
   */
  readonly projectId: string | null;
  /** The device that runs the agents for this target. */
  readonly effectiveDevice: Device | null;
  readonly effectiveDeviceId: string | null;
  /**
   * Catalogs and refs come from the device that RUNS the agents — the
   * space's device when it differs from the connected engine's own.
   */
  readonly targetDeviceId: string | null;
}

/**
 * Resolve the canvas target: the remembered picks projected onto the live
 * device/space rows. The remembered project wins; with nothing remembered,
 * the SIDEBAR's space pick (the filter, else the last space) stands in —
 * the desktop's `selected_space` is one field shared by the sidebar filter
 * and the canvas (`land_in_space` routes here after creating one).
 * `effective_device_id` (state.rs:1314-1320): the space's host, else the
 * device pick, else the connected engine's own device.
 */
export function useNewThreadTarget(): NewThreadTarget {
  const session = useEngineSession();
  const fleet = useFleet();
  const snapshot = useFleetSnapshot();
  const defaults = useSyncExternalStore(subscribeDefaults, getDefaults, getDefaults);
  const sidebar = useSidebar();

  return useMemo(() => {
    const devices = snapshot?.devices.rows ?? EMPTY_DEVICES;
    const spaces = spacesSorted(snapshot?.spaces.rows ?? EMPTY_SPACES);
    const resolved = resolveNewChatTarget(defaults, sidebar, fleet.active);
    const projectId = resolved.projectId;
    const space = projectId === null ? null : spaces.find((row) => row.id === projectId) ?? null;
    const ownRawDeviceId = session?.client.engineInfo?.deviceId ?? null;
    const own =
      session !== null && ownRawDeviceId !== null
        ? encodeScopedId(session.engine.baseUrl, ownRawDeviceId)
        : null;
    const effectiveDeviceId = space?.deviceId ?? resolved.deviceId ?? own;
    const effectiveDevice = devices.find((device) => device.id === effectiveDeviceId) ?? null;
    const targetDeviceId =
      space !== null && own !== null && space.deviceId !== own ? space.deviceId : null;
    return {
      devices,
      spaces,
      ownDeviceId: own,
      space,
      projectId,
      effectiveDevice,
      effectiveDeviceId,
      targetDeviceId,
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    snapshot?.devices.rows,
    snapshot?.spaces.rows,
    defaults,
    fleet.active,
    ownDeviceKey(session),
    sidebar.spaceFilter,
    sidebar.lastSpaceId,
  ]);
}

/** The routed session's own (scoped) device id as a memo key. */
function ownDeviceKey(session: ReturnType<typeof useEngineSession>): string | null {
  const deviceId = session?.client.engineInfo?.deviceId ?? null;
  return session !== null && deviceId !== null
    ? encodeScopedId(session.engine.baseUrl, deviceId)
    : null;
}

const EMPTY_DEVICES: readonly Device[] = [];
const EMPTY_SPACES: readonly Space[] = [];

/**
 * `render_new_thread_target_selectors` (pickers.rs:2426-2498): a `flex_none`
 * row, gap 4, with two footer chips in order — the DEVICE chip (monitor
 * icon, label = engine name or "Select engine", warning tint when offline,
 * popover 224) and the PROJECT chip (folder icon, label = space display
 * name or "No project", popover 280, right-aligned by the row's
 * justify-end).
 */
export function NewThreadTargetSelectors() {
  const target = useNewThreadTarget();
  const now = useNow(30_000);

  return (
    <div className="new-thread-target-selectors">
      {/* The project (repository) first, then the device it runs on
          (upstream #799: pick the project, then the device among its
          checkouts). */}
      <ProjectChip
        spaces={target.spaces}
        currentSpaceId={target.space?.id ?? null}
        currentDeviceId={target.effectiveDeviceId}
        fallbackLabel="No project"
      />
      <DeviceChip
        devices={target.devices}
        effectiveDevice={target.effectiveDevice}
        ownDeviceId={target.ownDeviceId}
        now={now}
        fallbackLabel="Select engine"
        // End-aligned on the canvas (wpn-03 — the desktop's
        // `attach_overlay_end`, pickers.rs:3175); the footer's Layer B
        // keeps the start default.
        placement="anchorAboveEnd"
      />
      <NewThreadTerminalAction
        projectId={target.projectId}
        spacePath={target.space?.path ?? null}
      />
    </div>
  );
}

/**
 * The new-chat canvas's terminal action (upstream 23e258ff, #474): opens the
 * managed terminal drawer keyed per space — `space-canvas:{spaceId}` — with
 * the project's folder as the PTY cwd (`~` project-less). The desktop's
 * affordance is the drawer itself; on the canvas this target-selector row is
 * the natural action spot, so the chip drives the same store entry the
 * Mod+J bridge and the chat-page drawer mount use.
 */
function NewThreadTerminalAction({
  projectId,
  spacePath,
}: {
  readonly projectId: string | null;
  readonly spacePath: string | null;
}) {
  const key = canvasTerminalKey(projectId);
  const cwd = terminalOpenCwd(key, spacePath);
  useSyncExternalStore(drawerTerminalStore.subscribe, drawerTerminalStore.getVersion, drawerTerminalStore.getVersion);
  const open = drawerTerminalStore.stateFor(key)?.open ?? false;
  return (
    <button
      type="button"
      id="new-thread-terminal-action"
      className={`footer-menu-chip new-thread-terminal-action ${open ? "footer-menu-chip-open" : ""}`}
      title={open ? "Hide terminal" : "Open terminal"}
      aria-pressed={open}
      onClick={() => {
        drawerTerminalStore.toggle(key, cwd);
      }}
    >
      <Icon name="terminal" size={12} className="footer-menu-chip-icon" />
    </button>
  );
}

/**
 * `render_new_thread_git_selectors` (pickers.rs:2502-2566): the footer
 * slot's Layer A — a `w_full min_w_0` flex row, gap 4, with the
 * checkout-kind chip (popover 224) and the branch chip (popover 320).
 * Renders NOTHING when the target space has no git (the row collapses
 * with it — the slot's height is `SESSION_FOOTER_HEIGHT × bottom_slot`).
 */
export function NewThreadGitSelectors() {
  const session = useEngineSession();
  const target = useNewThreadTarget();
  const space = target.space;
  // Draft picks for the git row — refs are fixed once the chat runs. The
  // owner key — the space plus the device that runs its agents — drives
  // the invalidation (the desktop's space/device observer,
  // pickers.rs:700-737): a switch resets the pick, the checkout kind, and
  // the refs, and re-keys the ref chip below so its own rows/switching
  // state fall with them. Empty while no project is picked — the row
  // renders nothing then, but the hooks stay mounted, so the reset is what
  // clears a pick made in the PREVIOUS project once another lands.
  const gitOwnerKey =
    space === null ? "" : `${space.id}\u0000${target.targetDeviceId ?? ""}`;
  const [draft, setDraft] = useDraftGitState(gitOwnerKey);
  const handleRefs = useCallback(
    (rows: readonly RepoRef[]) => setDraft((current) => ({ ...current, refs: rows })),
    [],
  );

  if (space === null || !space.gitDetected) {
    return null;
  }
  const repoPath = space.path;
  // The checkout chip's "Current worktree" reads the effective ref's
  // worktree (`selected_ref_worktree`, pickers.rs:2182-2184).
  const pickedRefHasWorktree = effectiveRefWorktree(draft, null) !== null;
  // `selected_ref` (pickers.rs:2160-2183): the picked ref, else the repo's
  // current branch — the chips read real values before any interaction.
  const currentRowBranch = draft.refs.find((row) => row.current)?.name ?? null;

  return (
    <div className="new-thread-git-selectors">
      <CheckoutChip
        checkout={draft.checkout}
        pickedRefHasWorktree={pickedRefHasWorktree}
        onPick={(kind) => setDraft(applyCheckoutPick(kind, draft))}
        // Below ALWAYS, no flip (wpn-03 — the desktop's
        // `attach_overlay_below`, pickers.rs:3242-3258: the floating row
        // sits above the pill, so a card opening upward covers the input);
        // the draft footer keeps the above default.
        placement="anchorBelow"
      />
      <RefChip
        key={gitOwnerKey}
        session={session}
        repoPath={repoPath}
        currentBranch={currentRowBranch}
        draftBranch={draft.branch}
        checkout={draft.checkout}
        targetDeviceId={target.targetDeviceId}
        canPick={session !== null}
        autoLoad={space.gitDetected}
        onPick={(row) => setDraft(applyRefPick(row, draft))}
        onRefs={handleRefs}
        placement="anchorBelow"
      />
    </div>
  );
}
