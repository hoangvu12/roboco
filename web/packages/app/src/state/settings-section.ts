/**
 * The remembered Settings section — the web peer of the desktop's
 * `SettingsSection` persistence (upstream d268830b, `settingsSection` in
 * ui-settings.json): generic ways into Settings (the command palette's
 * "Open settings", the `/settings` index route) reopen the section that was
 * showing when Settings was last left, while a link that names a section
 * (`/settings/<slug>`, the sidebar nav) both opens it and becomes the
 * remembered one.
 *
 * The web has no General page (the desktop default), so the web default is
 * the index redirect's historic target: Devices. Appshots is desktop-only
 * and permanently absent here. Unknown, missing, or malformed values heal
 * to that default — same lenient-read contract as the desktop's
 * `SettingsSection` deserializer, which reads unknown slugs as General
 * without discarding the rest of the file.
 */

/** The web's settings sections, in `SettingsNavBody`'s sidebar order. */
export const SETTINGS_SECTION_SLUGS = [
  "devices",
  "remote-access",
  "harnesses",
  "accounts",
  "appearance",
  "files",
  "notifications",
  "shortcuts",
  "archived",
] as const;

export type SettingsSectionSlug = (typeof SETTINGS_SECTION_SLUGS)[number];

/** The web default when nothing valid is remembered. */
export const SETTINGS_SECTION_DEFAULT: SettingsSectionSlug = "devices";

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
 * `SettingsSection::reopenable`, whose General fallback maps to the web's
 * historic default landing).
 */
export function settingsIndexTarget(section: string): string {
  return `/settings/${isSettingsSectionSlug(section) ? section : SETTINGS_SECTION_DEFAULT}`;
}
