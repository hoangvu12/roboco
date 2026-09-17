import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { Icon, type IconName } from "@roboco/icons";
import { methods } from "@roboco/engine-client";
import type { ChangeRequestSummary, ContextUsage, Device, RepoRef, Space } from "@roboco/proto";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { deviceOnline, spaceDisplayName, spacesSorted } from "../lib/view";
import { classifyKey, filterIndices, menuStep } from "../lib/picker-search";
import { anchorAbove, anchorAboveEnd } from "../lib/popover-anchor";
import { addSpaceStore } from "../state/add-space";
import { composerDefaults, rememberTarget } from "../lib/composer-draft";
import { ContextUsageIndicator } from "./context-usage";
import { ChangeRequestBadge } from "./change-request-badge";
import { PopoverCard, SearchInputFrame } from "./popover/menu";
import { MenuRowNav } from "./popover/menu-row";
import { ErrorRow, SkeletonRows } from "./popover/skeleton";
import { POPUP_TRIGGER_ATTR, Popup, usePopup } from "./popover/popup";

/**
 * The session footer under the composer — the desktop's `workspace_footer_row`
 * (`pickers.rs:2341-2422`, SESSION_FOOTER_HEIGHT 24).
 *
 * While a chat has neither a persisted `ChatConfig` nor a stamped branch (the
 * web's closest analogue of the desktop's draft canvas — ticket 15's
 * new-thread route re-homes the device/project pair), the row carries the
 * four draft chips: device and project (the run target, writing the
 * remembered defaults) and checkout kind + ref (the git target, `SwitchRef`
 * executing against the space folder for a plain non-current ref). Once the
 * chat is committed, the same slots become read-only `FooterLabel`s: a git
 * ref is fixed at creation, so the desktop never offers a picker there. The
 * trailing cluster (change-request badge + usage indicator) belongs to both
 * variants; the row's geometry is ticket 13's.
 */

/** `MAX_REF_ROWS` (pickers.rs) — the ref list's cap, surfaced as "Showing X of Y". */
const MAX_REF_ROWS = 300;

type CheckoutKind = "local" | "newWorktree";

export interface ComposerFooterProps {
  readonly chat: {
    readonly id: string;
    readonly branch: string | null;
    readonly config: unknown;
    readonly spaceId?: string | null;
    readonly cwd: string | null;
  };
  readonly crSummary: ChangeRequestSummary | null;
  readonly contextUsage: ContextUsage | null;
}

export function ComposerFooter({ chat, crSummary, contextUsage }: ComposerFooterProps) {
  const session = useEngineSession();
  const snapshot = useWatchSnapshot(session);
  const now = useNow(30_000);

  const devices = snapshot?.devices.rows ?? EMPTY_DEVICES;
  const spaces = useMemo(() => spacesSorted(snapshot?.spaces.rows ?? EMPTY_SPACES), [snapshot?.spaces.rows]);
  const space =
    chat.spaceId === null || chat.spaceId === undefined
      ? null
      : spaces.find((row) => row.id === chat.spaceId) ?? null;
  const ownDeviceId = session?.client.engineInfo?.deviceId ?? null;
  const effectiveDeviceId = space?.deviceId ?? ownDeviceId;
  const effectiveDevice = devices.find((device) => device.id === effectiveDeviceId) ?? null;
  // Catalogs and refs come from the device that RUNS the agents — the
  // space's device when it differs from the connected engine's own.
  const targetDeviceId =
    space !== null && ownDeviceId !== null && space.deviceId !== ownDeviceId ? space.deviceId : null;

  const committed = chat.config !== null || chat.branch !== null;
  // Draft picks for the git row (refs are fixed once the chat runs).
  const [draftBranch, setDraftBranch] = useState<string | null>(null);
  const [checkout, setCheckout] = useState<CheckoutKind>("local");
  // The loaded refs, lifted so the checkout chip can read "Current worktree"
  // off the picked ref (`checkout_label`, pickers.rs:1280-1304).
  const [refs, setRefs] = useState<readonly RepoRef[]>([]);

  const spacePath = space?.path ?? null;
  const canSwitch = !committed && spacePath !== null && session !== null;
  const picked = draftBranch ?? chat.branch;
  const pickedRefHasWorktree =
    picked !== null && refs.some((row) => row.name === picked && row.worktreePath !== null && row.worktreePath !== undefined);

  return (
    <div className={`composer-footer ${committed ? "" : "composer-footer-draft"}`}>
      {committed ? (
        <>
          <FooterLabel icon="folderWithFiles" label="Local checkout" />
          <FooterLabel icon="gitBranch" label={chat.branch ?? "No ref"} />
        </>
      ) : (
        <>
          <DeviceChip devices={devices} effectiveDevice={effectiveDevice} ownDeviceId={ownDeviceId} now={now} />
          <ProjectChip spaces={spaces} currentSpaceId={space?.id ?? null} />
          <CheckoutChip
            checkout={checkout}
            pickedRefHasWorktree={pickedRefHasWorktree}
            onPick={(kind) => {
              setCheckout(kind);
              // Picking Local from NewWorktree with a non-current plain ref
              // picked drops the branch override — the current branch takes
              // over (pickers.rs:1359-1373).
              if (
                kind === "local" &&
                checkout === "newWorktree" &&
                !pickedRefHasWorktree &&
                picked !== null &&
                !refs.some((row) => row.name === picked && row.current)
              ) {
                setDraftBranch(null);
              }
            }}
          />
          <RefChip
            session={session}
            repoPath={spacePath}
            currentBranch={chat.branch}
            draftBranch={draftBranch}
            checkout={checkout}
            targetDeviceId={targetDeviceId}
            canPick={canSwitch}
            onPick={(name) => setDraftBranch(name)}
            onRefs={setRefs}
          />
        </>
      )}
      <span className="footer-spring" />
      {crSummary !== null && <ChangeRequestBadge summary={crSummary} />}
      <ContextUsageIndicator usage={contextUsage} />
    </div>
  );
}

const EMPTY_DEVICES: readonly Device[] = [];
const EMPTY_SPACES: readonly Space[] = [];

// ---------------------------------------------------------------------------
// FooterChip / FooterLabel (pickers.rs:2341-2422)
// ---------------------------------------------------------------------------

interface FooterChipProps {
  readonly id: string;
  readonly icon: IconName;
  readonly label: string;
  readonly open: boolean;
  readonly offline?: boolean;
  readonly title: string;
  readonly onPointerDown: () => void;
  readonly onClick: () => void;
  /**
   * The trigger rect's owner — React 19 passes `ref` as a plain prop. The
   * chip's popover anchors to this button (`placeAbove`/`placeAboveEnd` read
   * `chipRef.current.getBoundingClientRect()`), so every caller must hand
   * its chip ref through; a dead ref falls back to the viewport corner.
   */
  readonly ref?: React.Ref<HTMLButtonElement>;
}

/**
 * The small ghost dropdown chip: 20px tall, 6px radius, 12px medium; the wash
 * is quiet until hovered, open holding the hover fill (snapped, no fade).
 * The offline device chip overrides its text to `warning @ 0.8`.
 */
export function FooterChip(props: FooterChipProps) {
  const { id, icon, label, open, offline, title, onPointerDown, onClick, ref } = props;
  return (
    <button
      type="button"
      id={id}
      ref={ref}
      {...{ [POPUP_TRIGGER_ATTR]: "" }}
      className={`footer-menu-chip ${open ? "footer-menu-chip-open" : ""} ${
        offline === true ? "footer-menu-chip-offline" : ""
      }`}
      onPointerDown={onPointerDown}
      onClick={onClick}
      aria-haspopup="menu"
      aria-expanded={open}
      title={title}
    >
      <Icon name={icon} size={12} className="footer-menu-chip-icon" />
      <span className="footer-menu-chip-label">{label}</span>
      <Icon name="altArrowDown" size={12} className="footer-menu-chip-caret" />
    </button>
  );
}

/** `FooterLabel` — the read-only committed variant: no chevron, no background, no hover. */
export function FooterLabel({ icon, label }: { icon: IconName; label: string }) {
  return (
    <span className="footer-menu-label" title={label}>
      <Icon name={icon} size={12} className="footer-menu-label-icon" />
      <span className="footer-menu-label-text">{label}</span>
    </span>
  );
}

// ---------------------------------------------------------------------------
// The device popover (pickers.rs:1928-2006) — width 224
// ---------------------------------------------------------------------------

function DeviceChip({
  devices,
  effectiveDevice,
  ownDeviceId,
  now,
}: {
  readonly devices: readonly Device[];
  readonly effectiveDevice: Device | null;
  readonly ownDeviceId: string | null;
  readonly now: number;
}) {
  const chipRef = useRef<HTMLButtonElement | null>(null);
  const popup = usePopup<"device">();

  // Device order: this device first, then by lowercased name, then by id.
  const rows = useMemo(() => {
    return [...devices].sort((a, b) => {
      const aLocal = a.id === ownDeviceId ? 0 : 1;
      const bLocal = b.id === ownDeviceId ? 0 : 1;
      if (aLocal !== bLocal) {
        return aLocal - bLocal;
      }
      const byName = a.name.toLowerCase().localeCompare(b.name.toLowerCase());
      return byName !== 0 ? byName : a.id.localeCompare(b.id);
    });
  }, [devices, ownDeviceId]);

  const label = effectiveDevice?.name ?? "Select device";
  const offline = effectiveDevice !== null && !deviceOnline(effectiveDevice, now);

  return (
    <>
      <FooterChip
        id="picker-device"
        ref={chipRef}
        icon="monitor"
        label={label}
        open={popup.get() !== null}
        offline={offline}
        title={label}
        onPointerDown={() => popup.noteTriggerPress()}
        onClick={() => {
          if (popup.takePressWasOpen()) {
            return;
          }
          popup.open("device");
        }}
      />
      <Popup popup={popup} placement={(size) => placeAbove(chipRef, size)}>
        {() => (
          <DeviceCard popup={popup} rows={rows} ownDeviceId={ownDeviceId} effectiveDeviceId={effectiveDevice?.id ?? null} now={now} />
        )}
      </Popup>
    </>
  );
}

function DeviceCard({
  popup,
  rows,
  ownDeviceId,
  effectiveDeviceId,
  now,
}: {
  readonly popup: ReturnType<typeof usePopup<"device">>;
  readonly rows: readonly Device[];
  readonly ownDeviceId: string | null;
  readonly effectiveDeviceId: string | null;
  readonly now: number;
}) {
  const [query, setQuery] = useState("");
  const [cursor, setCursor] = useState(0);
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (popup.isOpen()) {
      setQuery("");
      // Device → the effective device's index, else 0.
      const target = rows.findIndex((device) => device.id === effectiveDeviceId);
      setCursor(target < 0 ? 0 : target);
      inputRef.current?.focus();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [popup.isOpen()]);

  const names = rows.map((device) => device.name);
  const filtered = filterIndices(query, names).map((ix) => rows[ix]!);

  function pick(device: Device): void {
    const snapshot = composerDefaults.getSnapshot();
    rememberTarget(device.id, snapshot.project, snapshot.noProject);
    popup.dismiss();
  }

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>): void => {
    if (popup.asOpen() === null) {
      return;
    }
    const key = classifyKey(event.key, event.metaKey, event.ctrlKey);
    if (key === "down" || key === "up") {
      event.preventDefault();
      setCursor((current) => menuStep(current, filtered.length, key === "down" ? 1 : -1) ?? 0);
      return;
    }
    if (key === "enter" || key === "mod-enter") {
      event.preventDefault();
      const device = filtered[cursor];
      if (device !== undefined) {
        pick(device);
      }
      return;
    }
    if (key === "escape") {
      event.preventDefault();
      popup.closeByEscape();
    }
  };

  return (
    <PopoverCard role="dialog" aria-label="Devices" style={{ width: 224 }} onKeyDown={onKeyDown}>
      <SearchInputFrame>
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setCursor(0);
          }}
          placeholder="Search devices…"
          spellCheck={false}
          autoComplete="off"
          aria-label="Search devices"
        />
      </SearchInputFrame>
      {filtered.length === 0 ? (
        <div className="picker-empty-note">No devices match.</div>
      ) : (
        <div className="picker-list">
          {filtered.map((device, ix) => (
            <MenuRowNav
              key={device.id}
              fadeKey={device.id}
              highlighted={ix === cursor}
              selected={device.id === effectiveDeviceId}
              onClick={() => pick(device)}
            >
              <span className="menu-row-label">{device.name}</span>
              {device.id === ownDeviceId && <span className="picker-row-tag">You</span>}
              {!deviceOnline(device, now) && <Icon name="wifiOff" size={12} className="picker-row-offline" />}
            </MenuRowNav>
          ))}
        </div>
      )}
    </PopoverCard>
  );
}

// ---------------------------------------------------------------------------
// The project popover (pickers.rs:2012-2124) — width 280
// ---------------------------------------------------------------------------

function ProjectChip({
  spaces,
  currentSpaceId,
}: {
  readonly spaces: readonly Space[];
  readonly currentSpaceId: string | null;
}) {
  const chipRef = useRef<HTMLButtonElement | null>(null);
  const popup = usePopup<"project">();

  const pickedSpace = currentSpaceId === null ? null : spaces.find((space) => space.id === currentSpaceId) ?? null;
  const label = pickedSpace === null ? "All projects" : spaceDisplayName(pickedSpace);

  return (
    <>
      <FooterChip
        id="picker-project"
        ref={chipRef}
        icon="folder"
        label={label}
        open={popup.get() !== null}
        title={label}
        onPointerDown={() => popup.noteTriggerPress()}
        onClick={() => {
          if (popup.takePressWasOpen()) {
            return;
          }
          popup.open("project");
        }}
      />
      <Popup popup={popup} placement={(size) => placeAboveEnd(chipRef, size)}>
        {() => <ProjectCard popup={popup} spaces={spaces} currentSpaceId={currentSpaceId} />}
      </Popup>
    </>
  );
}

function ProjectCard({
  popup,
  spaces,
  currentSpaceId,
}: {
  readonly popup: ReturnType<typeof usePopup<"project">>;
  readonly spaces: readonly Space[];
  readonly currentSpaceId: string | null;
}) {
  const [query, setQuery] = useState("");
  const [cursor, setCursor] = useState(0);
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (popup.isOpen()) {
      setQuery("");
      // Space → the current space's index; the trailing "opt-out" row when
      // the draft has no project; NO_ACTIVE_ROW means 0 on the first Down.
      const target =
        currentSpaceId === null
          ? spaces.length
          : spaces.findIndex((space) => space.id === currentSpaceId);
      setCursor(target < 0 ? 0 : target);
      inputRef.current?.focus();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [popup.isOpen()]);

  const labels = spaces.map((space) => spaceDisplayName(space));
  const filtered = filterIndices(query, labels).map((ix) => spaces[ix]!);

  function pickSpace(space: Space): void {
    const snapshot = composerDefaults.getSnapshot();
    rememberTarget(snapshot.device, space.id, false);
    popup.dismiss();
  }

  function pickNoProject(): void {
    const snapshot = composerDefaults.getSnapshot();
    rememberTarget(snapshot.device, null, true);
    popup.dismiss();
  }

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>): void => {
    if (popup.asOpen() === null) {
      return;
    }
    const key = classifyKey(event.key, event.metaKey, event.ctrlKey);
    if (key === "down" || key === "up") {
      event.preventDefault();
      // Spaces + the trailing "Don't work in a project" row (§2.5).
      setCursor((current) => menuStep(current, filtered.length + 1, key === "down" ? 1 : -1) ?? 0);
      return;
    }
    if (key === "enter" || key === "mod-enter") {
      event.preventDefault();
      if (cursor < filtered.length) {
        pickSpace(filtered[cursor]!);
      } else if (cursor === filtered.length) {
        pickNoProject();
      }
      return;
    }
    if (key === "escape") {
      event.preventDefault();
      popup.closeByEscape();
    }
  };

  return (
    <PopoverCard role="dialog" aria-label="Project" style={{ width: 280 }} onKeyDown={onKeyDown}>
      <SearchInputFrame>
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setCursor(0);
          }}
          placeholder="Search projects…"
          spellCheck={false}
          autoComplete="off"
          aria-label="Search projects"
        />
      </SearchInputFrame>
      {filtered.length === 0 ? (
        <div className="picker-empty-note">
          {query.trim().length > 0 ? "No projects match." : "No projects on this device."}
        </div>
      ) : (
        <div className="picker-list">
          {filtered.map((space, ix) => (
            <MenuRowNav
              key={space.id}
              fadeKey={space.id}
              highlighted={ix === cursor}
              selected={space.id === currentSpaceId}
              onClick={() => pickSpace(space)}
            >
              <span className="menu-row-label">{spaceDisplayName(space)}</span>
            </MenuRowNav>
          ))}
        </div>
      )}
      {/* A one-off local divider (pickers.rs:2113-2120), not a shared primitive. */}
      <div className="picker-divider" />
      <MenuRowNav
        fadeKey="new-project"
        onClick={() => {
          // Close this popover, THEN open the add-space palette
          // (pickers.rs:2115-2121 / §2.4.3 — ticket 11's `addSpaceStore`
          // owns the surface).
          popup.dismiss();
          addSpaceStore.open();
        }}
      >
        <Icon name="plus" size={12} className="picker-row-icon" />
        <span className="menu-row-label">New project…</span>
      </MenuRowNav>
      <MenuRowNav
        fadeKey="no-project"
        highlighted={cursor === filtered.length}
        selected={currentSpaceId === null}
        onClick={pickNoProject}
      >
        <Icon name="close" size={12} className="picker-row-icon" />
        <span className="menu-row-label">Don&apos;t work in a project</span>
      </MenuRowNav>
    </PopoverCard>
  );
}

// ---------------------------------------------------------------------------
// The checkout-kind popover (pickers.rs:3073-3131) — width 224, two rows
// ---------------------------------------------------------------------------

function CheckoutChip({
  checkout,
  pickedRefHasWorktree,
  onPick,
}: {
  readonly checkout: CheckoutKind;
  readonly pickedRefHasWorktree: boolean;
  readonly onPick: (kind: CheckoutKind) => void;
}) {
  const chipRef = useRef<HTMLButtonElement | null>(null);
  const popup = usePopup<"checkout">();

  // `checkout_label` (pickers.rs:1280-1304): "New worktree" |
  // "Current worktree" when the picked ref has an existing worktree, else
  // "Current checkout".
  const label =
    checkout === "newWorktree" ? "New worktree" : pickedRefHasWorktree ? "Current worktree" : "Current checkout";

  function pick(kind: CheckoutKind): void {
    onPick(kind);
    popup.dismiss();
  }

  return (
    <>
      <FooterChip
        id="picker-checkout"
        ref={chipRef}
        icon={checkout === "newWorktree" || pickedRefHasWorktree ? "folderWithFiles" : "folder"}
        label={label}
        open={popup.get() !== null}
        title={label}
        onPointerDown={() => popup.noteTriggerPress()}
        onClick={() => {
          if (popup.takePressWasOpen()) {
            return;
          }
          popup.open("checkout");
        }}
      />
      <Popup popup={popup} placement={(size) => placeAbove(chipRef, size)}>
        {() => <CheckoutCard popup={popup} checkout={checkout} onPick={pick} />}
      </Popup>
    </>
  );
}

function CheckoutCard({
  popup,
  checkout,
  onPick,
}: {
  readonly popup: ReturnType<typeof usePopup<"checkout">>;
  readonly checkout: CheckoutKind;
  readonly onPick: (kind: CheckoutKind) => void;
}) {
  const [cursor, setCursor] = useState<CheckoutKind>(checkout);

  // Enter picks the highlighted kind; ↑/↓ walk the two rows (toggle step 5:
  // Checkout anchors on 0 or 1).
  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>): void => {
    if (popup.asOpen() === null) {
      return;
    }
    const key = classifyKey(event.key, event.metaKey, event.ctrlKey);
    if (key === "down" || key === "up") {
      event.preventDefault();
      setCursor((current) => (current === "local" ? "newWorktree" : "local"));
      return;
    }
    if (key === "enter" || key === "mod-enter") {
      event.preventDefault();
      onPick(cursor);
      return;
    }
    if (key === "escape") {
      event.preventDefault();
      popup.closeByEscape();
    }
  };

  return (
    <PopoverCard role="dialog" aria-label="Checkout kind" style={{ width: 224 }} onKeyDown={onKeyDown}>
      <div className="picker-list picker-list-plain">
        <MenuRowNav
          fadeKey="local"
          highlighted={cursor === "local" && checkout !== "local"}
          selected={checkout === "local"}
          onMouseEnter={() => setCursor("local")}
          onClick={() => onPick("local")}
        >
          <Icon name="folder" size={14} className="picker-row-icon-muted" />
          <span className="menu-row-label">Current checkout</span>
        </MenuRowNav>
        <MenuRowNav
          fadeKey="newWorktree"
          highlighted={cursor === "newWorktree" && checkout !== "newWorktree"}
          selected={checkout === "newWorktree"}
          onMouseEnter={() => setCursor("newWorktree")}
          onClick={() => onPick("newWorktree")}
        >
          <Icon name="folderWithFiles" size={14} className="picker-row-icon-muted" />
          <span className="menu-row-label">New worktree</span>
        </MenuRowNav>
      </div>
    </PopoverCard>
  );
}

// ---------------------------------------------------------------------------
// The ref (branch) popover (pickers.rs:2939-3069) — width 320
// ---------------------------------------------------------------------------

interface RefsState {
  readonly rows: readonly RepoRef[];
  readonly loading: boolean;
  readonly error: string | null;
}

function RefChip({
  session,
  repoPath,
  currentBranch,
  draftBranch,
  checkout,
  targetDeviceId,
  canPick,
  onPick,
  onRefs,
}: {
  readonly session: ReturnType<typeof useEngineSession>;
  readonly repoPath: string | null;
  readonly currentBranch: string | null;
  readonly draftBranch: string | null;
  readonly checkout: CheckoutKind;
  readonly targetDeviceId: string | null;
  readonly canPick: boolean;
  readonly onPick: (name: string) => void;
  readonly onRefs: (rows: readonly RepoRef[]) => void;
}) {
  const chipRef = useRef<HTMLButtonElement | null>(null);
  const popup = usePopup<"branch">();
  const [refs, setRefs] = useState<RefsState>({ rows: [], loading: false, error: null });
  const [switching, setSwitching] = useState<string | null>(null);
  const [switchError, setSwitchError] = useState<string | null>(null);

  const loadRefs = useCallback(
    async (force: boolean): Promise<void> => {
      if (session === null || repoPath === null) {
        return;
      }
      if (refs.loading || (refs.rows.length > 0 && !force)) {
        return;
      }
      setRefs((current) => ({ ...current, loading: true, error: null }));
      try {
        const params: Record<string, unknown> = { repoPath };
        if (targetDeviceId !== null) {
          params.targetDeviceId = targetDeviceId;
        }
        const rows = await session.client.call<RepoRef[]>(methods.LIST_REFS, params);
        const list = Array.isArray(rows) ? rows : [];
        setRefs({ rows: list, loading: false, error: null });
        onRefs(list);
      } catch (error) {
        setRefs({ rows: [], loading: false, error: error instanceof Error ? error.message : String(error) });
        onRefs([]);
      }
    },
    [session, repoPath, refs.loading, refs.rows.length, targetDeviceId, onRefs],
  );

  // Every open force-reloads refs and clears any stale switch error
  // (toggle steps 7-8).
  useEffect(() => {
    if (popup.isOpen()) {
      setSwitchError(null);
      void loadRefs(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [popup.isOpen()]);

  const picked = draftBranch ?? currentBranch;
  const label = refLabel(picked, checkout);

  async function pickRef(row: RepoRef): Promise<void> {
    // Refs are fixed at creation: a committed chat never moves.
    if (!canPick) {
      return;
    }
    if (row.worktreePath !== null && row.worktreePath !== undefined) {
      // Reuse the ref's existing worktree ("Current worktree").
      onPick(row.name);
      popup.dismiss();
      return;
    }
    if (checkout === "newWorktree" || row.current) {
      onPick(row.name);
      popup.dismiss();
      return;
    }
    // Local mode + a plain non-current ref: CHECK OUT the space folder via
    // SwitchRef; success records the pick, closes, and force-refreshes
    // refs; failure keeps the popover open with git's verbatim message.
    // One switch at a time (pickers.rs:1309-1357).
    if (session === null || repoPath === null || switching !== null) {
      return;
    }
    setSwitching(row.name);
    setSwitchError(null);
    try {
      const params: Record<string, unknown> = { repoPath, refName: row.name };
      if (targetDeviceId !== null) {
        params.targetDeviceId = targetDeviceId;
      }
      await session.client.call(methods.SWITCH_REF, params);
      onPick(row.name);
      popup.dismiss();
      void loadRefs(true);
    } catch (error) {
      setSwitchError(error instanceof Error ? error.message : String(error));
    } finally {
      setSwitching(null);
    }
  }

  return (
    <>
      <FooterChip
        id="picker-branch"
        ref={chipRef}
        icon="gitBranch"
        label={label}
        open={popup.get() !== null}
        title={label}
        onPointerDown={() => popup.noteTriggerPress()}
        onClick={() => {
          if (popup.takePressWasOpen()) {
            return;
          }
          popup.open("branch");
        }}
      />
      <Popup popup={popup} placement={(size) => placeAbove(chipRef, size)}>
        {() => (
          <BranchCard
            popup={popup}
            refs={refs}
            repoPath={repoPath}
            switching={switching}
            switchError={switchError}
            picked={picked}
            onRetry={() => void loadRefs(true)}
            onPick={(row) => void pickRef(row)}
          />
        )}
      </Popup>
    </>
  );
}

function BranchCard({
  popup,
  refs,
  repoPath,
  switching,
  switchError,
  picked,
  onRetry,
  onPick,
}: {
  readonly popup: ReturnType<typeof usePopup<"branch">>;
  readonly refs: RefsState;
  readonly repoPath: string | null;
  readonly switching: string | null;
  readonly switchError: string | null;
  readonly picked: string | null;
  readonly onRetry: () => void;
  readonly onPick: (row: RepoRef) => void;
}) {
  const [query, setQuery] = useState("");
  const [cursor, setCursor] = useState(0);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);

  const filtered = useMemo(() => {
    const names = refs.rows.map((row) => row.name);
    return filterIndices(query, names).map((ix) => refs.rows[ix]!).slice(0, MAX_REF_ROWS);
  }, [refs.rows, query]);

  const count = Math.min(refs.rows.length, MAX_REF_ROWS);

  useEffect(() => {
    if (popup.isOpen()) {
      setQuery("");
      // Branch → the current ref's row, capped to 299 (toggle step 5).
      const target = picked === null ? 0 : filtered.findIndex((row) => row.name === picked);
      setCursor(Math.min(target < 0 ? 0 : target, Math.max(0, MAX_REF_ROWS - 1)));
      inputRef.current?.focus();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [popup.isOpen()]);

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>): void => {
    if (popup.asOpen() === null) {
      return;
    }
    const key = classifyKey(event.key, event.metaKey, event.ctrlKey);
    if (key === "down" || key === "up") {
      event.preventDefault();
      setCursor((current) => menuStep(current, count, key === "down" ? 1 : -1) ?? 0);
      return;
    }
    if (key === "enter" || key === "mod-enter") {
      event.preventDefault();
      const row = filtered[cursor];
      if (row !== undefined) {
        onPick(row);
      }
      return;
    }
    if (key === "escape") {
      event.preventDefault();
      popup.closeByEscape();
    }
  };

  useEffect(() => {
    const row = listRef.current?.querySelector<HTMLElement>(`[data-ref-index="${cursor}"]`);
    row?.scrollIntoView({ block: "nearest" });
  }, [cursor, filtered.length]);

  return (
    <PopoverCard role="dialog" aria-label="Ref" style={{ width: 320 }} onKeyDown={onKeyDown}>
      <SearchInputFrame>
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setCursor(0);
          }}
          placeholder="Search refs…"
          spellCheck={false}
          autoComplete="off"
          aria-label="Search refs"
        />
      </SearchInputFrame>
      {repoPath === null ? (
        <div className="picker-empty-note">No project selected</div>
      ) : refs.loading ? (
        <div id="branch-skeleton">
          <SkeletonRows count={4} />
        </div>
      ) : refs.error !== null ? (
        <ErrorRow message={refs.error} onRetry={onRetry} />
      ) : filtered.length === 0 ? (
        <div className="picker-empty-note">No refs found.</div>
      ) : (
        <div className="picker-list" ref={listRef}>
          {filtered.map((row, ix) => (
            <MenuRowNav
              key={row.name}
              fadeKey={row.name}
              data-ref-index={ix}
              highlighted={ix === cursor}
              selected={picked === row.name}
              onClick={() => onPick(row)}
            >
              <span className="menu-row-label">{row.name}</span>
              {switching === row.name && <span className="picker-row-switching">switching…</span>}
              {row.current ? (
                <span className="picker-row-tag">current</span>
              ) : row.worktreePath !== null && row.worktreePath !== undefined ? (
                <span className="picker-row-tag">worktree</span>
              ) : null}
            </MenuRowNav>
          ))}
        </div>
      )}
      {switchError !== null && (
        <div className="picker-trailing-error" role="alert">
          {switchError}
        </div>
      )}
      {refs.rows.length > MAX_REF_ROWS && (
        <div className="picker-trailing-note">
          {`Showing ${Math.min(refs.rows.length, MAX_REF_ROWS)} of ${refs.rows.length} refs`}
        </div>
      )}
    </PopoverCard>
  );
}

/** `ref_label` (pickers.rs:1748): "Select ref" | "From {name}" | the bare name. */
function refLabel(picked: string | null, checkout: CheckoutKind): string {
  if (picked === null) {
    return "Select ref";
  }
  return checkout === "newWorktree" ? `From ${picked}` : picked;
}

// ---------------------------------------------------------------------------
// Shared placement helpers
// ---------------------------------------------------------------------------

function placeAbove(
  chipRef: React.RefObject<HTMLButtonElement | null>,
  size: { width: number; height: number },
): CSSProperties {
  const rect = chipRef.current?.getBoundingClientRect();
  if (rect === undefined) {
    return { left: 8, top: 8 };
  }
  return anchorAbove(rect, size);
}

function placeAboveEnd(
  chipRef: React.RefObject<HTMLButtonElement | null>,
  size: { width: number; height: number },
): CSSProperties {
  const rect = chipRef.current?.getBoundingClientRect();
  if (rect === undefined) {
    return { left: 8, top: 8 };
  }
  return anchorAboveEnd(rect, size);
}
