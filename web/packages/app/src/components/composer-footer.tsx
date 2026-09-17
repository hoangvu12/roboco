import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Icon, type IconName } from "@roboco/icons";
import { methods } from "@roboco/engine-client";
import type { ChangeRequestSummary, ContextUsage, Device, RepoRef, Space } from "@roboco/proto";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { deviceOnline, spaceDisplayName, spacesSorted } from "../lib/view";
import { classifyKey, filterIndices, menuStep } from "../lib/picker-search";
import { addSpaceStore } from "../state/add-space";
import { composerDefaults, rememberTarget } from "../lib/composer-draft";
import { ContextUsageIndicator } from "./context-usage";
import { ChangeRequestBadge } from "./change-request-badge";
import { createRbPopoverHandle, RbPopover, RbPopoverTrigger } from "./base/popover";
import { SearchInputFrame } from "./popover/menu";
import { MenuRowNav } from "./popover/menu-row";
import { ErrorRow, SkeletonRows } from "./popover/skeleton";

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
 *
 * Each chip's popover rides `RbPopover` + `RbPopoverTrigger` (the trigger's
 * `trigger-press` reason replaces the old noteTriggerPress dance; pressing
 * another chip dismisses the first popover and opens that chip's own — the
 * four-chip switching behavior). All four register the
 * `composer-pickers` overlayKeyboard source while open, keeping session-nav
 * shortcuts quiet under any of them — the desktop's
 * `composer.pickers().is_open()` covers the footer pickers too
 * (shell.rs:3681-3683).
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

interface FooterChipProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  readonly id: string;
  readonly icon: IconName;
  readonly label: string;
  readonly open: boolean;
  readonly offline?: boolean;
  readonly title: string;
}

/**
 * The small ghost dropdown chip: 20px tall, 6px radius, 12px medium; the wash
 * is quiet until hovered, open holding the hover fill (snapped, no fade).
 * The offline device chip overrides its text to `warning @ 0.8`.
 *
 * Rendered through `RbPopoverTrigger`'s `render` prop, which merges the
 * trigger's toggling/ARIA props onto this element — so the extra props
 * spread onto the button.
 */
export function FooterChip(props: FooterChipProps) {
  const { id, icon, label, open, offline, title, className, ...rest } = props;
  return (
    <button
      type="button"
      id={id}
      className={`footer-menu-chip ${open ? "footer-menu-chip-open" : ""} ${
        offline === true ? "footer-menu-chip-offline" : ""
      } ${className ?? ""}`}
      title={title}
      {...rest}
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
  const [open, setOpen] = useState(false);
  const [popoverHandle] = useState(() => createRbPopoverHandle());

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
      <RbPopoverTrigger
        handle={popoverHandle}
        render={
          <FooterChip
            id="picker-device"
            icon="monitor"
            label={label}
            open={open}
            offline={offline}
            title={label}
          />
        }
      />
      <RbPopover
        handle={popoverHandle}
        open={open}
        onOpenChange={setOpen}
        placement="anchorAbove"
        role="dialog"
        ariaLabel="Devices"
        style={{ width: 224 }}
        overlaySource="composer-pickers"
      >
        <DeviceCard
          open={open}
          onClose={() => setOpen(false)}
          rows={rows}
          ownDeviceId={ownDeviceId}
          effectiveDeviceId={effectiveDevice?.id ?? null}
          now={now}
        />
      </RbPopover>
    </>
  );
}

function DeviceCard({
  open,
  onClose,
  rows,
  ownDeviceId,
  effectiveDeviceId,
  now,
}: {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly rows: readonly Device[];
  readonly ownDeviceId: string | null;
  readonly effectiveDeviceId: string | null;
  readonly now: number;
}) {
  const [query, setQuery] = useState("");
  const [cursor, setCursor] = useState(0);
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (open) {
      setQuery("");
      // Device → the effective device's index, else 0.
      const target = rows.findIndex((device) => device.id === effectiveDeviceId);
      setCursor(target < 0 ? 0 : target);
      inputRef.current?.focus();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const names = rows.map((device) => device.name);
  const filtered = filterIndices(query, names).map((ix) => rows[ix]!);

  function pick(device: Device): void {
    const snapshot = composerDefaults.getSnapshot();
    rememberTarget(device.id, snapshot.project, snapshot.noProject);
    onClose();
  }

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>): void => {
    if (!open) {
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
    }
  };

  return (
    <div className="picker-key-frame" onKeyDown={onKeyDown}>
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
    </div>
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
  const [open, setOpen] = useState(false);
  const [popoverHandle] = useState(() => createRbPopoverHandle());

  const pickedSpace = currentSpaceId === null ? null : spaces.find((space) => space.id === currentSpaceId) ?? null;
  const label = pickedSpace === null ? "All projects" : spaceDisplayName(pickedSpace);

  return (
    <>
      <RbPopoverTrigger
        handle={popoverHandle}
        render={<FooterChip id="picker-project" icon="folder" label={label} open={open} title={label} />}
      />
      <RbPopover
        handle={popoverHandle}
        open={open}
        onOpenChange={setOpen}
        placement="anchorAboveEnd"
        role="dialog"
        ariaLabel="Project"
        style={{ width: 280 }}
        overlaySource="composer-pickers"
      >
        <ProjectCard open={open} onClose={() => setOpen(false)} spaces={spaces} currentSpaceId={currentSpaceId} />
      </RbPopover>
    </>
  );
}

function ProjectCard({
  open,
  onClose,
  spaces,
  currentSpaceId,
}: {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly spaces: readonly Space[];
  readonly currentSpaceId: string | null;
}) {
  const [query, setQuery] = useState("");
  const [cursor, setCursor] = useState(0);
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (open) {
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
  }, [open]);

  const labels = spaces.map((space) => spaceDisplayName(space));
  const filtered = filterIndices(query, labels).map((ix) => spaces[ix]!);

  function pickSpace(space: Space): void {
    const snapshot = composerDefaults.getSnapshot();
    rememberTarget(snapshot.device, space.id, false);
    onClose();
  }

  function pickNoProject(): void {
    const snapshot = composerDefaults.getSnapshot();
    rememberTarget(snapshot.device, null, true);
    onClose();
  }

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>): void => {
    if (!open) {
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
    }
  };

  return (
    <div className="picker-key-frame" onKeyDown={onKeyDown}>
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
          onClose();
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
    </div>
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
  const [open, setOpen] = useState(false);
  const [popoverHandle] = useState(() => createRbPopoverHandle());

  // `checkout_label` (pickers.rs:1280-1304): "New worktree" |
  // "Current worktree" when the picked ref has an existing worktree, else
  // "Current checkout".
  const label =
    checkout === "newWorktree" ? "New worktree" : pickedRefHasWorktree ? "Current worktree" : "Current checkout";

  return (
    <>
      <RbPopoverTrigger
        handle={popoverHandle}
        render={
          <FooterChip
            id="picker-checkout"
            icon={checkout === "newWorktree" || pickedRefHasWorktree ? "folderWithFiles" : "folder"}
            label={label}
            open={open}
            title={label}
          />
        }
      />
      <RbPopover
        handle={popoverHandle}
        open={open}
        onOpenChange={setOpen}
        placement="anchorAbove"
        role="dialog"
        ariaLabel="Checkout kind"
        style={{ width: 224 }}
        overlaySource="composer-pickers"
        // No search input here — the card never moved focus on open, and the
        // default would land it on the first row; `false` keeps focus put.
        initialFocus={false}
      >
        <CheckoutCard open={open} onClose={() => setOpen(false)} checkout={checkout} onPick={onPick} />
      </RbPopover>
    </>
  );
}

function CheckoutCard({
  open,
  onClose,
  checkout,
  onPick,
}: {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly checkout: CheckoutKind;
  readonly onPick: (kind: CheckoutKind) => void;
}) {
  const [cursor, setCursor] = useState<CheckoutKind>(checkout);

  function pick(kind: CheckoutKind): void {
    onPick(kind);
    onClose();
  }

  // Enter picks the highlighted kind; ↑/↓ walk the two rows (toggle step 5:
  // Checkout anchors on 0 or 1).
  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>): void => {
    if (!open) {
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
      pick(cursor);
    }
  };

  return (
    <div className="picker-list picker-list-plain" onKeyDown={onKeyDown}>
      <MenuRowNav
        fadeKey="local"
        highlighted={cursor === "local" && checkout !== "local"}
        selected={checkout === "local"}
        onMouseEnter={() => setCursor("local")}
        onClick={() => pick("local")}
      >
        <Icon name="folder" size={14} className="picker-row-icon-muted" />
        <span className="menu-row-label">Current checkout</span>
      </MenuRowNav>
      <MenuRowNav
        fadeKey="newWorktree"
        highlighted={cursor === "newWorktree" && checkout !== "newWorktree"}
        selected={checkout === "newWorktree"}
        onMouseEnter={() => setCursor("newWorktree")}
        onClick={() => pick("newWorktree")}
      >
        <Icon name="folderWithFiles" size={14} className="picker-row-icon-muted" />
        <span className="menu-row-label">New worktree</span>
      </MenuRowNav>
    </div>
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
  const [open, setOpen] = useState(false);
  const [popoverHandle] = useState(() => createRbPopoverHandle());
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
    if (open) {
      setSwitchError(null);
      void loadRefs(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

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
      setOpen(false);
      return;
    }
    if (checkout === "newWorktree" || row.current) {
      onPick(row.name);
      setOpen(false);
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
      setOpen(false);
      void loadRefs(true);
    } catch (error) {
      setSwitchError(error instanceof Error ? error.message : String(error));
    } finally {
      setSwitching(null);
    }
  }

  return (
    <>
      <RbPopoverTrigger
        handle={popoverHandle}
        render={<FooterChip id="picker-branch" icon="gitBranch" label={label} open={open} title={label} />}
      />
      <RbPopover
        handle={popoverHandle}
        open={open}
        onOpenChange={setOpen}
        placement="anchorAbove"
        role="dialog"
        ariaLabel="Ref"
        style={{ width: 320 }}
        overlaySource="composer-pickers"
      >
        <BranchCard
          open={open}
          onClose={() => setOpen(false)}
          refs={refs}
          repoPath={repoPath}
          switching={switching}
          switchError={switchError}
          picked={picked}
          onRetry={() => void loadRefs(true)}
          onPick={(row) => void pickRef(row)}
        />
      </RbPopover>
    </>
  );
}

function BranchCard({
  open,
  onClose,
  refs,
  repoPath,
  switching,
  switchError,
  picked,
  onRetry,
  onPick,
}: {
  readonly open: boolean;
  readonly onClose: () => void;
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
    if (open) {
      setQuery("");
      // Branch → the current ref's row, capped to 299 (toggle step 5).
      const target = picked === null ? 0 : filtered.findIndex((row) => row.name === picked);
      setCursor(Math.min(target < 0 ? 0 : target, Math.max(0, MAX_REF_ROWS - 1)));
      inputRef.current?.focus();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>): void => {
    if (!open) {
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
    }
  };

  useEffect(() => {
    const row = listRef.current?.querySelector<HTMLElement>(`[data-ref-index="${cursor}"]`);
    row?.scrollIntoView({ block: "nearest" });
  }, [cursor, filtered.length]);

  return (
    <div className="picker-key-frame" onKeyDown={onKeyDown}>
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
    </div>
  );
}

/** `ref_label` (pickers.rs:1748): "Select ref" | "From {name}" | the bare name. */
function refLabel(picked: string | null, checkout: CheckoutKind): string {
  if (picked === null) {
    return "Select ref";
  }
  return checkout === "newWorktree" ? `From ${picked}` : picked;
}
