# 29 — Remaining settings sections

**What to build:** A user opening Settings can now reach every section the
desktop has except Appshots: Devices (pair by URL, rename, forget, presence
dots), Agents (the harness enable/disable page, née "Harnesses"), Files
(autosave/word-wrap/font-size prefs), Notifications (per-event sound +
desktop-banner toggles), Shortcuts (a full rebind editor with conflict
detection), and Archived sessions (a full-page list with Unarchive) — each
matching the desktop's exact layout, copy, and RPC/persistence behavior.
Accounts and Remote access, already shipped, gain their missing pieces
(device switcher, Refresh action, exact copy). The settings nav (ticket 28)
now links to real pages for all 9 web-relevant sections instead of 3.

**Blocked by:** 03 (Client settings store), 12 (Keyboard), 28 (Settings shell and Appearance)

**Status:** done

**Research:** `../../web-client/research/13-settings-sections.md` §2, §3.0–§3.11, §4, §5 (all rows); `../../web-client/research/12-settings-shell-appearance.md` §3.0 (`UiSettings` model, for persistence shape reference).

**Desktop reference (for lookups only):** `crates/ui/src/settings/accounts.rs` (1586 lines), `crates/ui/src/settings/devices.rs` (686 lines), `crates/ui/src/settings/harnesses.rs` (749 lines), `crates/ui/src/settings/remote_access.rs` (248 lines), `crates/ui/src/settings/shortcuts.rs` (1014 lines), `crates/ui/src/settings/archived.rs` (348 lines), `crates/ui/src/settings/files.rs` (320 lines), `crates/ui/src/settings/notifications.rs` (404 lines), `crates/ui/src/settings/widgets.rs` (full), `crates/engine/src/registry.rs` (`HarnessDescriptor`, `TitleSettings`, `descriptor_enabled`), `crates/ui/src/pickers.rs` (~4025-4069, `visible_harnesses`/`offered_harnesses`), `crates/proto/src/entities.rs` (~748-841, `AgentAccount` family), `crates/proto/src/remote.rs` (85 lines), `crates/rpc/src/lib.rs` (~20-165, `methods::*`).

**Web files to touch:**
| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/routes/settings-devices.tsx` | new | `DevicesSettingsPage`, pairing box, device rows, presence dot, rename dialog |
| `web/packages/app/src/routes/settings-agents.tsx` | new | `AgentsSettingsPage` (desktop's `HarnessesPage`), harness toggle rows, device switcher, session-title pickers |
| `web/packages/app/src/routes/settings-files.tsx` | new | `FilesSettingsPage`, autosave/delay/font-size/word-wrap/show-all rows |
| `web/packages/app/src/routes/settings-notifications.tsx` | new | `NotificationsSettingsPage`, 6 toggle rows |
| `web/packages/app/src/routes/settings-shortcuts.tsx` | new | `ShortcutsSettingsPage`, group cards, `render_row`/binding recorder, send-behavior row, escape-behavior row |
| `web/packages/app/src/routes/settings-archived.tsx` | new | `ArchivedSettingsPage` — full-page list, distinct from the sidebar's `archived-section.tsx` shelf |
| `web/packages/app/src/routes/settings-accounts.tsx` | edit | add `renderDeviceSwitcher` (220px popup), `targetDeviceId` plumbing through `lib/accounts.ts` calls |
| `web/packages/app/src/routes/settings-remote-access.tsx` | edit | fix empty-link copy string (§2.5 Text), confirm Refresh reachability |
| `web/packages/app/src/lib/devices.ts` | new | `formatLastSeen`, `deviceOnline`, `presenceDot`, `platformLabel`, `shortId`, `DEVICE_ONLINE_WINDOW_SECS` |
| `web/packages/app/src/lib/harnesses.ts` | new | `descriptorEnabled`, `visibleHarnesses`, `offeredHarnesses`, harness blurb table, `ListHarnesses`/`SetHarnessEnabled`/`GetTitleSettings`/`SetTitleSettings`/`ListModels` calls |
| `web/packages/app/src/lib/shortcuts-editor.ts` | new | `recordKey`, `conflictOwner`, `sendComboIsReserved`, keymap default table reference (reads ticket 12's keymap table), combo display formatting |
| `web/packages/app/src/lib/archived.ts` | new | `archivedChats` (full-page variant reusing `lib/view.ts`'s `archivedRows` logic) |
| `web/packages/engine-client/src/methods.ts` | edit | add `LIST_HARNESSES`, `SET_HARNESS_ENABLED`, `GET_TITLE_SETTINGS`, `SET_TITLE_SETTINGS`, `LIST_MODELS` (none of these exist yet — confirmed by grep) |
| `web/packages/app/src/router.tsx` | edit | add `devicesRoute`, `agentsRoute`, `filesRoute` (settings, not the existing top-level Files browser route — pick a distinct path segment, e.g. `/settings/files`), `notificationsRoute`, `shortcutsRoute`, `archivedRoute`; change `settingsIndexRoute`'s redirect target to `/settings/devices` (desktop's `OpenSettings`/user-menu always lands on Devices) |
| `web/packages/app/src/components/settings-layout.tsx` | edit | wire the 4 previously-placeholder nav links (Devices, Agents, Files, Notifications, Shortcuts, Archived) to their real routes now that they exist |
| `web/packages/app/src/styles/app.css` | edit | new: `.settings-pairing-box`, `.device-row`, `.presence-dot`, `.id-chip`, `.harness-row`, `.title-picker`, `.pill-row`, `.pill`, `.shortcut-row`, `.combo-chip`, `.combo-chip-recording`, `.segmented-control`, `.archived-page-row` |

## 1. Context a fresh session needs

- This ticket covers every settings section except Appearance (ticket 28)
  and the settings shell itself (also ticket 28, already landed). Read
  ticket 28 first if it has not shipped yet — this ticket's nav wiring
  assumes the 9-row nav from ticket 28 §2.1 exists.
- **Important naming correction inherited from research 13 §1**:
  `crates/ui/src/settings/composer.rs` is NOT a settings page — it is
  `ComposerDefaults`, a sticky "remember my last picks" store, no UI. The
  desktop setting that reads as "send behavior" (Enter vs. Cmd/Ctrl+Enter)
  lives inside the **Shortcuts** page's "Send messages with" row (§2.5
  below), not a "Composer" settings page. Do not invent one.
- Naming collisions to keep straight while building this ticket:
  - `web/packages/app/src/components/archived-section.tsx` is the
    **sidebar's** archived shelf (a different surface, already shipped —
    do not touch it). This ticket's `settings-archived.tsx` is the
    **full-page** Settings → Archived sessions view — a new file.
  - `web/packages/app/src/components/account-row.tsx` is the **sidebar's
    user-menu row** (unrelated to Accounts-page account rows). This
    ticket's device-switcher and account rows live inside
    `settings-accounts.tsx`'s own `AccountRow` function, already present
    — do not confuse the two when searching the codebase.
  - `web/packages/app/src/components/engine-drawer.tsx` is **not** the
    Devices settings page. It is the web's "which paired engine(s) am I
    connected to" picker, reached from the sidebar's user menu
    (`emitShortcut("open-engines")`), and covers a genuinely different
    problem (multi-engine fleet management, ticket 31) than
    `settings/devices.rs`'s per-engine device registry (rename/presence/
    forget for devices that have paired with *one* connected engine).
    **Do not delete or repurpose `engine-drawer.tsx`** — leave it exactly
    as is; ticket 31 decides its long-term fate (keep as the user-menu
    device list, or fold into something else) once the fleet model is
    built. This ticket's `settings-devices.tsx` is a new, separate page.
- The shared widget vocabulary (`page_column`, `page_header`,
  `page_subtitle`, `field_label`, `section_card`, `card_row`,
  `section_header`, `status_dot`, `badge*`, `url_fragment`, `row_tile`,
  `row_title`, `meta_line`, `toggle_switch`, `ghost_action`,
  `error_strip`/`warning_strip`) already exists as CSS
  (`.settings-page`, `.settings-card`, `.settings-row`, `.toggle`,
  `.badge`, etc.) per research 13 §3.0 — confirmed a faithful 1:1 port,
  no numeric gaps. Reuse those classes; do not reinvent per-page
  variants.
- Every new RPC method this ticket needs is listed per section below with
  its exact params/response shape from the research. `methods.ts` is
  missing `LIST_HARNESSES`, `SET_HARNESS_ENABLED`, `GET_TITLE_SETTINGS`,
  `SET_TITLE_SETTINGS`, `LIST_MODELS` — add them. `MUTATE` (for
  `renameDevice`, `setChatArchived`) and the Accounts/Remote-access RPCs
  already exist and are used correctly by the shipped pages — copy their
  calling pattern.
- Shortcuts recording needs a keyboard-capture layer that intercepts
  keystrokes before any bound action fires (the desktop's
  `cx.intercept_keystrokes`). On web this means installing a
  `capture: true` `keydown` listener on `window` while a binding is being
  recorded, calling `preventDefault()`/`stopPropagation()` so nothing else
  reacts. Ticket 12 (Keyboard) owns the default keymap table and its
  `combo_from_keystroke`/`display_combo` helpers — this ticket depends on
  those existing and only builds the editor UI around them, exactly as
  research 13 §3.6 scopes it on desktop.
- Every persisted field in this ticket lives in the ticket-03 client
  settings store (device-local, the web analog of `ui-settings.json`)
  **except** Accounts/Devices/Agents/Remote-access data, which is
  engine-side (RPC-backed, no client persistence at all beyond in-memory
  React state).

## 2. Spec

### 2.1 Devices settings page (`settings-devices.tsx`)

**Layout** — `page_column()`:
1. `page_header("Devices", count)`.
2. `page_subtitle`: *"Connect and manage engines."* (web: since the web
   has no local/synced/development workspace-scope distinction, always
   use this string rather than desktop's scope-conditional variants).
3. Optional `error_strip` (dismiss on click).
4. `section_card` "pairing box": a text field (placeholder *"Paste a
   pairing URL"*) + primary button ("Connect" / "Connecting…" while
   busy) on one row, plus a `mt(6px)` hint, `text-size 11px`, `text_color
   theme.text_muted.opacity(0.65)` (`devices.rs:610-612`): *"Create a
   pairing link in the engine's Remote access settings, then paste it
   here."*
5. `section_card` of device rows (or a centered empty state, `px(16px)
   py(40px)`, `text-center`, `text-size 14px`, `text_color
   theme.text_muted.opacity(0.6)` (`devices.rs:521-525`): *"No devices
   registered"*).

**Children (device row, `card_row`)**: a `row_tile(platform_icon)` with a
**relative** 9px presence dot pinned `bottom(-3px) right(-3px)`,
`border_2(theme.surface)` — Connected: emerald fill + emerald glow (6px
blur, 0.55 opacity); Reconnecting: `theme.warning` fill, no glow; Off:
`ink(0.22)` fill — then a flex-1 column of `row_title(device.name)` +
`meta_line([...])`, then conditionally a `badge` ("This device" / "Local
only"), a "Forget" ghost-action (engine-backed non-local rows only), and
a "Rename" ghost-action (`pen` icon 14px, base `opacity(0.7)` → hover
`opacity(1.0)` + `bg ink(0.06)` + `text_color theme.text`
(`devices.rs:497-502`)).

**`meta_line` fragments, in order** (join with a dimmed "·"):
`platform_label(platform)` → `"v{version}"` if present → connection word
("Connected"/"Reconnecting"/"Off") if an engine-key match exists →
`"Last seen {formatLastSeen}"` if not online → `"Added
{formatLastSeen(created_at)}"` (always, if present) → clickable mono id
chip (`text-size 10.5px`, `font-family theme.font_mono`): short id
(`abcd1234…wxyz` when >12 chars), `text_color theme.text_muted
.opacity(0.5)`; on click, flips to "Copied", `text_color
theme.success_muted.opacity(0.9)` (`devices.rs:434-440`), for 1.5s, then
reverts; hover (either state) → `text_color theme.text_muted`.

**States**

| state | condition | change |
| --- | --- | --- |
| Presence: Connected | engine key found & connected, OR no engine key & `deviceOnline` | emerald dot + glow |
| Presence: Reconnecting | engine key found & reconnecting | amber dot, no glow |
| Presence: Off | engine key found & off, OR no engine key & not online | faint ink dot |
| Pairing busy | in flight | button label "Connecting…" |
| Rename dialog open | see §2.1.1 | modal overlay |
| Id copied | just clicked | chip text → "Copied", `text_color theme.success_muted.opacity(0.9)` (`devices.rs:437`), 1.5s timeout |
| Empty | no devices | "No devices registered" centered text |

**Interactions**:
- Pairing field Enter or Connect click → pair via the fleet/engine-store
  client (the same registry object `engine-drawer.tsx` already uses for
  redeeming pairing URLs — reuse `fleetStore.redeemPairingUrl`, do not
  build a second pairing code path). Clears field on success; shows error
  inline.
- "Forget" click (engine-backed, non-local rows) → the registry's
  `forget`.
- "Rename" click → opens the rename dialog (§2.1.1).
- Id chip click → copies the raw (unscoped) id to the clipboard.

**Text** (verbatim): *"Connect and manage engines."*; *"Create a pairing
link in the engine's Remote access settings, then paste it here."*; "No
devices registered"; pairing placeholder "Paste a pairing URL"; pairing
buttons "Connect"/"Connecting…".

**Data**:
- Reads: device registry rows for the connected engine, local device id,
  connection state per row for presence.
- Writes: `Mutate {op: "renameDevice", deviceId, name}` (`methods::MUTATE`).
  Pairing/forget go through the client-local engine registry (ticket
  31's territory conceptually, but the pairing-box UI itself belongs here
  since it is a Devices-page affordance on desktop too — call the
  existing `fleetStore` methods, do not duplicate their logic).

#### 2.1.1 Rename dialog

**Layout** — a modal card: title "Rename device"; `mt(12px)` field row
(one text input, pre-filled with the device's current name); `mt(16px)`
right-justified button row (Cancel, then Rename).

**States** — Closed: no overlay. Open: field pre-populated with the
row's current name. **Field empty after trim**: submission is **silently
swallowed** — no rename call is made, no error is shown, and the dialog
**still closes**. This is a real desktop behavior quirk to preserve, not
a bug to "fix" into a validation message. Save in flight/failed: the
dialog has **already closed** before the RPC resolves (see Interactions
step 1 below); a failure surfaces only as the page-level `error_strip`
reading `"Rename failed: {err}"`, never inside the dialog.

**Interactions** — Open: click a row's "Rename" action. Enter in the
field submits (the input's own submit behavior — no separate handler
needed if using a `<form onSubmit>`). No Escape-to-cancel handler exists
on desktop for this dialog specifically — closing without saving requires
clicking Cancel; **do not add an Escape handler here**, that would be a
behavior addition beyond parity. Cancel → discards with no RPC call.
Rename button or Enter-submit:
1. The dialog is removed from state **immediately**, before any
   validation — it disappears on this same interaction regardless of
   what happens next.
2. Trim the name.
3. If empty after trim: no RPC call, no error, done (dialog already
   closed in step 1 — net effect indistinguishable from Cancel).
4. Otherwise call `Mutate {op:"renameDevice", deviceId, name}`.
5. On failure, set the **page-level** error to `"Rename failed: {err}"`.

**Text** (verbatim): title "Rename device"; field placeholder "Device
name"; buttons "Cancel"/"Rename"; failure `"Rename failed: {err}"`
(interpolated).

**Data**: writes `Mutate` RPC, `{op: "renameDevice", deviceId, name}`.

### 2.2 Agents settings page (`settings-agents.tsx`, desktop's `HarnessesPage`, nav label "Agents")

**Layout** — `page_column()`:
1. Header row: `page_header("Agents", None)` + spacer + device switcher
   (§2.2.1 below — the identical 220px-wide popup pattern Accounts uses,
   duplicated per-page on desktop; duplicate it here too rather than
   building a shared component prematurely, matching desktop's own
   duplication).
2. `page_subtitle` (max-width 512px, line-height 20px): *"Choose which
   coding agents the composer offers. The setting is per device — switch
   devices in the header. Agents whose CLI isn't installed on a device
   can't be enabled there."*
3. Optional error strip (last refused/failed toggle).
4. `section_card` of harness rows (or a 4-row skeleton while loading, or
   an error + Retry ghost-action).
5. A second `section_card` (`mt(20px) p(16px)`), "Session titles":
   *"Choose the agent and model for automatic titles on this device.
   Claude Code and Codex support restricted title generation."* with two
   pickers ("Title harness" / "Title model"), each a ghost-action trigger
   opening a scrollable (max-height 240px) choice list.

**Children (harness row, `card_row`)**: brand tile (36px, tinted icon via
`@roboco/icons`' `harnessBrandIcon`) → flex-1 column of
`row_title(descriptor.name)` + `meta_line([blurb, optional not-installed
hint])` → `toggle_switch(enabled)`. Row opacity 0.55 when not installed.

**States**

| state | condition | change |
| --- | --- | --- |
| Not installed, not enabled | `!installed && !enabled` | row dimmed, hint *"Install the {cli} CLI to enable"*, `text_color theme.warning_muted.opacity(0.9)` (`harnesses.rs:616` — both not-installed hints below share this one color literal, not just the "stale catalog" one), toggle inert-off |
| Not installed, enabled (stale catalog) | `!installed && enabled` | hint *"{cli} CLI not installed — turn it off or install it"*, same `warning_muted.opacity(0.9)` (`harnesses.rs:616`) |
| Last enabled & installed | `enabled && enabledCount==1 && installed` | toggle **not interactive** (cannot turn off the only running agent) |
| Interactive | `!lastEnabled && (enabled \|\| installed)` | toggle clickable |
| Title picker open | picker menu open | dropdown list renders |
| Title saving | in flight | pickers dim (0.5 opacity), non-interactive |

**Interactions** — toggle click → `SetHarnessEnabled {harness, enabled,
targetDeviceId?}`, replace the whole row list from the reply, then call
the composer's harness-catalog cache-bust hook so the composer's cached
per-space catalog re-fetches (port `pickers::bump_harness_catalog`'s
purpose — invalidate whatever cache the composer keeps per space/harness
list; if no such cache exists yet on web, add the invalidation call site
as a no-op-safe function now so ticket 13/14's composer work can wire it
later without touching this page again). Device switcher: same pattern as
Accounts. Title picker: click trigger toggles open/closed; click a choice
→ `SetTitleSettings {harness, model}`; choosing "Automatic" clears the
field. Retry (error state) → reload.

**Text** (verbatim) — blurb per harness:

| harness | blurb |
| --- | --- |
| ClaudeCode | "Anthropic's coding agent, driven through the Claude Code CLI." |
| Codex | "OpenAI's coding agent, driven through the Codex CLI." |
| Cursor | "Cursor's coding agent, driven through the cursor-agent CLI." |
| Devin | "Cognition's Devin agent (devin CLI)." |
| Grok | "xAI's Grok Build agent (grok CLI)." |
| Hermes | "Nous Research's Hermes Agent (hermes CLI)." |
| Pi | "The pi coding agent (pi CLI)." |
| Opencode | "SST's opencode agent (opencode CLI)." |
| Mock | "Scripted test harness." (exclude from the visible list unless a `ROBOCO_HARNESS=mock`-equivalent dev flag is set, or it is the only harness at all) |

Title picker fallbacks: "Automatic (session agent when supported)"
(harness), "Automatic (cheapest model)" (model).

**Data**:
- Reads: `ListHarnesses {targetDeviceId?}` → `Vec<HarnessDescriptor>`
  `{id, name, supportsSteering, steeringMode, reasoningLevels[],
  installed (default true), enabled: Option<bool>}`.
  `descriptorEnabled()` = `enabled ?? (installed && id !== "mock")`.
- `GetTitleSettings`/`SetTitleSettings {targetDeviceId?}` →
  `{harness: Option<HarnessId>, model: Option<String>}`.
- `ListModels {harness, targetDeviceId?}` → `Vec<Model>`.
- Writes: `SetHarnessEnabled {harness, enabled, targetDeviceId?}` →
  returns the fresh harness list.
- Persistence: engine-side, per device (`harness-prefs.json` equivalent
  on the engine — nothing in the web's client settings store).

#### 2.2.1 Device switcher (shared shape, Accounts + Agents)

A 220px-wide popover card listing the engine's known devices; clicking a
row calls `set_target_device` (reload the page's data with
`targetDeviceId` forced, dropping any in-flight state). Build this once
and reuse it in both `settings-accounts.tsx` (currently missing it
entirely — a real gap per research 13 §5) and `settings-agents.tsx`.

**Trigger geometry** (identical in both pages —
`accounts.rs:338-359`/`harnesses.rs:495-519`): leading icon 16px,
`text_color theme.text_muted`; label `text-size 12.5px`, `font-weight
Medium`, `text_color theme.text`, truncating; trailing 6px status dot
(`bg` emerald when the effective target is the local device, else `ink
(0.2)`); trailing `SORT_VERTICAL` chevron icon 14px.

### 2.3 Files settings page (`settings-files.tsx`)

**Layout** — `page_column()`:
1. `page_header("Files", None)`.
2. `page_subtitle` (max-width 512px, line-height 20px): *"Control how
   workspace files are displayed and saved while you edit."*
3. One `section_card`, in order:
   - "Autosave" row (tile `folder`, *"Save edited workspace files to disk
     automatically."*, toggle).
   - "Autosave delay" row — **only rendered when autosave is enabled**,
     description *"Save files after editing has been idle for this
     long."*, then a `mt(12px)` flex-wrap row of pill buttons for
     `[300, 600, 900, 1500, 3000]` ms; label `"{ms} ms"` under 1000ms,
     else `"{s} s"` (e.g. "1.5 s", "3 s").
   - "Editor font size" row (tile `tuning`, *"Set the text size in
     workspace file editors."*), pill row for `[10.0, 11.5, 13.0, 15.0,
     17.0]` px; label `"{n} px"` (integer sizes drop the decimal: "10 px"
     vs "11.5 px").
   - "Word wrap" row (tile `list`, *"Wrap long lines in every workspace
     file."*, toggle).
   - "Show all files" row (tile `eye`, *"Include hidden and ignored files
     in every file tree."*, toggle).

**Pill geometry** — height 28px, `px(10px)`, radius 7px; active =
`border-color: accent 70%; background: accent 11%`; inactive =
`border-color: theme.border; background: wash(0.025)`; hover (inactive
only) → `background: wash(0.08)`.

**Interactions** — every control fires immediately on click and applies
live to every open file surface (ticket 24/25's territory to actually
wire the live-apply side; this ticket only needs to persist the setting
and expose it for those tickets to read).

**Data** — persisted fields (ticket 03's client settings store):
`filesAutosaveEnabled` (default `false`), `filesAutosaveDelayMs` (default
900, bounds 100–10000 — bounds only matter for hand-edited storage, the
picker only ever offers the 5 fixed values), `filesWordWrap` (default
`false`), `filesEditorFontSize` (default 13.0, bounds 9.0–24.0), `filesShowAll`
(default `false`). No RPC calls — purely client-local.

### 2.4 Notifications settings page (`settings-notifications.tsx`)

**Layout** — `page_column()`:
1. `page_header("Notifications", None)`.
2. `page_subtitle` (max-width 512px, line-height 20px): *"Choose which
   session events can play a sound, and when desktop notifications
   appear."*
3. One `section_card`, six rows in fixed order:

| # | title | tile icon | description | dependency |
| --- | --- | --- | --- | --- |
| 1 | "Session sounds" | `volumeLoud` | "Allow sounds for the selected session events below." | master, always interactive |
| 2 | "Task completed" | `check` | "Play a sound when an agent finishes a run." | depends on 1 |
| 3 | "Input required" | `chatRoundLine` | "Play a sound when an agent needs your response." | depends on 1 |
| 4 | "Errors and disconnections" | `dangerTriangle` | "Play a sound when a run fails or the connection remains unavailable." | depends on 1 |
| 5 | "Desktop notifications" | `bell` | "Show a system banner on the same events, so pings reach you while Roboco is in the background." | master, always interactive |
| 6 | "Only when in the background" | `monitor` | "Skip the banner while a Roboco window is focused." | depends on 5 |

**States** — dependent rows (2,3,4,6) render at 0.55 opacity and are
non-interactive when their master toggle is off (still render the
`role="switch"` element, but with no `tabIndex`/click handler and
`aria-description="Unavailable while its parent setting is off"`).
Master rows (1,5) are always interactive.

**Interactions** — click (or Enter/Space when focused) any toggle flips
its bool and persists the full set of six fields immediately (not a
delta write).

**Data** — persisted fields (ticket 03's client settings store, all
default `true`): `soundEnabled`, `soundCompletionEnabled`,
`soundInputEnabled`, `soundAttentionEnabled`, `notificationsEnabled`,
`notificationsBackgroundOnly`. No RPC calls. These six fields are what
ticket 30's notification/sound engine reads to gate chimes and banners —
this ticket only builds the toggles and their persistence; wiring them
into actual sound/banner playback is ticket 30's job.

### 2.5 Remote access page — polish (edit `settings-remote-access.tsx`)

Already shipped and close to parity per research 13 §3.5. Fix these
specific gaps only:

| item | current web | fix |
| --- | --- | --- |
| Empty-link copy | *"No link yet. Create one and open or paste it on the other device."* (`settings-remote-access.tsx:168`) | *"No link yet. Create one and paste it on the other device under Settings → Devices."* — now correct to say since ticket 29 ships Settings → Devices |
| "Use once within five minutes." | already present verbatim | no change — confirmed this is a static string on desktop too; `PairingLink.expiresAt` is fetched but never used for a countdown. **Do not add a countdown timer** — that would be inventing behavior the desktop doesn't have |
| QR code | absent | **do not add** — desktop has none either; the research brief's "QR if any" resolves to none existing |
| Refresh reachability | already correctly enabled while `snapshot === null` (only the toggle is disabled then) | no change |

No other changes to this file are in scope for this ticket.

### 2.6 Accounts page — polish (edit `settings-accounts.tsx`)

Add the device switcher from §2.2.1 (currently entirely missing — no
`targetDeviceId` is ever sent by `lib/accounts.ts`'s calls). Thread
`targetDeviceId` through `listAgentAccounts`/`activateAgentAccount`/
`forgetAgentAccount`/`startAgentLogin`/`pollAgentLogin`/
`cancelAgentLogin` in `lib/accounts.ts`, defaulting to the connected
engine's own device when no switcher selection has been made (matching
desktop's "local by default, explicit switch to retarget" model). No
other functional changes to this page are in scope — but the shipped
implementation's pixel/opacity values were never checked against the
Rust; verify (and fix if drifted) these exact literals while touching the
file for the switcher work:

**Usage meter** (`render_usage_meter`, `accounts.rs:686-754`): row `gap
8px`, `text-size 11.5px`, `text_color theme.text_muted.opacity(0.7)`;
label cell `w(48px)`, `flex_none`, `truncate` (`accounts.rs:708`); track
`flex_1`, `min_w(56px)`, `max_w(230px)`, `h(5px)`, `rounded_full`, `bg ink
(0.07)` (`accounts.rs:716-721`); fill `rounded_full`, color = the usage
color at `opacity(0.8)` (Normal level) or `opacity(0.85)` (Warn/Critical)
(`accounts.rs:694-697`), with a 1.5%-of-track floor width so tiny non-zero
usage stays visible; percent cell `w(64px)`, `flex_none`, `text-right`
(`accounts.rs:736`); trailing reset fragment, when present, `text_color
theme.text_muted.opacity(0.45)` (`accounts.rs:749`). The identical
`min_w(56px)`/`max_w(230px)` track cap and `w(48px)`/`w(64px)` label/
percent cells are reused by the skeleton meter ghosts below.

**Usage-unavailable fallback line** ("Usage unavailable"/"Credentials
unavailable", shown instead of meters when an account has none):
`mt(6px)`, `text-size 11.5px`, `text_color theme.text_muted.opacity(0.6)`
(`accounts.rs:887-895`).

**Skeleton row** (`render_skeleton_row`, `accounts.rs:1114-1193`): each
ghost meter row `gap(8px)`: label ghost `48×9px`; track ghost `flex_1`,
`min_w(56px)`, `max_w(230px)`, `h(5px)`, `rounded_full`, `bg ink(0.04)`;
percent ghost `64×9px` (`accounts.rs:1144-1160`); the row's own email-line
ghost is `176×13px`, capped `max_w(relative(0.6))` (`accounts.rs:1178`);
the trailing badge ghost is `64×21px`, `rounded_full`
(`accounts.rs:1181-1185`); the whole row dims to `opacity(0.6)` when
`dim` (the second skeleton row) (`accounts.rs:1190`), on top of the
shared pulse animation's `0.55 + 0.35 * wave` opacity oscillation.

**Provider empty-state copy**: `px(20px) py(32px)`, `text-center`,
`text-size 14px`, `text_color theme.text_muted.opacity(0.6)`
(`accounts.rs:1350-1354`).

**Header Refresh action**: `text-size 12.5px` (`accounts.rs:1427`), rest
per the shared `ghost_action` geometry (§2.0 of ticket 28).

**Footer note**: `mt(24px)`, `text-size 12px`, `line-height 19px`,
`text_color theme.text_muted.opacity(0.6)` (`accounts.rs:1464-1467`).

**Login dialog**: the "Reopen the…" text link is `mt(6px)`, `text-size
12px`, `text_color theme.text_muted.opacity(0.6)`, hover → `theme.text`
(`accounts.rs:939-944`); an inline error message (paste-code submit
failure, browser-flow failure) is `text_color theme.danger_muted
.opacity(0.9)` (`accounts.rs:929`, reused at `:995,1086`); the
browser-flow "Waiting for the browser…" line is `text-size 12.5px`,
`text_color theme.text_muted.opacity(0.7)` (`accounts.rs:1073-1074`).

### 2.7 Shortcuts editor page (`settings-shortcuts.tsx`)

The default-combo table itself is ticket 12's (Keyboard); this ticket
builds only the editor UI around it.

**Layout** — `page_column()`:
1. Header row (items-start, justify-between, gap 24px): left =
   `page_header("Keyboard shortcuts", None)` + `page_subtitle` (*"Click a
   binding, then press the key combination you want to use. Changes
   apply immediately and stay on this device."*, max-width 512px,
   line-height 20px); right = "Restore defaults" ghost-action (`restart`
   icon 14px), 0.35 opacity and inert when nothing is customized or while
   recording.
2. `send_behavior_row` (`mt(32px)`, `section_card` with one min-height
   84px `card_row`): title "Send messages with" + description (`mt(4px)`,
   `max-width 430px` (`shortcuts.rs:498,609`), `text-size 11.5px`,
   `line-height 17px`, `text_color theme.text_muted.opacity(0.65)`
   (`shortcuts.rs:501,612` — the same description geometry the
   escape-behavior row below uses)) *"Choose whether Enter sends
   immediately or starts a new paragraph. Cmd/Ctrl+Enter always submits;
   with an empty composer it sends the most recently queued message.
   Shift+Enter always inserts a line break."* + a segmented control
   (Enter / platform modifier label; each option `min-width 72px`,
   `px(12px) py(6px)`, `radius 7px`, mono font, `text-size 12px`;
   selected: `bg theme.bg`, `border_1`, `border_color theme.border
   .opacity(0.8)` (`shortcuts.rs:581`), `text theme.text`; unselected:
   `text theme.text_muted`, hover → `text theme.text`) with a
   reset-to-Enter icon button shown only when non-default.
3. Six group cards (`mt(28px)`, gap 28px between them), each a
   `field_label(name)` + `section_card` of rows, in `GROUP_ORDER`:
   **Files, Browser, Panels, Sessions, Jump to session**. (The Appshots
   group is explicitly skipped — it has no web page at all, unlike
   desktop where it renders on a separate route.)
4. Helper line (`mt(12px)`, centered, 12px, min-height 20px): *"Press
   Escape to cancel."* while recording, else the last conflict notice,
   else *"Shortcuts must be unique."*
5. `escape_behavior_row` (`section_card`, min-height 84px row): title
   "Stop active agent with Escape" + description (`mt(4px)`, `max-width
   430px`, `text-size 11.5px`, `line-height 17px`, `text_color
   theme.text_muted.opacity(0.65)` (`shortcuts.rs:498-501`)) *"When no
   dialog, menu, picker, or terminal handles Escape, stop the agent in
   the active session."* + toggle.

**Children (shortcut row, `render_row`)**: min-height 72px, `px(20px)
gap(20px)` flex row: left flex-1 column (13px medium label + 12px muted
description) → `render_binding_control`: optional "Reset" text button
(11px, shown only when the combo differs from default and not recording)
+ the combo chip (min-width 96px, `px(12px) py(6px)` radius 8px, mono
12px; recording state: `border_color theme.text.opacity(0.3)`, `bg
theme.text`, `text_color theme.on_solid`, label "Press keys…"
(`shortcuts.rs:373-375`); idle state: `border theme.border`, `bg
theme.bg`, `text theme.text`, shows the formatted combo, hover (idle
only) → `border_color theme.text.opacity(0.2)`, `bg ink(0.03)`
(`shortcuts.rs:377-385`)).

**States**

| state | condition | change |
| --- | --- | --- |
| Recording | this id is being recorded | chip inverts, keystroke interceptor + blur-cancel installed |
| Conflict refused | bound combo matches another id | conflict notice `"{Combo} is already assigned to {label}."`, recording stops, keymap unchanged |
| Reserved combo | combo == `mod-enter` | conflict notice `"{Combo} is reserved for the composer."`, refused |
| Non-default | combo != default | "Reset" button appears |
| Customized (page-level) | keymap ≠ default OR escape-toggle on OR send behavior ≠ Enter | "Restore defaults" becomes interactive |

**Interactions**:
- Click a combo chip → start recording: focus a hidden element, install a
  capturing `keydown` listener that runs before any bound shortcut fires,
  and a blur handler that cancels recording.
- Keystroke while recording: Escape → cancel (keep old combo); a bare
  modifier alone → stay recording; otherwise build the combo string and
  validate in order: (1) reserved-combo check (`mod-enter` always
  refused); (2) conflict check against every other bound id; else commit
  and stop recording.
- Per-row "Reset" click → reset that id to its default, commit
  immediately.
- "Restore defaults" click → reset the whole keymap to defaults, escape
  toggle to `false`, send behavior to `Enter`.
- Send-behavior segmented control click → set send behavior.
- Escape-behavior toggle click → set the escape-stops-agent preference.

**Text** (verbatim, per shortcut id):

| id | description |
| --- | --- |
| CaptureAppshot | "Capture the focused application from anywhere on your desktop." (list this row's text for completeness, but never render the row itself — Appshots has no web surface; skip the whole group) |
| SaveFile | "Save the active workspace file." |
| BrowserReload | "Reload the focused browser tab." |
| ToggleSidebar | "Show or hide sessions and settings navigation." |
| ToggleChanges | "Show or hide the right sidebar for the current session." |
| ToggleTerminal | "Show or hide the terminal for the current session." |
| NewSession | "Open a blank session canvas to start a new session." |
| NextSession | "Select the next session in the sidebar, wrapping at the end." |
| PrevSession | "Select the previous session in the sidebar, wrapping at the start." |
| ArchiveSession | "Move the current session to the archived shelf." |
| JumpSession(_) | "Open the session at this place in the sidebar list." |

Modifier-send labels: macOS "⌘ Enter", else "Ctrl Enter". Conflict
message: `"{Combo} is already assigned to {owner label}."`; reserved
message: `"{Combo} is reserved for the composer."`. Helper line default:
*"Shortcuts must be unique."*

**Data**: purely client-local (ticket 03's client settings store):
`keymap` (11 sub-fields per ticket 12's table), `escapeStopsActiveAgent`,
`composerSendBehavior`. No RPC calls anywhere on this page. Every change
must also re-apply the app's live keyboard bindings immediately (the
desktop's `apply_keymap` equivalent — ticket 12 owns the binding-apply
mechanism this page calls into).

### 2.8 Archived sessions settings page (`settings-archived.tsx`)

**Distinct from** `components/archived-section.tsx` (the sidebar shelf —
do not touch that file, do not merge the two surfaces).

**Layout** — `page_column()`:
1. `page_header("Archived sessions", count)`.
2. `page_subtitle`: *"Hidden from the sidebar, never deleted. Unarchiving
   puts a session back on its device."*
3. Optional `error_strip`.
4. Body: centered empty state, or a `mt(24px) gap(2px)` flex column of
   rows.

**Children (row)**: flex row, items-center, gap 12px, radius 8px,
`px(12px) py(8px)`, hover background; a 32px rounded-6px bordered tile
with `archiveMinimalistic` icon 16px, icon color `theme.text_muted
.opacity(0.6)` (`archived.rs:144`); flex-1 column: title row (13px
medium title, truncating + 11px time-ago) then a meta row (`device ·
location`, only the fragments that resolve are shown, "·" separator only
when both present); trailing "Unarchive" pill (icon `archiveUpMinimalistic`
14px + text), bordered, invisible unless the row is hovered or the
unarchive action is in flight.

**States**

| state | condition | change |
| --- | --- | --- |
| Empty | no archived chats | centered `ARCHIVE_MINIMALISTIC` icon 28px, `text_color theme.text_muted.opacity(0.2)` (`archived.rs:255` — the container around it separately carries `text_muted.opacity(0.5)`, but the icon's own color literal overrides it to 0.2, an effectively-dim ~20% glyph); `mt(12px)` *"Nothing archived"* 14px (inherits the container's 0.5 color, no override of its own); `mt(4px)` *"Right-click a session in the sidebar to archive it."* 12px, `text_color theme.text_muted.opacity(0.4)` (`archived.rs:267`) |
| Row hovered | pointer over | Unarchive pill opacity 0→1 (`archived.rs:219`) |
| Unarchive busy | action in flight for this chat | pill stays visible (opacity forced to 1 by the busy condition itself), then additionally dimmed to `opacity(0.4)` (`archived.rs:220`), label → "Unarchiving…" |
| Unknown device | no device-name match | device fragment omitted entirely (not a placeholder) |

**Interactions** — click "Unarchive" → `Mutate {op: "setChatArchived",
chatId, archived: false}`.

**Text** (verbatim): *"Hidden from the sidebar, never deleted. Unarchiving
puts a session back on its device."*; *"Nothing archived"*; *"Right-click
a session in the sidebar to archive it."*; fallback title "Untitled
session"; button label "Unarchive"/"Unarchiving…".

**Data**: reads chats filtered by `.archived`, sorted in the same recency
order the sidebar uses; devices for the device-name lookup; the existing
`lib/view.ts` time-ago/location helpers. Writes `Mutate {op:
"setChatArchived", chatId, archived: false}`. No client-local
persistence — archived state is a chat-doc field on the engine. Reuse
`lib/view.ts`'s existing `archivedRows` computation (already used by the
sidebar shelf) rather than writing a second sort/filter implementation —
this page just renders more rows, with fuller per-row content, and no
space-filter scoping (the full-page view shows every archived chat, not
just the current space's).

## 3. Pure logic to port

- `usageLevel(fraction) -> UsageLevel` — already ported in
  `lib/accounts.ts` (confirmed match against `USAGE_WARN_FRACTION=0.80`/
  `USAGE_CRITICAL_FRACTION=0.95`); no change needed, referenced here only
  because the device-switcher work in §2.6 touches the same file.
- `deviceOnline(lastSeen, now) -> bool` (new, `lib/devices.ts`): `now -
  lastSeen <= 70s` (`DEVICE_ONLINE_WINDOW_SECS`); future timestamps
  (clock skew) count as online; `null` → offline. Test:
  `deviceOnlineWithin70SecondsWindow`.
- `presenceDot(connection, online) -> PresenceDot` (new,
  `lib/devices.ts`): engine-backed rows use the connection state verbatim
  (Connected/Reconnecting/Off); rows with no engine key fall back to
  `deviceOnline`. Test: `presenceDotFallsBackWhenNoEngineKey`.
- `formatLastSeen(lastSeen, now) -> string` (new, `lib/devices.ts`):
  `null`→"never seen"; `<60s`→"just now"; `<3600s`→"{m}m ago";
  `<86400s`→"{h}h ago"; else "{d}d ago". Reused by both Devices rows and
  Remote-access paired-session rows (`remote-access.ts` may already have
  an equivalent — check before duplicating; if it exists, move it to
  `lib/devices.ts` and import it from both places). Test:
  `formatLastSeenBucketsMatchRoboco`.
- `platformLabel(platform) -> string` (new, `lib/devices.ts`):
  `macos|darwin`→"macOS", `linux`→"Linux", `windows`→"Windows",
  `web`→"Web", `ios`→"iOS", `android`→"Android", else verbatim
  passthrough. Test: `platformLabelMapsKnownPlatforms`.
- `shortId(id) -> string` (new, `lib/devices.ts`): `>12` chars →
  `"{first8}…{last4}"`, else passthrough. Test:
  `shortIdTruncatesLongIds`.
- `descriptorEnabled(descriptor) -> bool` (new, `lib/harnesses.ts`):
  `enabled ?? (installed && id !== "mock")`. Test:
  `descriptorEnabledDefaultsToInstalledExceptMock`.
- `visibleHarnesses`/`offeredHarnesses` (new, `lib/harnesses.ts`): hide
  Mock unless a dev flag is set or it's the only harness; `offered`
  additionally requires `installed && descriptorEnabled`. Test:
  `mockHarnessHiddenUnlessOnlyOption`.
- `conflictOwner(keymap, id, combo) -> id | null` (new,
  `lib/shortcuts-editor.ts`): first other available id whose bound combo
  equals this one. Test: `conflictOwnerFindsFirstMatchingBinding`.
- `sendComboIsReserved(combo) -> bool` (new, `lib/shortcuts-editor.ts`):
  `combo === "mod-enter"`, unconditionally. Test:
  `modEnterAlwaysReserved`.
- `archivedChats(chats) -> chats` (reuse `lib/view.ts`'s existing
  `archivedRows`, do not duplicate): filter `.archived`, order preserved
  from the pre-sorted input.

## 4. Gaps this ticket closes

| item | kind | desktop value | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| Devices settings page | MISSING | full `DevicesPage` | no route | Add `/settings/devices` |
| Agents ("Harnesses") settings page | MISSING | full `HarnessesPage` | no route | Add `/settings/agents` |
| Shortcuts editor page | MISSING | full `ShortcutsPage` | `state/shortcuts.ts` is an unrelated event bus | Add `/settings/shortcuts` with real keyboard-recording capture |
| Files settings page | MISSING | `FilesSettingsPage` | no route | Add `/settings/files` |
| Notifications settings page | MISSING | `NotificationsPage` | no route | Add `/settings/notifications` |
| Archived settings page | MISSING | `ArchivedPage`, full-page list | only the sidebar shelf exists | Add `/settings/archived` as a separate page |
| Composer send-behavior setting | MISSING (as any surface) | lives inside Shortcuts page | no route, no control | Ships as part of §2.7; do not invent a standalone "Composer" page |
| Settings default landing section | WRONG BEHAVIOR | lands on Devices | `/settings` redirected to `/settings/remote-access` | Redirect to `/settings/devices` now that it exists |
| Settings nav section count | MISSING | 9 web-relevant sections | 3 | All 9 now link to real pages |
| Accounts: device switcher | MISSING | `render_device_switcher`, `targetDeviceId` | absent | Add per §2.2.1/§2.6 |
| Remote access: empty-link copy | WRONG VALUE | *"…under Settings → Devices."* | *"…on the other device."* | Fix string now that Devices exists |
| EngineDrawer vs Devices | (context only, not a fix in this ticket) | n/a | `engine-drawer.tsx` solves a different problem | Do not touch `engine-drawer.tsx`; leave its fate to ticket 31 |

## 5. Do not

- Do not build the Appshots settings page or its group in the Shortcuts
  editor — desktop/Linux-only native screen-capture feature, no browser
  equivalent, permanently out of scope.
- Do not touch `components/archived-section.tsx` (the sidebar shelf) —
  it is a different, already-correct surface.
- Do not delete, repurpose, or "fix" `components/engine-drawer.tsx` in
  this ticket. It is not the Devices page and is not wrong; it is
  ticket 31's concern.
- Do not build a "Composer" settings page — the send-behavior setting
  lives in Shortcuts, per desktop.
- Do not add a countdown timer to the Remote access pairing link — the
  desktop fetches `expiresAt` and never displays it; the copy is a static
  string.
- Do not add a QR code to Remote access — desktop has none.
- Do not build the sound/notification *playback* engine here — this
  ticket only builds the Notifications page's toggles and persists them;
  ticket 30 wires them to actual chimes/banners.
- Do not build the Fleet/multi-engine registry semantics — ticket 31.
- Do not invent extra validation on the device-rename dialog (e.g. an
  Escape-to-cancel handler, or an error message for an empty name) beyond
  what §2.1.1 specifies — the silent-swallow-and-close behavior is
  intentional parity, not a bug.

## 6. Acceptance

- [ ] Settings nav (ticket 28's shell) links to real pages for Devices,
      Agents, Files, Notifications, Shortcuts, and Archived; `/settings`
      redirects to `/settings/devices`.
- [ ] Devices page: pairing box connects via a pasted URL and lists the
      new device; device rows show the correct presence dot, meta
      fragments, and id-chip copy behavior; Rename and Forget both work,
      including the rename dialog's silent-empty-swallow quirk.
- [ ] Agents page: harness rows toggle correctly, respecting the
      "can't disable the last enabled harness" rule and the
      not-installed dimming/hints; device switcher retargets the page;
      session-title pickers commit and clear to Automatic.
- [ ] Files page: all 5 rows persist and reflect their current value;
      the autosave-delay row appears/disappears with the autosave toggle.
- [ ] Notifications page: all 6 toggles persist; dependent rows (2,3,4,6)
      are visibly dimmed and non-interactive when their master is off.
- [ ] Shortcuts page: clicking a chip enters recording mode; a valid new
      combo commits; a reserved or conflicting combo is refused with the
      exact message text; "Restore defaults" resets everything; the
      send-behavior segmented control and escape-behavior toggle both
      work.
- [ ] Archived page: lists every archived chat (not scoped to the
      sidebar's current space filter), shows the hover-reveal Unarchive
      pill, and successfully unarchives a chat.
- [ ] Accounts page: device switcher popup (220px) appears and retargets
      account data via `targetDeviceId`.
- [ ] Remote access: empty-link copy reads "...under Settings → Devices.";
      no countdown timer, no QR code added.
- [ ] Unit tests: `deviceOnlineWithin70SecondsWindow`,
      `presenceDotFallsBackWhenNoEngineKey`, `formatLastSeenBucketsMatchRoboco`,
      `platformLabelMapsKnownPlatforms`, `shortIdTruncatesLongIds`,
      `descriptorEnabledDefaultsToInstalledExceptMock`,
      `mockHarnessHiddenUnlessOnlyOption`, `conflictOwnerFindsFirstMatchingBinding`,
      `modEnterAlwaysReserved` → new tests in `lib/devices.ts`,
      `lib/harnesses.ts`, `lib/shortcuts-editor.ts`.
- [ ] Screenshot pair, desktop vs web, states: (a) Devices page with 2+
      rows, one online one offline; (b) Agents page with one harness
      toggled off and its not-installed hint visible; (c) Shortcuts page
      mid-recording on one row; (d) a conflict-refused shortcut showing
      the conflict message; (e) Archived page with 2+ rows and one
      hovered showing the Unarchive pill; (f) Notifications page with the
      master toggle off and dependent rows dimmed.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

### What landed (2026-09-19, wp2/29-settings-sections)

- **Devices** (`routes/settings-devices.tsx`, `lib/devices.ts`): the pairing
  box (reuses `fleetStore.redeemPairingUrl` + `webDeviceLabel` — no second
  pairing path), one `settings-row` per registry device with the platform
  tile's corner presence dot (emerald+glow / amber / ink-22, `border 2px
  var(--rb-card)` ring), the meta-line fragment order (platform · v{version}
  · connection word · "Last seen {…}" · "Added {…}" · the mono id chip), the
  1.5s click-to-copy "Copied" flip, the Rename dialog with BOTH desktop
  quirks (scrim swallows clicks, no Escape handler — only Cancel / Rename /
  Enter close; empty-after-trim submits close the dialog silently with no
  RPC), and `Mutate {op: "renameDevice"}` writes. "This device" badge on
  the local row. `formatLastSeen` moved from `lib/remote-access.ts` into
  `lib/devices.ts` (shared by both pages); `platformLabel`, `shortId`,
  `deviceOnline`, `presenceDot`, `formatLastSeenAt` (ISO variant) added.
- **Agents** (`routes/settings-agents.tsx`, `lib/harnesses.ts`): harness
  rows over `visibleHarnesses` (re-exported from `lib/model-rows.ts` —
  ticket 10 already landed the pure functions; no duplication), the
  last-enabled/installed interactivity rule, the two not-installed hints at
  `warning_muted.opacity(0.9)`, the 4-row skeleton, error+Retry, the
  per-device session-titles card with both inline pickers (Automatic
  fallbacks, `supportsTitles` filter, SetTitleSettings round trip +
  ListModels reload), and `bumpHarnessCatalog(session)` — the
  `pickers::bump_harness_catalog` port as a stale-while-revalidate
  `catalog.loadHarnesses({force: true})` poke. Blurb/CLI tables verbatim.
- **Files** (`routes/settings-files.tsx`): the five rows with the
  autosave-gated delay row, pill rows (28px/7px radius/11.5px, accent-70
  border + 11% wash active, wash-2.5% inactive, wash-8% hover), integer
  font labels dropping the decimal; every write immediate through ticket
  03's store. Desktop's odd separator rule preserved (first four rows
  borderless, only "Show all files" carries the top border).
- **Notifications** (`routes/settings-notifications.tsx`): the six rows in
  fixed order, masters always interactive on `RbSwitch` (Base UI), the
  inert twin for dependent rows (same 32×18 switch, `role="switch"`, the
  "Unavailable while its parent setting is off" aria-description, no
  handlers), 0.55 row dimming, full-set persistence per flip.
- **Shortcuts** (`routes/settings-shortcuts.tsx`, `lib/shortcuts-editor.ts`,
  `state/keymap.ts`): the five group cards (Appshots skipped), 18 rows,
  `render_row`'s chip (96px min, mono, recording inversion to
  text-on-solid with "Press keys…"), per-row Reset, Restore defaults
  (0.35/inert), the send-behavior segmented control + reset icon, the
  escape-behavior toggle, and the helper line's three states. The recorder:
  `setKeystrokeIntercept` (a `cx.intercept_keystrokes` registry —
  `app-shell.tsx`'s binding dispatch declines while it is held) + one
  capture-phase window listener that preventDefaults/stopPropagations
  every keystroke + blur cancel on the focused recorder span. Escape
  cancels; bare modifiers stay recording (DOM "Meta"/"OS" normalized to
  the grammar's "cmd"); reserved-check then conflict-check then commit.
  `keymapGet` exported from `state/shortcuts.ts`; `keymapStore` patches
  persist + re-apply live (the shell's `useKeymap` table rebuilds).
- **Archived** (`routes/settings-archived.tsx`, `lib/archived.ts`): the
  full-page list on `lib/view.ts`'s `archivedRows` with `spaceFilter=null`
  (every archived chat, not the shelf's scope) plus the page-only content
  ("Untitled session" fallback, device · location meta via the
  `chat_location` port, 11px time-ago), the hover-reveal Unarchive pill
  (CSS `:hover` — the desktop's `self.hovered` group-hover equivalent) with
  the busy state's forced-0.4 reveal, and `setChatArchived` writes.
- **Accounts**: the 220px `DeviceSwitcher` (built once in
  `components/ui/DeviceSwitcher.tsx` per §2.2.1, on `PickerCard` +
  `MenuRow`s — §2.2's "duplicate it" parenthetical was superseded by
  §2.2.1's "build this once and reuse"), `targetDeviceId` threaded through
  every `lib/accounts.ts` wrapper (null = local, no passthrough),
  `set_target_device`'s drop-and-reload, and the §2.6 literal fixes:
  Refresh as a 12.5px ghost action with a 16px icon dimming to 0.5, the
  usage-fill 0.8/0.85 opacities (`usageColorVar` now color-mixes), the
  usage-fallback line (11.5px/60% + mt-6), the desktop-geometry skeleton
  row (avatar ghost, 176×13 email line capped 60%, two meter ghosts
  48×9/56–230×5/64×9, 64×21 badge, row-2 0.6 dim), the login link without
  the underline (the Rust ports text-color only), the login error at
  `danger_muted.opacity(0.9)`, the wait line at 12.5px/70%.
- **Remote access**: the empty-link copy now reads "…under Settings →
  Devices." No countdown, no QR (per §2.5).
- **Router/nav**: all six stub routes swapped for real components
  (`settings-stub.tsx` deleted — nothing was meant to survive), `/settings`
  redirects to `/settings/devices`, and the user menu's Settings row
  (`account-row.tsx`) lands on Devices (the desktop's `OpenSettings`
  target; the file's own comment said it was temporary until this ticket).
- `engine-client/src/methods.ts`: added `SET_HARNESS_ENABLED`,
  `GET_TITLE_SETTINGS`, `SET_TITLE_SETTINGS` (`LIST_HARNESSES` and
  `LIST_MODELS` already existed — the ticket's "confirmed by grep" note
  predated ticket 10).

### Deviations and judgment calls (for a human)

1. **Web mapping of the Devices page's multi-engine concepts.** The
   desktop's rows mix "devices that paired with this engine" with
   engine-registry connection state. On the web, WatchDevices publishes
   exactly the engine's own device row (engine-local registry, ADR 0004),
   so: the local row = `engineInfo.deviceId` (presence = the live client
   status), a row matching a parked fleet engine renders engine-backed-off
   and carries Forget (`fleetStore.remove`), every other row falls back to
   the last-seen window. In practice the smoke engine shows one row.
2. **The title pickers render inline choice lists, not popovers** — that
   is what the desktop does (`harnesses.rs:358-382` appends a
   `max_h(240)` scroll list inside the titles card); §2.2's "ghost-action
   trigger opening a scrollable choice list" reads the same way once you
   check the Rust.
3. **The recorder's intercept needed one `app-shell.tsx` edit** beyond the
   ticket's file table: the shell's binding dispatch is a capture-phase
   window listener registered at mount, so a later-registered capture
   listener can never preempt it. The `setKeystrokeIntercept` registry
   (read live per event) is the minimal equivalent of
   `cx.intercept_keystrokes`; verified live — Ctrl+N during recording
   neither fires New session nor navigates, exactly the desktop's
   `recorder_refuses_bound_actions_before_they_can_run` test.
4. **`DeviceSwitcher` lives in `components/ui/`** (data+callbacks in, DOM
   out) rather than being duplicated per page — §2.2.1 says build once and
   reuse; §2.2's parenthetical said duplicate. I followed the dedicated
   section.
5. **`lib/harnesses.ts` re-exports** `descriptorEnabled`/
   `visibleHarnesses`/`offeredHarnesses` from `lib/model-rows.ts` instead
   of re-implementing them (ticket 10 landed them for the pickers). The
   ticket-named API surface exists; one source of truth.
6. **The Files separator quirk** (only the last row bordered) is ported
   verbatim from `files.rs:146-300` — it looks like a desktop bug but is
   parity.
7. **`customized` on the Shortcuts page** compares the serialized keymap
   (`JSON.stringify`) — `defaultKeymap()` mints a fresh object, so a
   reference compare (my first draft) is always true. The desktop uses
   derived `PartialEq`.
8. **Class-name collision found in the smoke round**: my first draft
   reused `files-row` for the Files settings rows — that class is the
   Files pane's 27px tree row, which flattened the settings rows. Renamed
   to `settings-files-row/-nosep/-pills`. Audited every other new class
   name against the existing sheet — no other collisions.
9. **`use-browser`'s index/coordinate clicks silently no-op on some
   elements** (Base UI switches, the unarchive pill) while DOM `.click()`
   and full synthetic mouse sequences work — a tool quirk, not a page bug;
   the acceptance behaviors were all exercised via real event dispatch.
   The archived hover screenshot needed a REAL cursor move (PowerShell
   `Cursor.Position`) because synthetic events never set CSS `:hover`.

### Verification

- `pnpm -r build` (web/) green — typecheck for all 5 packages.
- `@roboco/app` vitest 1130/1130 green (9 new suites/cases:
  `deviceOnlineWithin70SecondsWindow`, `presenceDotFallsBackWhenNoEngineKey`,
  `formatLastSeenBucketsMatchRoboco`, `platformLabelMapsKnownPlatforms`,
  `shortIdTruncatesLongIds` in `tests/devices.test.ts`;
  `descriptorEnabledDefaultsToInstalledExceptMock`,
  `mockHarnessHiddenUnlessOnlyOption` + copy tables + RPC param shapes in
  `tests/harnesses.test.ts`; `conflictOwnerFindsFirstMatchingBinding`,
  `modEnterAlwaysReserved` + record outcomes + notice wording in
  `tests/shortcuts-editor.test.ts`; the archived derivation in
  `tests/archived.test.ts`; `remote-access-view.test.ts` re-pointed at
  `lib/devices`). `@roboco/engine-client` vitest 41/41 green.
- Live smoke round on the embedded bundle (port 27699): boot check green
  (no error boundary, fresh pairing); `/settings` → `/settings/devices`
  redirect; 9-row nav; Devices (presence dot + "This device" + "Added 1m
  ago" + id chip copy/revert + rename round trip incl. the
  silent-empty-swallow quirk + a real rename persisting);
  Agents (Mock row visible-since-alone, toggle refusal surfaces the
  engine guard error verbatim, title pickers open/commit-path, device
  switcher trigger + 220px menu with the "You" tag); Files (delay row
  appears with the autosave toggle, pill clicks persist
  delay/font/word-wrap/show-all through reload); Notifications (master off
  dims rows 2-4 with the aria-description; full-set persists); Shortcuts
  (recording, Escape cancel, valid rebind + per-row Reset, reserved
  mod-enter refusal, conflict refusal naming the owner with the keymap
  untouched, intercept blocks bound actions, send-behavior + reset,
  escape toggle, Restore defaults); Archived (2 rows staged over the wire,
  device · location meta, hover-revealed pill, unarchive removes the
  row); Accounts (device switcher + targetDeviceId plumbing compiles and
  opens; retarget itself cannot stage — see below).

### Screenshot pairs

Web halves captured to `.scratch/web-parity/shots/29/` (the wave-1
convention — desktop halves skipped; desktop references live in research
13): `web-00-boot-check.png`, `web-a-devices.png`,
`web-a2-devices-id-copied.png`, `web-a3-devices-rename-dialog.png`,
`web-b-agents.png`, `web-b2-agents-title-picker.png`,
`web-b3-agents-refused-toggle.png` (extra), `web-c-files.png`,
`web-d-shortcuts-recording.png`, `web-d2-shortcuts-send-behavior.png`
(extra), `web-d3-shortcuts-page-top.png` (extra),
`web-e-archived-hover.png`, `web-e-archived-page.png` (extra, full page),
`web-e2-archived-unarchive-busy.png` (extra — shot raced the RPC; the
busy pill may have already completed), `web-e-shortcuts-conflict-refused.png`,
`web-f-notifications-master-off.png`,
`web-g-accounts-device-switcher.png` (extra), `web-g2-accounts-page.png`
(extra).

Staging skips (all engine-side, not web gaps):

- **(a) "2+ rows, one online one offline"** — the engine-local registry
  publishes exactly its own device row (ADR 0004); a second device row
  cannot be staged against the smoke engine. The single row carries the
  online presence, meta line, badge, and id chip; the offline dot is
  unit-tested.
- **(b) "one harness toggled off and its not-installed hint"** — the smoke
  registry is Mock-only and Mock is installed, and the last-enabled rule
  (plus the engine's own guard) keeps the only harness on. The refused
  toggle's error strip (`web-b3`) shows the guard path instead; the
  hint wording is unit-tested.
- **Remote access empty-link copy** — `web_smoke`'s `EngineCore::
  assemble_with_profile` never calls `remote_access.initialize`, so the
  toggle always answers "Engine is still starting" and the pairing-link
  section cannot render. The string change is code-verified (one-line
  diff, `settings-remote-access.tsx`).
- **Accounts retarget** — only one device exists in the smoke registry, so
  the switcher has no non-local row to pick. The param threading is
  unit-tested (`targetDeviceId` rides every call only when set).

### Shared components addendum (2026-09-18)

Build on components/ui/ + components/base/ (see components/README.md)
— do not hand-roll card shells, cursor lists, menu rows, chips, or
tooltips.
