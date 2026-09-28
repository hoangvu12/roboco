/**
 * The remembered Settings section — the web peer of the desktop's
 * `SettingsSection` persistence (upstream d268830b, `settingsSection` in
 * ui-settings.json): generic ways into Settings (⌘,/Ctrl+,, the command
 * palette's "Open settings", the account menu's Settings row, the
 * `/settings` index redirect) reopen the section that was showing when
 * Settings was last left, while a link that names a section
 * (`/settings/<slug>`, the dialog's nav) both opens it and becomes the
 * remembered one.
 *
 * The slug list mirrors the desktop's `SettingsSection::ALL` order and
 * grouping after the modal redesign (ticket 26 / upstream b782d043):
 * preferences (General, Appearance, Notifications, Shortcuts), providers
 * and devices (Providers, Accounts, Devices, Remote access), then
 * workspace data (Files, Archived sessions). The web default is the
 * desktop's `#[default]` General — the conversation page the modal
 * redesign added; Appshots is desktop-only and permanently absent here.
 * Unknown, missing, or malformed values heal to that default — same
 * lenient-read contract as the desktop's `SettingsSection` deserializer,
 * which reads unknown slugs as General without discarding the rest of the
 * file.
 */

/** The web's settings sections, in the dialog nav's order (`SettingsSection::ALL` minus Appshots). */
export const SETTINGS_SECTION_SLUGS = [
  "general",
  "appearance",
  "notifications",
  "shortcuts",
  "harnesses",
  "accounts",
  "devices",
  "remote-access",
  "files",
  "archived",
] as const;

export type SettingsSectionSlug = (typeof SETTINGS_SECTION_SLUGS)[number];

/**
 * The web default when nothing valid is remembered: the desktop's
 * `SettingsSection::General` (the conversation page) — the modal
 * redesign's landing page.
 */
export const SETTINGS_SECTION_DEFAULT: SettingsSectionSlug = "general";

export function isSettingsSectionSlug(value: unknown): value is SettingsSectionSlug {
  return typeof value === "string" && (SETTINGS_SECTION_SLUGS as readonly string[]).includes(value);
}

/**
 * The section a `/settings/<slug>` pathname names, or null for anything else
 * (the `/settings` index, an unknown slug, a non-settings route). Mirrors the
 * desktop's `settings_open_route` slug half.
 */
export function settingsSectionFromPath(pathname: string): SettingsSectionSlug | null {
  const slug = /^\/settings\/([^/]+)\/?$/.exec(pathname)?.[1];
  return slug === undefined ? null : (isSettingsSectionSlug(slug) ? slug : null);
}

/**
 * Where the `/settings` index redirects for a remembered section: the section
 * itself, or the default when it is unknown to this build (the desktop's
 * `SettingsSection::reopenable`, whose hidden-section fallback maps to the
 * web's General default).
 */
export function settingsIndexTarget(section: string): string {
  return `/settings/${isSettingsSectionSlug(section) ? section : SETTINGS_SECTION_DEFAULT}`;
}
