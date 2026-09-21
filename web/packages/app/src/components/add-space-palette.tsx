import { useEffect, useRef } from "react";
import type { ReactNode, RefObject } from "react";
import { useNavigate } from "@tanstack/react-router";
import { Icon, type IconName } from "@roboco/icons";
import type { Device, DriveEntry, FolderEntry } from "@roboco/proto";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { deviceOnline } from "../lib/view";
import {
  activeLocation,
  addSpaceCompletion,
  breadcrumbs,
  childPath,
  crumbFold,
  filteredFolders,
} from "../lib/add-space";
import { addSpaceStore, useAddSpaceSnapshot, type AddSpaceFlow } from "../state/add-space";
import { ESCAPE_PRIORITY, registerEscapeSurface } from "../state/escape";
import { RbDialogGlass } from "./base/dialog";
import { KeyHint, KeyHintPair, KeyHintText } from "./ui/KeyHint";
import { MenuRowNav } from "./ui/MenuRows";
import { ErrorRow, SkeletonRows } from "./ui/Skeleton";

/**
 * The add-space palette — the ⌘K-style "New project" surface, port of the
 * desktop's `render_add_space_overlay` (`spaces.rs:2526-3274`): a 680px
 * `palette_card` (its own 14px radius, deliberately NOT the generic 12px)
 * centered on the lighter 0.35 `modal_glass` scrim. Input row → body
 * (breadcrumbs + folder list beside the devices/locations rail) → footer.
 *
 * The mount lifecycle rides `RbDialogGlass`: the store's `open` flag drives
 * the dialog, scrim presses and the escape ladder close through
 * `addSpaceStore.close()`, and the 100ms `[data-closed]` layer fade IS the
 * exit window — `unmounted()` fires when Base UI's animation-aware unmount
 * drains, dropping the flow. Headless while closed — the state machine
 * lives in `state/add-space.ts` (`addSpaceStore`); ticket 10's spaces-menu
 * row and ticket 12's `Mod+K` binding call `open()`. Escape resolves on the
 * shell's capture ladder at the reserved `addSpace` priority, so one
 * keystroke can never reach two handlers.
 */

/**
 * The Devices-page platform mapping (settings::devices) — LAPTOP for
 * macos/darwin, GLOBAL for web, SMARTPHONE for ios/android, MONITOR
 * otherwise.
 */
export function devicePlatformIcon(platform: string): IconName {
  switch (platform) {
    case "macos":
    case "darwin":
      return "laptop";
    case "web":
      return "global";
    case "ios":
    case "android":
      return "smartphone";
    default:
      return "monitor";
  }
}

export function AddSpacePalette() {
  const session = useEngineSession();
  const snapshot = useWatchSnapshot(session);
  const now = useNow(30_000);
  const state = useAddSpaceSnapshot();
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);

  // The store is module-level; the mounted card is its window onto the
  // active session. An engine switch remounts the sidebar (AppShell keys
  // it), which re-attaches here and closes any open palette. The canvas
  // hop rides a ref so the binding only re-runs when the session does.
  const goToCanvasRef = useRef(() => {
    void navigate({ to: "/" });
  });
  goToCanvasRef.current = () => {
    void navigate({ to: "/" });
  };
  useEffect(() => {
    addSpaceStore.attach({
      session,
      goToCanvas: () => {
        goToCanvasRef.current();
      },
    });
    return () => {
      // The host is unmounting — nothing is left to paint, so no exit.
      addSpaceStore.forceClose();
    };
  }, [session]);

  // The shell's Escape ladder owns Escape at the reserved addSpace
  // priority — one capture-phase handler, so the focused input's own
  // keydown never sees the key (no double close).
  useEffect(() => {
    if (state.status === "closed") {
      return;
    }
    return registerEscapeSurface(ESCAPE_PRIORITY.addSpace, () => {
      if (state.status === "open") {
        addSpaceStore.close();
      }
      // Consumed either way — closing still counts; a second Escape in the
      // exit window must not fall through to the chat interrupt.
      return true;
    });
  }, [state.status]);

  // `focus_pending`: the search input takes focus on open.
  useEffect(() => {
    if (state.status === "open") {
      inputRef.current?.focus({ preventScroll: true });
    }
  }, [state.status]);

  const flow = state.flow;
  if (state.status === "closed" || flow === null) {
    return null;
  }

  const listing =
    typeof flow.listing === "object" && "entries" in flow.listing ? flow.listing : null;
  const loadError = typeof flow.listing === "object" && "error" in flow.listing ? flow.listing.error : null;
  const loading = !listing && loadError === null;
  const rows = listing !== null ? filteredFolders(listing.entries, flow.query) : [];
  const completion = listing !== null ? addSpaceCompletion(rows, flow.active, flow.query) : null;

  const devices = snapshot?.devices.rows ?? [];
  const device = flow.deviceId !== null ? devices.find((row) => row.id === flow.deviceId) ?? null : null;
  const deviceName = device?.name ?? "This device";

  // The rail's active Locations row + the drive whose mount folds into a
  // breadcrumb (the System "/" drive never folds — plain crumbs then).
  const activeLoc = activeLocation(listing?.path ?? null, flow.home, flow.drives);
  const activeDrive = activeLoc !== null && activeLoc.kind === "drive" ? flow.drives[activeLoc.index] : undefined;
  const driveMount = activeDrive !== undefined ? activeDrive.path.replace(/\/+$/, "") : null;
  const foldDrive = driveMount !== null && driveMount.length > 0 ? driveMount : null;

  // The scrim press is Base UI's dismissal now (modal Dialog, pointer
  // dismissal on — the `modal_glass` contract); `overlayOpen` holds the
  // keyboard claim through the exit window (the scrim is still up while the
  // card fades).
  return (
    <RbDialogGlass
      open={state.status === "open"}
      onOpenChange={(next) => {
        if (!next) {
          addSpaceStore.close();
        }
      }}
      onOpenChangeComplete={(next) => {
        if (!next) {
          addSpaceStore.unmounted();
        }
      }}
      ariaLabel="New project"
      overlaySource="add-space"
      // The component renders through the exit window ("closing"), so the
      // claim holds until the layer is truly gone — a jump firing under a
      // still-visible scrim would strand it (ticket 11's comment).
      overlayOpen
      backdropClassName="add-space-backdrop"
      cardClassName="add-space-frost"
    >
      <div className="add-space-card">
        <InputRow
          flow={flow}
          completion={completion}
          listingReady={listing !== null}
          inputRef={inputRef}
        />
        <Body
          flow={flow}
          listing={listing}
          loadError={loadError}
          loading={loading}
          rows={rows}
          device={device}
          devices={devices}
          now={now}
          foldDrive={foldDrive}
          listRef={listRef}
        />
        <Footer error={flow.error} />
      </div>
    </RbDialogGlass>
  );
}

// ── Input row ────────────────────────────────────────────────────────────

interface InputRowProps {
  readonly flow: AddSpaceFlow;
  readonly completion: { name: string; suffix: string } | null;
  readonly listingReady: boolean;
  readonly inputRef: React.RefObject<HTMLInputElement | null>;
}

/**
 * The ⌘K bar (spaces.rs:2641-2734): summon chip · search input (with the
 * ⇥ ghost suffix) · the ⌘Enter add chip · esc. The recessed band tone and
 * the 1px hairline frame the folder list, which stays on the brighter
 * card tint.
 */
function InputRow(props: InputRowProps) {
  const { flow, completion, listingReady, inputRef } = props;
  const manualMissing = flow.manualPath !== null && !flow.manualPath.exists;
  const dim = flow.submitBusy || (!listingReady && flow.manualPath === null);
  return (
    <div className="add-space-input-row">
      <span className="add-space-chip" aria-hidden>
        <Icon name="command" size={11} className="add-space-chip-icon" />
        <span className="add-space-chip-text">K</span>
      </span>
      <div className="add-space-search">
        {completion !== null && (
          <span className="add-space-ghost" aria-hidden>
            <span className="add-space-ghost-query">{flow.query}</span>
            <span className="add-space-ghost-suffix">{completion.suffix}</span>
          </span>
        )}
        <input
          ref={inputRef}
          type="text"
          value={flow.query}
          placeholder="Search folders…"
          spellCheck={false}
          autoComplete="off"
          autoCorrect="off"
          onChange={(event) => {
            addSpaceStore.setQuery(event.target.value);
          }}
          onKeyDown={(event) => {
            // The desktop's "PaletteSearch" context leaves the navigation
            // keys unbound so they bubble to the card's handler.
            if (addSpaceStore.keyDown(event.nativeEvent)) {
              event.preventDefault();
            }
          }}
        />
      </div>
      <button
        type="button"
        className="add-space-submit"
        data-rb-dim={dim ? "" : undefined}
        onClick={() => {
          addSpaceStore.submit();
        }}
      >
        {flow.submitBusy ? (
          <span>Adding…</span>
        ) : (
          <>
            <Icon name="command" size={11} className="add-space-submit-icon" />
            <span>{manualMissing ? "Create and add" : "Enter"}</span>
          </>
        )}
      </button>
      <button
        type="button"
        className="add-space-chip add-space-chip-click"
        onClick={() => {
          addSpaceStore.close();
        }}
      >
        <span className="add-space-chip-text">esc</span>
      </button>
    </div>
  );
}

// ── Body: crumbs + folder list beside the rail ───────────────────────────

interface BodyProps {
  readonly flow: AddSpaceFlow;
  readonly listing: { path: string; entries: FolderEntry[]; truncated: boolean } | null;
  readonly loadError: string | null;
  readonly loading: boolean;
  readonly rows: readonly FolderEntry[];
  readonly device: Device | null;
  readonly devices: readonly Device[];
  readonly now: number;
  readonly foldDrive: string | null;
  readonly listRef: RefObject<HTMLDivElement | null>;
}

/**
 * The fixed 330px body — sparse folders, skeletons, and device switches
 * never resize the card; the list fills and scrolls.
 */
function Body(props: BodyProps) {
  const { flow, listing, loadError, loading, rows, device, devices, now, foldDrive, listRef } = props;
  return (
    <div className="add-space-body">
      <div className="add-space-main">
        <Crumbs flow={flow} listing={listing} deviceName={device?.name ?? "This device"} foldDrive={foldDrive} />
        <FolderList
          flow={flow}
          listing={listing}
          loadError={loadError}
          loading={loading}
          rows={rows}
          deviceName={device?.name ?? "This device"}
          listRef={listRef}
        />
      </div>
      <Rail flow={flow} listing={listing} deviceName={device?.name ?? "This device"} devices={devices} now={now} />
    </div>
  );
}

/**
 * Breadcrumbs ("MacBook Pro / Projects / roboco") — the quiet mono path
 * voice. The device crumb stands in for everything up to home; a drive's
 * mount folds the same way into a crumb named after the drive. The last
 * crumb is the current folder and never clickable.
 */
function Crumbs(props: {
  readonly flow: AddSpaceFlow;
  readonly listing: BodyProps["listing"];
  readonly deviceName: string;
  readonly foldDrive: string | null;
}) {
  const { flow, listing, deviceName, foldDrive } = props;
  if (listing === null) {
    return <div className="add-space-crumbs-empty" />;
  }
  const segments = breadcrumbs(listing.path);
  const last = segments.length - 1;
  const atHome = flow.home === listing.path;
  const atMount = foldDrive !== null && listing.path.replace(/\/+$/, "") === foldDrive;
  const folded = crumbFold(listing.path, flow.home, foldDrive);
  return (
    <nav className="add-space-crumbs" aria-label="Folder path">
      {atHome ? (
        <span className="add-space-crumb add-space-crumb-current">{deviceName}</span>
      ) : (
        <button
          type="button"
          className="add-space-crumb add-space-crumb-click"
          onClick={() => {
            addSpaceStore.browse(null);
          }}
        >
          {deviceName}
        </button>
      )}
      {foldDrive !== null && (
        <>
          <CrumbSeparator />
          {atMount ? (
            <span className="add-space-crumb add-space-crumb-current">{driveName(flow, foldDrive)}</span>
          ) : (
            <button
              type="button"
              className="add-space-crumb add-space-crumb-click"
              onClick={() => {
                addSpaceStore.gotoLocation(foldDrive);
              }}
            >
              {driveName(flow, foldDrive)}
            </button>
          )}
        </>
      )}
      {segments.slice(folded).map(([label, full], ix) => {
        const isLast = ix + folded === last;
        return (
          <span key={full} className="add-space-crumb-pair">
            <CrumbSeparator />
            {isLast ? (
              <span className="add-space-crumb add-space-crumb-current">{label}</span>
            ) : (
              <button
                type="button"
                className="add-space-crumb add-space-crumb-click"
                onClick={() => {
                  addSpaceStore.browse(full);
                }}
              >
                {label}
              </button>
            )}
          </span>
        );
      })}
    </nav>
  );
}

function driveName(flow: AddSpaceFlow, mount: string): string {
  const drive = flow.drives.find((row) => row.path.replace(/\/+$/, "") === mount);
  return drive?.name ?? mount;
}

function CrumbSeparator() {
  return <span className="add-space-crumb-sep">/</span>;
}

/**
 * The folder list: skeletons while loading, the error row + Retry on
 * failure, the empty hints, or the nav-style folder rows (repo rows carry
 * the trailing git-branch glyph — the row you're usually hunting for
 * announces itself).
 */
function FolderList(props: {
  readonly flow: AddSpaceFlow;
  readonly listing: BodyProps["listing"];
  readonly loadError: string | null;
  readonly loading: boolean;
  readonly rows: BodyProps["rows"];
  readonly deviceName: string;
  readonly listRef: BodyProps["listRef"];
}) {
  const { flow, listing, loadError, loading, rows, deviceName, listRef } = props;

  // Every browse starts the list at the top (the desktop resets
  // `list_scroll`), and keyboard navigation keeps the highlighted row in
  // view (`scroll_to_item` — the rows are the list's direct children).
  useEffect(() => {
    listRef.current?.scrollTo({ top: 0 });
  }, [listing?.path, listRef]);
  useEffect(() => {
    const row = listRef.current?.children.item(flow.active);
    row?.scrollIntoView({ block: "nearest" });
  }, [flow.active, listRef]);

  if (loading) {
    return (
      <div className="add-space-list-state">
        <SkeletonRows count={6} />
      </div>
    );
  }
  if (loadError !== null) {
    // Folder-level failures show as themselves; transport-shaped failures
    // read as the device being unreachable.
    const message = loadError.includes("folder")
      ? loadError
      : `${deviceName} didn't respond — is it online?`;
    return (
      <div className="add-space-list-error">
        <ErrorRow
          message={message}
          onRetry={() => {
            addSpaceStore.retryLoad();
          }}
        />
      </div>
    );
  }
  if (rows.length === 0) {
    return (
      <div className="add-space-list-empty">{flow.query.length === 0 ? "No folders here" : "No folders match"}</div>
    );
  }
  const base = listing?.path ?? "";
  return (
    <div className="add-space-list-wrap">
      <div className="add-space-list" ref={listRef}>
        {rows.map((entry, ix) => (
          <MenuRowNav
            key={entry.name}
            fadeKey={`add-space-folder-${ix}`}
            highlighted={ix === flow.active}
            onClick={() => {
              addSpaceStore.descend(childPath(base, entry.name), entry.isRepo);
            }}
          >
            <Icon name="folder" size={15} className="add-space-folder-icon" />
            <span className="add-space-folder-name">{entry.name}</span>
            {entry.isRepo && <Icon name="gitBranch" size={13} className="add-space-repo-icon" />}
          </MenuRowNav>
        ))}
      </div>
    </div>
  );
}

// ── The devices + locations rail ─────────────────────────────────────────

/**
 * The right rail: Devices (platform glyph + name + presence dot) over
 * Locations (home + the browsed device's mounted drives), an info line
 * naming the browsed device. Clicking a device or a location rebrowses
 * the same card in place.
 */
function Rail(props: {
  readonly flow: AddSpaceFlow;
  readonly listing: BodyProps["listing"];
  readonly deviceName: string;
  readonly devices: readonly Device[];
  readonly now: number;
}) {
  const { flow, listing, deviceName, devices, now } = props;
  const activeLoc = activeLocation(listing?.path ?? null, flow.home, flow.drives);
  const hasLocations = flow.deviceId !== null;
  return (
    <div className="add-space-rail">
      <div className="add-space-rail-label add-space-rail-label-first">Devices</div>
      {devices.map((device) => (
        <button
          key={device.id}
          type="button"
          className={`add-space-rail-row ${device.id === flow.deviceId ? "add-space-rail-row-active" : ""}`}
          onClick={() => {
            addSpaceStore.pickDevice(device.id);
          }}
        >
          <Icon name={devicePlatformIcon(device.platform)} size={14} className="add-space-rail-icon" />
          <span className="add-space-rail-name">{device.name}</span>
          <span
            className={`add-space-presence ${deviceOnline(device, now) ? "add-space-presence-online" : ""}`}
          />
        </button>
      ))}
      {hasLocations && (
        <>
          <div className="add-space-rail-divider" />
          <div className="add-space-rail-label">Locations</div>
          <button
            type="button"
            className={`add-space-rail-row ${activeLoc !== null && activeLoc.kind === "home" ? "add-space-rail-row-active" : ""}`}
            onClick={() => {
              addSpaceStore.gotoLocation(null);
            }}
          >
            <Icon name="home" size={14} className="add-space-rail-icon" />
            <span className="add-space-rail-name">Home</span>
          </button>
          {flow.drives.map((drive: DriveEntry, ix: number) => (
            <button
              key={drive.path}
              type="button"
              className={`add-space-rail-row ${activeLoc !== null && activeLoc.kind === "drive" && activeLoc.index === ix ? "add-space-rail-row-active" : ""}`}
              onClick={() => {
                addSpaceStore.gotoLocation(drive.path);
              }}
            >
              <Icon name="hardDrive" size={14} className="add-space-rail-icon" />
              <span className="add-space-rail-name">{drive.name}</span>
            </button>
          ))}
        </>
      )}
      <div className="add-space-rail-divider" />
      <div className="add-space-rail-info">
        <Icon name="infoCircle" size={12} className="add-space-rail-info-icon" />
        <span className="add-space-rail-info-text">Showing folders from {deviceName} only</span>
      </div>
    </div>
  );
}

// ── Footer ───────────────────────────────────────────────────────────────

/** The key-hint legend + the inline error line (spaces.rs:3212-3243). */
function Footer(props: { error: string | null }) {
  return (
    <div className="add-space-footer">
      <KeyHintPair first={<Icon name="arrowUp" />} second={<Icon name="arrowDown" />} label="Navigate" />
      <KeyHint cap={<Icon name="arrowLeft" />} label="Up" />
      <KeyHint cap={<Icon name="arrowRight" />} label="Open" />
      <KeyHintText cap="tab" label="Complete" />
      {props.error !== null && <span className="add-space-footer-error">{props.error}</span>}
    </div>
  );
}
