import { useCallback, useRef, type ReactNode } from "react";
import { Link, useRouter } from "@tanstack/react-router";
import { Icon, type IconName } from "@roboco/icons";
import { RbDialogGlass } from "./base/dialog";
import { SETTINGS_SECTION_SLUGS, type SettingsSectionSlug } from "../state/settings-section";

/**
 * The settings dialog — the web peer of the desktop's settings modal
 * (upstream b782d043, tickets 22-26): the routed settings pages reshaped
 * into one portal + anchored-card overlay in the web's own dialog grammar
 * (`RbDialogGlass`, the command-palette material the desktop's modal
 * borrows). The `/settings/<section>` routes stay the URL surface: the
 * dialog is open exactly while the route is a settings route, so deep
 * links keep working as shareable URLs and the section nav is plain
 * router `Link`s — a click changes the section under the dialog without
 * unmounting it, and the remembered-section write (settings-layout) rides
 * the pathname change exactly as before.
 *
 * - **⌘,/Ctrl+,** toggles it (app-shell's `open-settings` dispatch: open
 *   navigates to `/settings`, whose index redirect reopens the remembered
 *   section; already open closes through the shared close target).
 * - **Escape** closes: Base UI's dialog dismissal routes to `onClose`,
 *   while an open dropdown or nested dialog inside the page consumes its
 *   own Escape first — the desktop's `dismiss_settings_escape_surface`
 *   ladder for free. Scrim presses close too (the glass contract, same
 *   as the command palette the material comes from).
 * - **Focus** lands on the dialog card itself, never a first control
 *   (the desktop's `settings_focus`: focus the settings page container
 *   with `tab_stop(false)`, so Tab is the explicit way into its content).
 * - **The nav** mirrors `SettingsSection::ALL`: the same order, labels
 *   and icons, grouped by spacing alone (preferences, providers and
 *   devices, then workspace data — `starts_nav_group`), with the Back row
 *   pinned above it. Arrow keys roam: up/down/left/right/home/end move
 *   the selection AND navigate, the desktop's roving section tabs.
 * - **Contained dropdowns** (ticket 24): the card element is provided as
 *   the containment boundary, so every popover the pages open flips and
 *   clamps inside the dialog instead of the window.
 */

/** One nav row — `SettingsSection::ALL`'s order, label and icon, verbatim. */
interface NavSection {
  readonly slug: SettingsSectionSlug;
  readonly label: string;
  readonly icon: IconName;
  /** The desktop's `starts_nav_group`: this row opens a new spacing group. */
  readonly startsGroup: boolean;
}

/** The label + icon per slug — the desktop's `label()` / nav icon table. */
const SECTION_META: {
  readonly [Slug in SettingsSectionSlug]: { readonly label: string; readonly icon: IconName };
} = {
  general: { label: "General", icon: "settings" },
  appearance: { label: "Appearance", icon: "tuning" },
  notifications: { label: "Notifications", icon: "bell" },
  shortcuts: { label: "Shortcuts", icon: "keyboard" },
  harnesses: { label: "Providers", icon: "widget" },
  accounts: { label: "Accounts", icon: "keyMinimalistic" },
  devices: { label: "Devices", icon: "monitor" },
  "remote-access": { label: "Remote access", icon: "keyMinimalistic" },
  files: { label: "Files", icon: "folder" },
  archived: { label: "Archived sessions", icon: "archiveMinimalistic" },
};

/**
 * `SettingsSection::ALL` minus Appshots (desktop-only and permanently absent
 * on web), in the modal redesign's order, with the label/variant crossover
 * the desktop carries: `Harnesses` shows as "Providers", `Agents` shows as
 * "Accounts" (shell.rs:386-431, 582-592).
 */
const NAV_SECTIONS: readonly NavSection[] = SETTINGS_SECTION_SLUGS.map((slug) => ({
  slug,
  ...SECTION_META[slug],
  // The desktop's group starts (shell.rs:574-577): Harnesses opens
  // "providers and devices", Files opens "workspace data".
  startsGroup: slug === "harnesses" || slug === "files",
}));

export interface SettingsDialogProps {
  /** Every exit (Escape, scrim press, Back, ⌘/Ctrl+,) routes here. */
  readonly onClose: () => void;
  /** The routed section page — the router `Outlet`, rendered in the page column. */
  readonly children: ReactNode;
}

/** The settings dialog: glass card, contained nav + page columns. */
export function SettingsDialog(props: SettingsDialogProps) {
  const cardRef = useRef<HTMLDivElement | null>(null);
  const setCard = useCallback((node: HTMLDivElement | null) => {
    cardRef.current = node;
  }, []);
  // The nav rows' elements — the roving arrow keys focus and navigate them.
  const rowRefs = useRef<(HTMLAnchorElement | null)[]>([]);
  const router = useRouter();

  // The desktop's roving section tabs (shell.rs:5487-5540): up/down/left/
  // right/home/end move the selection AND navigate — `open_settings` on
  // the target, focus on its row. The handler is the nav region's, so a
  // row focused by Tab roams too. Raw `router.history.push`, not
  // `navigate({to})`: the section path is computed at runtime, which the
  // typed router's literal `to` union cannot express (the shell's
  // back/forward walk navigates the same way).
  const onNavKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLElement>) => {
      const current = rowRefs.current.findIndex((row) => row === document.activeElement);
      const count = NAV_SECTIONS.length;
      let target: number;
      switch (event.key) {
        case "ArrowUp":
        case "ArrowLeft":
          target = current < 0 ? count - 1 : (current + count - 1) % count;
          break;
        case "ArrowDown":
        case "ArrowRight":
          target = current < 0 ? 0 : (current + 1) % count;
          break;
        case "Home":
          target = 0;
          break;
        case "End":
          target = count - 1;
          break;
        default:
          return;
      }
      event.preventDefault();
      const row = rowRefs.current[target];
      if (row !== null && row !== undefined) {
        row.focus();
      }
      router.history.push(`/settings/${NAV_SECTIONS[target]!.slug}`);
    },
    [router],
  );

  return (
    <RbDialogGlass
      open
      onOpenChange={(next) => {
        if (!next) {
          props.onClose();
        }
      }}
      ariaLabel="Settings"
      overlaySource="settings-dialog"
      cardClassName="settings-dialog-frost"
      // The desktop's `settings_focus`: focus lands on the dialog itself,
      // never a specific first control — Tab is the explicit way in.
      initialFocus={() => cardRef.current}
    >
        <div className="settings-dialog-card" ref={setCard} tabIndex={-1}>
          <nav
            className="settings-dialog-nav"
            aria-label="Settings sections"
            onKeyDown={onNavKeyDown}
          >
            {/* Back is pinned above the sections — the desktop's sidebar
                column shape (render_settings_page, shell.rs:5383-5411). */}
            <button type="button" className="settings-dialog-back" onClick={props.onClose}>
              <Icon name="altArrowLeft" size={16} className="settings-nav-icon" />
              Back
            </button>
            <div className="settings-dialog-sections">
              {NAV_SECTIONS.map((item, ix) => (
                <Link
                  key={item.slug}
                  ref={(row: HTMLAnchorElement | null) => {
                    rowRefs.current[ix] = row;
                  }}
                  to={`/settings/${item.slug}`}
                  className={`settings-nav-link ${item.startsGroup ? "settings-nav-group-start" : ""}`}
                  activeProps={{ className: "settings-nav-link settings-nav-active" }}
                >
                  {/* The icon is ALWAYS muted, selected or not (shell.rs:4392-4394). */}
                  <Icon name={item.icon} size={16} className="settings-nav-icon" />
                  {item.label}
                </Link>
              ))}
            </div>
          </nav>
          <div className="settings-dialog-page">{props.children}</div>
        </div>
    </RbDialogGlass>
  );
}

