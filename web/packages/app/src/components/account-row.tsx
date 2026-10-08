import { useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { Icon } from "@roboco/icons";
import { useEngineSessions } from "../state/session-provider";
import { useFleet } from "../state/fleet";
import { useWatchSnapshot } from "../state/hooks";
import { checkUpdate } from "./update-strip";
import { PickerCard } from "./ui/PickerCard";

/** The check-for-updates row's live state (see `runCheck`). */
type CheckOutcome =
  | { kind: "idle" }
  | { kind: "busy" }
  | { kind: "current" }
  | { kind: "available"; version: string | null }
  | { kind: "failed" };

/**
 * The sidebar's bottom identity control — the desktop's `render_user_menu`.
 *
 * Upstream f9563394 compacts it: the row's label and subline collapse into
 * a 21px circular avatar button carrying the name's initial (13px white
 * circle, 9px mono semibold) with the name in the aria label — the menu
 * opens to the RIGHT of the trigger (the desktop's
 * `popover::anchored_menu_right`, shell.rs:6430 — the card's top-left pins
 * at the trigger's top-right + 6, clamped into the window).
 *
 * Ticket 02: the card rides `PickerCard`'s `anchorRight` variant — the
 * Base UI body portal. The old inline `.user-menu-card` (absolute, `left:
 * calc(100% + 6px)`) painted inside `<aside class="sidebar">` whose
 * `overflow: hidden` clipped it entirely, so the press looked dead while
 * the state and navigation worked. The portal puts the card on the menu
 * tier at the body, unclipped by any column; outside presses and Escape
 * dismiss through Base UI's pipeline (the old hand-rolled window
 * listeners are gone), and the trigger's press toggles with the
 * `trigger-press` reason exactly like every other anchored menu.
 *
 * The menu carries the desktop's rows (`shell.rs`): the muted "Stored in
 * this browser" identity line, the "Settings" row (which lands on the
 * Devices section, the desktop's landing row), and "Check for updates"
 * (upstream #595 — the desktop's account-menu row; its dialog collapses
 * onto the row here). Engine management lives in Settings → Devices
 * (ticket 45 folded the old web-only Engines drawer there).
 */
export function AccountRow() {
  // The ACTIVE engine's session carries this row's identity — the desktop's
  // user line is the local device, and the web's "home" engine is the one
  // new chats land on.
  const sessions = useEngineSessions();
  const fleet = useFleet();
  const session = fleet.active === null ? null : sessions.get(fleet.active) ?? null;
  const snapshot = useWatchSnapshot(session);
  const navigate = useNavigate();
  // Controlled by the trigger's press — every dismissal (outside press,
  // Escape, the Settings row's navigation) lands here as `false`.
  const [open, setOpen] = useState(false);
  // The check-for-updates row's live state — the desktop's account-menu row
  // with its result dialog collapsed onto the row (the sidebar strip mirrors
  // the "update available" outcome through the engine's UpdateStatus
  // stream, which the engine refreshes as part of the check).
  const [check, setCheck] = useState<CheckOutcome>({ kind: "idle" });

  // The connected engine's own device is the identity this row carries — the
  // desktop's user line is the local device, not the transport. Falls back to
  // the engine label while the devices stream is still filling; the subline
  // is gone (f9563394) — the name rides the trigger's aria label alone.
  const engine = fleet.engines.find((candidate) => candidate.baseUrl === fleet.active) ?? null;
  const deviceId = session?.client.engineInfo?.deviceId ?? null;
  const device =
    deviceId === null ? undefined : snapshot?.devices.rows.find((row) => row.id === deviceId);
  const name = device?.name ?? engine?.label ?? "Roboco";
  const initial = (name.trim()[0] ?? "?").toUpperCase();

  function goSettings(): void {
    setOpen(false);
    // The user menu's Settings row is one of the desktop's generic entries
    // (`open_last_settings`): it lands on the remembered section through the
    // `/settings` index redirect, not a hard-coded section.
    void navigate({ to: "/settings" });
  }

  /** "Check for updates" — one awaited engine check; the row reports it. */
  async function runCheck(): Promise<void> {
    if (session === null || check.kind === "busy") {
      return;
    }
    setCheck({ kind: "busy" });
    try {
      // The reply is the fresh status; the strip refreshes through the
      // engine's UpdateStatus stream, which the check republishes.
      const status = await checkUpdate(session.client);
      setCheck(
        status.updateAvailable
          ? { kind: "available", version: status.latestVersion ?? null }
          : { kind: "current" },
      );
    } catch {
      // Older engines answer UnknownMethod; offline checks fail — both read
      // as "couldn't check" on the row.
      setCheck({ kind: "failed" });
    }
  }

  function handleOpenChange(next: boolean): void {
    // Each opening starts the check row fresh: a verdict from the last visit
    // must not masquerade as one from this one.
    if (next) {
      setCheck({ kind: "idle" });
    }
    setOpen(next);
  }

  return (
    <div className="user-menu">
      {/* The frame is the shared `.popover-card` glass; `.user-menu-body`
          carries only the card-content specifics (the 180px floor and the
          1px row rhythm the old card had). `anchorRight` is the desktop's
          `anchored_menu_right` placement, portaled by `PickerCard`. */}
      <PickerCard
        open={open}
        onOpenChange={handleOpenChange}
        placement="anchorRight"
        cardClassName="popover-card user-menu-body"
        role="menu"
        ariaLabel="Account menu"
        initialFocus={false}
        trigger={
          <button
            type="button"
            className={`user-menu-trigger ${open ? "user-menu-trigger-open" : ""}`}
            aria-label={`Account menu: ${name}`}
            aria-haspopup="menu"
          >
            <span className="avatar" aria-hidden="true">
              {initial}
            </span>
          </button>
        }
      >
        <div className="user-menu-identity">Stored in this browser</div>
        <button type="button" className="menu-item" role="menuitem" onClick={goSettings}>
          <Icon name="settingsMinimalistic" size={16} />
          Settings
        </button>
        {/* The desktop's account-menu "Check for updates" row (upstream
            #595): macOS keeps it in the app menu under About, so the web —
            which has no app menu either — follows the non-macOS placement.
            Busy while the engine checks, then the verdict on the row; the
            sidebar strip surfaces an available release (through the stream
            the check republishes). */}
        <button
          type="button"
          className="menu-item"
          role="menuitem"
          id="user-menu-check-updates"
          disabled={check.kind === "busy"}
          onClick={() => void runCheck()}
        >
          <Icon name="refresh" size={16} />
          {check.kind === "idle" ? "Check for updates" : null}
          {check.kind === "busy" ? "Checking…" : null}
          {check.kind === "current" ? "Roboco is up to date" : null}
          {check.kind === "available"
            ? `Update available — v${check.version ?? "?"}`
            : null}
          {check.kind === "failed" ? "Couldn't check for updates" : null}
        </button>
      </PickerCard>
    </div>
  );
}
