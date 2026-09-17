# 12 — Keyboard

**What to build:** The web client gets the desktop's whole keyboard. All 19
default bindings exist and are dispatched through one shortcut bus with the
desktop's per-route guards; holding the jump modifier reveals `⌘1`…`⌘9` key-cap
chips on the first nine sidebar rows; Escape gains the opt-in
"stop active agent" behaviour on top of ticket 06's ladder; and the composer's
three key contexts (message / wizard / palette) are reproduced as three explicit
handler policies, including the list of keys the palette deliberately does not
consume. Combos are stored platform-neutrally and rendered with the desktop's
`badge_combo` / `display_combo` formatting, so the Shortcuts settings page
(ticket 29) can read the same table.

**Blocked by:** 06 (Titlebar and main-column chrome), 07 (Right pane host and
multi-instance tabs).

**Status:** ready-for-agent

**Research:** `../../web-client/research/01-shell-chrome.md` §3.10.1a, §4.6,
§4.7, §4.8, §4.9, §4.10, §4.16, §5.3 rows S18–S24, §5.6 rows N8, N9, N11, N12,
N13, N14; `../../web-client/research/04-composer.md` §3.12.

**Desktop reference (for lookups only):**
`crates/ui/src/settings.rs::ShortcutId` (740), `::ShortcutId::ALL` (756),
`::ShortcutId::label` (783), `::ShortcutId::available` (778),
`::ShortcutId::default_combo_on` (799), `::JUMP_DEFAULTS` (722),
`::KeymapConfig` (849), `::heal_jump_slots` (939),
`::heal_reserved_composer_shortcuts` (951),
`::combo_from_keystroke_on` (975), `::combo_modifiers` (1030),
`::jump_hints_visible` (1048), `::modifier_send_hint_visible` (1060),
`::platform_combo` / `::display_combo` / `::badge_combo` (1065-1133);
`crates/ui/src/settings/shortcuts.rs` (groups at 416-464, the
`"Stop active agent with Escape"` toggle at 700-708);
`crates/ui/src/shell.rs::apply_keymap` (295), `::update_jump_hints` (3691),
`::jump_to_session` (3667), `::resolve_shell_escape` (942),
`::capture_escape_surface` (5325); `crates/ui/src/shell/tabs.rs::cycle_target`
(16); `crates/ui/src/composer.rs::init` (1498),
`::MESSAGE_COMPOSER_CONTEXT` / `::GENERIC_COMPOSER_CONTEXT` /
`::PALETTE_SEARCH_CONTEXT` (1347-1349).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/state/shortcuts.ts` | edit (rewrite) | `ShortcutId`, `SHORTCUT_IDS`, `JUMP_SLOTS`, `JUMP_DEFAULTS`, `defaultComboOn`, `shortcutLabel`, `shortcutGroup`, `shortcutAvailable`, `platformCombo`, `displayCombo`, `badgeCombo`, `comboModifiers`, `validOrDefault`, `applyKeymap`, `jumpHintsVisible`, `modifierSendHintVisible`, `comboFromKeystrokeOn`, `healJumpSlots`, `healReservedComposerShortcuts`, `ShortcutEvent`, `onShortcut`, `emitShortcut`, `BROWSER_RESERVED` |
| `web/packages/app/src/state/keymap.ts` | **new** | `KeymapConfig`, `keymapStore`, `useKeymap` (persisted through ticket 03's settings store) |
| `web/packages/app/src/components/app-shell.tsx` | edit | the global `keydown` effects (replacing the two-key handler at `:126-151` and the Escape handler at `:156-172`), `isEditableTarget`, the jump-hint modifier listeners |
| `web/packages/app/src/state/jump-hints.ts` | **new** | `jumpHintStore`, `useJumpHints`, `visibleJumpOrder` |
| `web/packages/app/src/state/escape.ts` | edit | `resolveShellEscape` gains `escapeStopsActiveAgent`; the ladder itself is ticket 06's |
| `web/packages/app/src/components/composer.tsx` | edit | `onKeyDown` — the three key contexts |
| `web/packages/app/src/components/picker-popover.tsx` | edit | the search `<input>`'s `onKeyDown` — the `PaletteSearch` let-through rule |
| `web/packages/app/src/routes/chat-page.tsx` | edit | **delete** the local `Mod+J` capture listener (`:48-58`) |
| `web/packages/app/src/components/new-chat-button.tsx` | edit | `NewChatListener` — must not be gated on `connected` (gap N14) |
| `web/packages/app/src/styles/app.css` | edit | no new classes — the chip's class (`.chat-row-jump`) belongs to ticket 08 |
| `web/packages/app/tests/shortcuts.test.ts` | edit | the default keymap table, combo formatting, `jumpHintsVisible`, `cycleTarget`, `validOrDefault` |

---

## 1. Context a fresh session needs

- The desktop dispatches every shortcut as a gpui `Action` through the focus
  chain, with per-route guards, and gpui runs a **matched binding before any raw
  `on_key_down` listener**. The browser has no keymap-context system, so the same
  scoping is expressed as *which element owns the `keydown` handler and what it
  calls `preventDefault()` on*.
- Today `state/shortcuts.ts` is a 46-line event bus with exactly **two** events
  (`"new-chat"`, `"open-engines"`), and `app-shell.tsx:126-151` binds exactly two
  keys (`Mod+N`, `Mod+B`). `chat-page.tsx:48-58` separately binds `Mod+J` in the
  **capture** phase to the wrong target (the right-pane terminal surface rather
  than the bottom dock).
- Ticket 06 already built: the single capture-phase Escape ladder in
  `state/escape.ts` with `resolveShellEscape`, and the z-index ladder. **This
  ticket adds the key handling around them, not a second listener.**
- Ticket 07 already built: the right-pane surface model and `close_right_plus`,
  which is step 7 of that ladder.
- Ticket 03 owns the persisted client-settings store. `KeymapConfig` and
  `escapeStopsActiveAgent` live there.
- Vocabulary (`CONTEXT.md`): **chat**, **harness**, **engine**, **space**.
  The desktop's shortcut **labels** are user-visible strings and are copied
  verbatim below — `"New session"`, `"Archive session"`, `"Jump to session 1"`,
  etc. Do not translate them here; the Shortcuts page (ticket 29) renders them
  as-is.
- Combos are stored **platform-neutral**: the token `mod` means Cmd on macOS and
  Ctrl elsewhere (`settings.rs:847-848`). A stored combo is a `-`-joined string,
  e.g. `"mod-shift-a"`.

---

## 2. Spec

### 2.1 The default keymap

`ShortcutId::ALL` is `[ShortcutId; 10 + JUMP_SLOTS]` = **19** entries, in this
order (`settings.rs:756-776`). `JUMP_SLOTS = 9` (`settings.rs:719`);
`JUMP_DEFAULTS` is `["mod-1", …, "mod-9"]` (`settings.rs:722-724`).

| # | Variant | Label (verbatim) | Default (macOS) | Default (Win/Linux) | Display (macOS / other) | Group | gpui context |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0 | `CaptureAppshot` | `"Capture Appshot"` | `ctrl-alt-space` | `mod-alt-space` | `Ctrl+Opt+Space` / `Ctrl+Alt+Space` | Appshots | **no gpui binding** — an OS-global hotkey via `appshots::set_shortcut` (`shell.rs:304`) |
| 1 | `SaveFile` | `"Save file"` | `mod-s` | `mod-s` | `Cmd+S` / `Ctrl+S` | Files | `None` (global) |
| 2 | `BrowserReload` | `"Reload browser page"` | `mod-shift-r` | `mod-shift-r` | `Cmd+Shift+R` / `Ctrl+Shift+R` | Browser | **`Some("Browser")`** |
| 3 | `ToggleSidebar` | `"Toggle left sidebar"` | `mod-b` | `mod-b` | `Cmd+B` / `Ctrl+B` | Panels | `None` |
| 4 | `ToggleChanges` | `"Toggle right sidebar"` | `mod-r` | `mod-r` | `Cmd+R` / `Ctrl+R` | Panels | `None` |
| 5 | `ToggleTerminal` | `"Toggle terminal"` | `mod-j` | `mod-j` | `Cmd+J` / `Ctrl+J` | Panels | `None` |
| 6 | `NewSession` | `"New session"` | `mod-n` | `mod-n` | `Cmd+N` / `Ctrl+N` | Sessions | `None` |
| 7 | `NextSession` | `"Next session"` | **`ctrl-tab`** | **`mod-tab`** | `Ctrl+Tab` / `Ctrl+Tab` | Sessions | `None` |
| 8 | `PrevSession` | `"Previous session"` | **`ctrl-shift-tab`** | **`mod-shift-tab`** | `Ctrl+Shift+Tab` / `Ctrl+Shift+Tab` | Sessions | `None` |
| 9 | `ArchiveSession` | `"Archive session"` | `mod-shift-a` | `mod-shift-a` | `Cmd+Shift+A` / `Ctrl+Shift+A` | Sessions | `None` |
| 10-18 | `JumpSession(0..8)` | `"Jump to session 1"`..`"Jump to session 9"` | `mod-1` … `mod-9` | same | `Cmd+1` … / `Ctrl+1` … | Jump to session | `None` |

**Cross-platform trap** (`settings.rs:816-826`, load-bearing): Next/Prev Session
are Ctrl+Tab / Ctrl+Shift+Tab on **every** platform, but the *stored spelling
differs*. Off macOS, Ctrl **is** the primary modifier and stores as `mod`; on
macOS, Ctrl is its own modifier and `mod-tab` would mean Cmd+Tab (eaten by the OS
app switcher). Off macOS `ctrl-tab` and `mod-tab` resolve to the same keystroke,
**but conflict detection compares the STORED spelling** — a default the recorder
cannot reproduce would let a rebind onto the same physical key pass as
conflict-free, bind twice, and silently kill one shortcut. **The web must
reproduce the platform-dependent storage spelling.**

**Non-rebindable fixed bindings applied alongside**

| Combo | Action | Context | Source |
| --- | --- | --- | --- |
| `mod-k` | `AddSpacePalette` | `None` | `shell.rs:362-364` |
| `cmd-,` / `ctrl-,` | `OpenSettings` | `None` | `app_menus.rs:142-146` |
| `cmd-q` / `cmd-h` / `alt-cmd-h` / `cmd-m` / `cmd-w` | Quit / Hide / HideOthers / Minimize / CloseWindow | macOS only | `app_menus.rs:149-153` |
| `mod-t` / `mod-w` / `mod-[` / `mod-]` | NewTab / CloseTab / Back / Forward | `Some("Browser")`, bound **only if** no user shortcut already resolves to the same keystroke | `browser/mod.rs:47-50, 27-34` |

**Groups**, in display order (`shortcuts.rs:416-423`): `"Files"`, `"Browser"`,
`"Panels"`, `"Sessions"`, `"Jump to session"`, `"Appshots"` — but the renderer
**skips "Appshots"** (it has its own sub-page), so five cards render.

**`ShortcutId::available()`** (`settings.rs:778-780`): everything is available
except `CaptureAppshot`, which requires `appshots::is_desktop()`. Unavailable ids
are excluded from conflict detection. **On web, `CaptureAppshot`,
`BrowserReload` and the Browser-context chords are permanently unavailable.**

### 2.1.1 What each action does, and which ticket owns the target

| Variant | Effect | Guard | Target owned by |
| --- | --- | --- | --- |
| `CaptureAppshot` | OS-global screenshot capture | — | **desktop-only; unavailable on web** |
| `SaveFile` | saves the active right-pane Files/File surface (`shell.rs:7773-7784`) | `route == Chat && right_pane_open` | ticket 25 (Files preview and editor) |
| `BrowserReload` | reloads the embedded browser page | Browser context | **desktop-only; unavailable on web** |
| `ToggleSidebar` | `toggle_sidebar` (`shell.rs:7785`) | always, incl. Settings | ticket 06 / `state/layout.ts` |
| `ToggleChanges` | `toggle_right_pane`; if it **closed**, focus returns to the composer (`shell.rs:7798-7807`) | `route == Chat` | ticket 07 |
| `ToggleTerminal` | `toggle_terminal` — the **bottom dock** on the conversation column, with focus handoff: opening cancels the composer's pending focus and focuses the terminal handle; closing focuses the composer (`shell.rs:7768-7772`, `3026-3062`) | `route == Chat` | ticket 26 |
| `NewSession` | `open_new_session` (`shell.rs:7788`) — always works; it routes back to chat itself, so Settings is not a dead spot | always | ticket 15 |
| `NextSession` | `cycle_session(true)` (`shell.rs:7796`) | `route == Chat && !overlay_owns_keyboard` | ticket 08 (sidebar order) |
| `PrevSession` | `cycle_session(false)` (`shell.rs:7797`) | same | ticket 08 |
| `ArchiveSession` | `archive_selected_chat` (`shell.rs:7811-7815`) | `route == Chat && !overlay_owns_keyboard` | ticket 08 |
| `JumpSession(slot)` | first offered to the composer's model picker (`jump_model_slot`); if unhandled **and** no overlay owns the keyboard → `jump_to_session(slot)` (`shell.rs:7822-7828`) | — | ticket 08 (rows), ticket 10 (picker) |
| `AddSpacePalette` (fixed `mod-k`) | toggles the add-space palette (`shell.rs:7832-7839`) | always | ticket 11 |
| `OpenSettings` (fixed `mod-,`) | `open_settings(SettingsSection::Devices)` (`shell.rs:7791-7793`) | always | ticket 28 |

`overlay_owns_keyboard` = `add_space.is_some() || composer.pickers().is_open()`
(`shell.rs:3681-3683`). On the desktop, gpui runs a matched binding *before* any
`on_key_down`, so session-nav shortcuts (cycle / jump / archive) go **quiet**
under the add-space palette or a composer picker (gap N11). On web, check this
flag first in the global handler and return early.

**`jump_to_session(slot)`** (`shell.rs:3667-3673`) reads
`sidebar_visible_order(cx)` — the **displayed** order (sort and grouping permute
it) — and takes the same `open_chat` path a click on that row takes. A slot past
the end does nothing.

### 2.2 `apply_keymap` semantics

`shell.rs:295-303`, `settings.rs:849-957`.

```
valid_or_default(combo, fallback):
    c = platform_combo(combo)
    if Keystroke::parse(c) fails:
        log "unparseable shortcut combo; using default"
        c = platform_combo(fallback)
    return c
```

`apply_keymap` walks `ShortcutId::ALL`, resolves each id's stored combo through
`valid_or_default(stored, default_combo_on(platform))`, and registers the binding
in its gpui context (`None` for all but `BrowserReload`). Then it applies the
fixed bindings in §2.1's second table, skipping any Browser chord whose keystroke
a user shortcut already claims.

**`KeymapConfig`** (`settings.rs:849-868`) — the persisted shape:
`#[serde(default, rename_all = "camelCase")]`, fields `captureAppshot`,
`saveFile`, `browserReload`, `toggleSidebar`, `toggleChanges`, `toggleTerminal`,
`newSession`, `nextSession`, `prevSession`, `archiveSession`, and
**`jumpSession: Vec<String>`** — a **list**, not nine fields, because a
whole-file parse error resets every setting.

* `heal_jump_slots` truncates/pads `jumpSession` to exactly **9**
  (`settings.rs:939-945`).
* `heal_reserved_composer_shortcuts` resets any id whose combo is `"mod-enter"`
  (`settings.rs:951-957`) — that keystroke is reserved for the composer.

**Web `applyKeymap`:** build a lookup from *resolved keystroke string* → action,
rebuilt whenever the keymap changes, and consult it from **one** document-level
`keydown` listener. Actions fan out through the existing `emitShortcut` bus,
grown from two events to cover every action in §2.1.1 (gap N13).

**Recording** (used by the Shortcuts page, but the normalization is shared):
`combo_from_keystroke_on(mac, ctrl, alt, shift, cmd, key)`
(`settings.rs:975-1011`) lowercases the key, returns `None` (keep recording) for
an empty key or one of `ctrl` / `control` / `alt` / `shift` / `cmd` / `platform`
/ `fn`, and emits parts in the **fixed canonical order `mod-ctrl-alt-shift-key`**,
where `mod` is emitted for `cmd || (ctrl && !mac)` and a bare `ctrl` part only on
macOS.

### 2.3 Combo formatting

`settings.rs:1065-1133`

| Function | Rule |
| --- | --- |
| `platform_combo(combo)` | replace the token `mod` with `cmd` on macOS, `ctrl` elsewhere; rejoin with `-` |
| `display_combo(combo)` | `mod` → `Cmd`/`Ctrl`, `alt` → `Opt`/`Alt`, `shift` → `Shift`, anything else → capitalise first char; join with `+` |
| `badge_combo(combo)` | **macOS**: modifier glyphs in the canonical `⌃⌥⇧⌘` order with no separators, then the key with its first char uppercased (`"⌘1"`, `"⇧⌘A"`). **Elsewhere**: exactly `display_combo` (`"Ctrl+1"`) |

`combo_modifiers(combo)` (`settings.rs:1030-1038`): split on `-`, drop the last
part (the key), then `(contains "mod", contains "alt", contains "shift")`.

**Platform detection on web:** derive `isMac` once from
`navigator.platform`/`navigator.userAgentData` and treat it as the desktop's
`cfg!(target_os = "macos")` throughout — storage spelling, `platform_combo`,
`badge_combo`, and the jump-hint primary modifier all read the same flag.

### 2.4 Jump hints

`update_jump_hints` (`shell.rs:3691-3709`):

```
primary = macos ? modifiers.platform : modifiers.control
visible = route == Chat
       && !overlay_owns_keyboard()
       && jump_hints_visible(keymap, primary, alt, shift)
queue_shortcut_revealed = route == Chat
       && !overlay_owns_keyboard()
       && modifier_send_hint_visible(primary, alt, shift)
```

`jump_hints_visible` (`settings.rs:1048-1056`): **false when no modifier is
held**; otherwise true iff some jump-slot shortcut's modifier triple matches
`(primary, alt, shift)` **exactly** — adding Shift or Alt hides the hints, so a
chord like Cmd+Shift+4 never flashes the overlay. A jump combo with **no**
modifiers never shows hints (it would match the resting state and pin the overlay
open).

`modifier_send_hint_visible` (`settings.rs:1060-1062`): `primary && !alt && !shift`.

**Hint lifecycle:**
* `on_modifiers_changed` sets it. **Web:** listen for `keydown` and `keyup` on
  `window` and read `event.metaKey` / `event.ctrlKey` / `event.altKey` /
  `event.shiftKey` on every event; there is no `modifierschanged` event.
* Window deactivation **clears** it (`observe_window_activation`,
  `shell.rs:7703-7715`, plus the render guard at 7739-7745). A Cmd+Tab away
  swallows the key-up, so without this the chips would stick for good (gap N12).
  **Web:** clear on `window` `blur` and on `visibilitychange` when hidden.
* `render_active_rows` also re-checks `!overlay_owns_keyboard()` at **render**
  time, so the chips drop the **frame** a popover opens, not on the next modifier
  event (`spaces.rs:1446-1448`).

**The chip** (`shell.rs:4507-4518`, §3.10.1a) — cut to the sidebar PR badge's
exact cloth. It replaces the status/time corner on the **first nine visible
rows**, taking the corner outright, **above hover and above the status word**, so
all nine chips appear together (`shell.rs:4447-4451`):

| Property | Value | Source |
| --- | --- | --- |
| height | `16`, `flex-none` | 4507-4508 |
| display | `flex row; align-items:center` | 4509-4511 |
| padding-x | `4` | 4512 |
| radius | `4` | 4513 |
| background | `theme.text_muted.opacity(0.08)` | 4514 |
| font-size | `ui_rems(10.0)`, weight `MEDIUM` | 4515-4516 |
| colour | `theme.text_muted.opacity(0.85)` | 4517 |
| font-family | `theme.font_mono` | 4518 |
| text | `badge_combo(combo)` — macOS `"⌘1"`, `"⇧⌘A"`; elsewhere `"Ctrl+1"` | `settings.rs:1108-1133` |

> **The chip rendering itself is ticket 08** (it lives inside the sidebar chat
> row's corner). This ticket owns the **key handling**: the `visible` predicate,
> the modifier-hold lifecycle, the blur/visibility clearing, the ordered slot →
> chat mapping, and `badge_combo`'s output. Ticket 08 consumes
> `useJumpHints()` and renders the chip with the geometry above under its own
> class `.chat-row-jump`. **Do not repurpose `.identity-badge`** — that class is
> an INVENTED titlebar element deleted by tickets 04/06; ticket 08 writes the
> chip's rule fresh (gap S18, and ticket 08 §5 says the same).

### 2.5 Escape

Ticket 06 built the two-phase ladder. This ticket adds the **setting** and the
**priority order** as it applies to composer surfaces.

`escapeStopsActiveAgent` is a persisted client setting (ticket 03), surfaced on
the Shortcuts page as **`"Stop active agent with Escape"`**; `Restore defaults`
sets it to `false`, so it is **opt-in** (`shortcuts.rs:700-708`).

**Escape priority order** (first match wins):

1. **Completion popup** — an open mention (`@`) or slash (`/`) completion menu in
   the composer dismisses; the key is consumed. *(Ticket 14 registers it.)*
2. **Wizard** — an active input-request wizard pages back; the key is swallowed
   either way. Only when the textarea is unfocused or empty. *(Ticket 14.)*
3. **Popover / menu / dialog** — the ticket-06 ladder
   (`capture_escape_surface`): delete-confirm, chat menu, space menu and user
   menu **block** Escape without closing (they have a Cancel path); rename,
   rename-space and add-space dialogs clear; the spaces menu and the right-pane
   `+` menu close; otherwise the active `Changes` surface's `handle_escape` runs.
4. **Interrupt** — `resolve_shell_escape` (`shell.rs:942-971`):

```
if key != "escape"                                       → OtherKey
else if blocking_overlay                                 → Blocked
else if !escape_stops_active_agent
     || route != Chat
     || interrupting                                     → Ignored
else if indicator ∈ {Working, AwaitingInput}
     → selected_chat.map(InterruptChat).unwrap_or(Ignored)
else                                                     → Ignored
```

Outcomes (`on_key_down`, 5414-5430): `Blocked` → `stopPropagation` only;
`InterruptChat(id)` → `stopPropagation` + `composer.interrupt_chat(id)`; the rest
→ nothing.

> Today `composer.tsx:392` treats `Escape` as "submit" when working and the field
> is empty. That is **not** the desktop's behaviour and must be removed; the
> interrupt is step 4 above and is gated on the opt-in setting.

### 2.6 Tab

`on_key_down` (`shell.rs:5391-5403`): a bare `Tab` (no ctrl/alt/platform) walks
the accessible focus order — `Shift+Tab` → `focus_prev()`, `Tab` →
`focus_next()` — then `stopPropagation`s and returns. Inputs and completion menus
consume Tab first (they are deeper in the dispatch chain).

On web this is native. The requirement is that **nothing at shell level swallows
Tab**, and that the composer's palette-context inputs let it bubble (§2.8).

### 2.7 Chat cycling — `cycle_target`

`shell/tabs.rs:16-32`

```
if order.is_empty()            → None
at = position of `selected` in `order`            // None if absent
next = match (at, forward)
    (Some(at), true)  → (at + 1) % len
    (Some(at), false) → (at + len − 1) % len
    (None,     true)  → 0
    (None,     false) → len − 1
→ Some(order[next])
```

A selection that has left the list (archived elsewhere mid-cycle) is treated
exactly like "no selection" rather than dead-ending. A single-row list cycles to
itself in both directions — deliberately, so the shortcut never looks broken.

`order` is `sidebar_visible_order(cx)` — the **displayed** order, after sort and
grouping (ticket 08 owns that function).

### 2.8 The three composer key contexts

`composer.rs:1347-1349, 1392, 1498-1593, 3744`, research 04 §3.12.

`ComposerInput` is used by three kinds of field, and the *only* thing that
distinguishes their keyboards is the `key_context` string the element declares.
GPUI resolves a keystroke against the bindings registered for that context and
**dispatches a matched binding before any raw `on_key_down` listener**, so a key
that is *deliberately left unbound* in a context is the mechanism by which it
reaches the surrounding frame. All three binding sets are registered once at boot
by `composer::init(cx, send_behavior)` (1498), in the order palette → generic →
message (1591-1593).

| constant (composer.rs) | string | which inputs declare it | bindings that apply | how it is entered / left |
|---|---|---|---|---|
| `GENERIC_COMPOSER_CONTEXT` (1347) | `"Composer"` | the default for `ComposerInput::new` (1718–1720) — picker search fields, rename fields, and **the main composer while the question wizard is active** (`message_input_context(true)`, 1392, applied at 5925–5927) | everything in `input_bindings("Composer")` **plus** `enter → Submit` (1500–1504). No `ModifiedSubmit`, no `MessageNewlineOrAccept` | set by `set_key_context` when a wizard opens; restored to `MESSAGE_COMPOSER_CONTEXT` by `wizard_finish` (6811) and by `on_state_changed` when the latch releases |
| `MESSAGE_COMPOSER_CONTEXT` (1348) | `"MessageComposer"` | the main chat composer only (`ComposerInput::with_context("Do anything…", MESSAGE_COMPOSER_CONTEXT, cx)`, 4198) | everything in `input_bindings("MessageComposer")` **plus** the two bindings from `message_enter_bindings(send_behavior, platform_combo("mod-enter"))` (1506–1525) — `Enter`: `enter → Submit`, `{cmd\|ctrl}-enter → ModifiedSubmit`; `ModEnter`: `enter → MessageNewlineOrAccept`, `{cmd\|ctrl}-enter → ModifiedSubmit` | the resting context; only the wizard swaps it out |
| `PALETTE_SEARCH_CONTEXT` (1349) | `"PaletteSearch"` | palette / command-search filter fields (`ComposerInput::with_context(…, "PaletteSearch", cx)`) — **never** the composer | **text-editing keys only** (1538–1590): `backspace`, `delete`, `home`, `end`, `shift-left`, `shift-right`, `cmd-left`→Home, `cmd-right`→End, `shift-cmd-left`→SelectHome, `shift-cmd-right`→SelectEnd, `cmd-backspace`→DeleteToLineStart, `{alt on macOS \| ctrl}-backspace/-delete/-left/-right` and their `shift-` selecting variants, and `{cmd\|ctrl}-{a,c,x,v,z}` + `shift-{cmd\|ctrl}-z` | never changes |

**Keys deliberately NOT bound in `PaletteSearch`** — bare `up`, `down`, `left`,
`right`, `enter`, `tab`, `shift-enter`, `shift-up`, `shift-down`, `cmd-up`,
`cmd-down`, `shift-cmd-up`, `shift-cmd-down`, `cmd-delete`. Because matched
bindings outrank raw key listeners, leaving these unbound is what lets the
palette's own `on_key_down` frame receive them for row navigation and activation
(comment at 1532–1536).

**What the web must do to reproduce the scoping.**

| desktop context | web equivalent |
|---|---|
| `MESSAGE_COMPOSER_CONTEXT` | the chat composer `<textarea>`'s own `onKeyDown`. It handles `Enter` / `Shift+Enter` / `Mod+Enter` per the `ComposerSendBehavior` setting and `Escape` (dismiss an open mention/slash popup), and calls `preventDefault()` only for the keys it consumes. Every other editing key is native — do not intercept it. |
| `GENERIC_COMPOSER_CONTEXT` | the **same** `<textarea>` while the wizard is mounted. Swap its Enter handling to "bare Enter always submits the page" and drop `ModifiedSubmit`. The wizard panel's own `onKeyDown` must sit on the panel wrapper and guard against double handling exactly as `on_wizard_key` does: bare digits `1`–`9` select only when the textarea is unfocused **or** empty (and then `stopPropagation()` so the digit is not also typed); `Enter` advances only when the textarea is **not** focused; `Escape` pages back only when the textarea is unfocused or empty, and is swallowed either way. |
| `PALETTE_SEARCH_CONTEXT` | the palette/picker search `<input>`. Its `onKeyDown` must **not** `preventDefault()` bare `ArrowUp`/`ArrowDown`/`ArrowLeft`/`ArrowRight`/`Enter`/`Tab` — let them bubble to the palette container's handler, which owns row navigation and activation. Text-editing keys are native in a browser, so nothing needs binding; the whole job is deciding what to let through. |

The practical consequence for the port: the composer textarea needs **two**
Enter/Escape modes (message vs wizard) driven by "is a wizard open", and any
search input the composer renders (the picker popovers' filters) must use the
palette rule, not the composer rule.

> **Send behaviour:** the desktop's `ComposerSendBehavior` default is `Enter`
> (bare Enter submits, `Mod+Enter` also submits). The web hard-codes `Mod+Enter`
> to send and bare Enter to newline (`composer.tsx:381-397`), which is the
> desktop's **non**-default (00-index finding 7). Wire the setting from ticket 03
> and honour it; **ticket 13 owns the send path itself** — this ticket owns only
> which key maps to which intent.
> `heal_reserved_composer_shortcuts` guarantees no rebindable shortcut can ever
> be `"mod-enter"`.

### 2.9 Browser-reserved combos

The browser claims some of the desktop's keystrokes before any page listener
runs, or claims them unreliably across browsers. Publish this table from
`state/shortcuts.ts` as `BROWSER_RESERVED` so the **Shortcuts settings page
(ticket 29) reads the same table** rather than restating it.

| Combo | Browser behaviour | What the web client does instead |
| --- | --- | --- |
| `Mod+W` | Closes the tab/window. Not interceptable in any browser. | **Never bound.** No web action uses it. On desktop it is the macOS CloseWindow / Browser-context CloseTab chord; both are desktop-only. |
| `Mod+N` | Opens a new browser window in Chrome/Edge/Safari; **interceptable in Firefox only**. | `NewSession` stays bound and calls `preventDefault()`. Where the browser wins, the action is unreachable by keyboard — the titlebar `+` (ticket 06) is the guaranteed path, and the Shortcuts page marks the row **"Reserved by your browser"**. |
| `Mod+T` | Opens a new browser tab; not interceptable. | Not bound. The desktop's `mod-t` is a Browser-context chord — desktop-only. |
| `Ctrl+Tab` / `Ctrl+Shift+Tab` | Cycles browser tabs in Chrome/Edge (not interceptable); reaches the page in Firefox and in Safari when "Use ⌘1–⌘9 to switch tabs" is off. | `NextSession` / `PrevSession` stay bound with the desktop's stored spelling and call `preventDefault()`. Where the browser wins, the Shortcuts page marks the row **"Reserved by your browser"**. Chats remain reachable via `Mod+1`…`Mod+9` and the sidebar. |
| `Mod+1` … `Mod+9` | Switches browser tabs in Chrome/Edge/Firefox by default; **interceptable** on a focused page in all three (they fire `keydown` first). Safari honours the "Use ⌘1–⌘9 to switch tabs" preference. | `JumpSession(0..8)` stays bound and calls `preventDefault()`. This is the primary chat-switching path on web. |
| `Mod+R` / `Mod+Shift+R` | Reload / hard reload; **interceptable** via `preventDefault()` in all major browsers. | `ToggleChanges` (`Mod+R`) stays bound and calls `preventDefault()`. `Mod+Shift+R` (`BrowserReload`) is **unavailable on web** — it has no web target. |
| `Mod+S` | Save page; interceptable. | `SaveFile` stays bound and calls `preventDefault()` (target: ticket 25). |
| `Mod+B` / `Mod+J` / `Mod+K` | `Mod+B` bolds in some rich-text contexts only; `Mod+J` opens Downloads in Chrome (interceptable); `Mod+K` focuses the address bar in Firefox (interceptable). All reach the page. | `ToggleSidebar`, `ToggleTerminal`, `AddSpacePalette` stay bound and call `preventDefault()`. |
| `Mod+,` | Nothing in any browser. | `OpenSettings` stays bound. |
| `Ctrl+Alt+Space` / `Mod+Alt+Space` | OS-level. | `CaptureAppshot` is **unavailable on web**. |
| `Mod+Q` / `Mod+H` / `Alt+Cmd+H` / `Mod+M` | OS-level (macOS). | Never bound — desktop-only app-menu chords. |

**Rules this table encodes, which the implementation must follow:**
1. A combo the browser owns outright (`Mod+W`, `Mod+T`) is **never registered**;
   it must not appear in the keymap with a silent no-op.
2. A combo that is usually interceptable is registered normally and calls
   `preventDefault()` on match.
3. A combo the browser may or may not deliver (`Mod+N`, `Ctrl+Tab`) is registered
   **and** flagged, so the Shortcuts page can render the caveat.
4. `shortcutAvailable(id)` returns `false` on web for `CaptureAppshot` and
   `BrowserReload`; unavailable ids are excluded from conflict detection, exactly
   as `ShortcutId::available()` does.
5. The Shortcuts page (ticket 29) imports `BROWSER_RESERVED` and renders its
   third column as the row's caveat text. It must not hard-code a second copy.

### 2.10 Where each handler lives

| Handler | File | Phase | Notes |
| --- | --- | --- | --- |
| global keymap dispatch | `components/app-shell.tsx` (effect) → `state/shortcuts.ts::applyKeymap` | bubble on `window` | early-return when `isEditableTarget(event.target)` **unless** the action is explicitly allowed while typing (the desktop's inputs let unbound keys through, so only bare-key actions need the guard); early-return when `overlayOwnsKeyboard()` for cycle / jump / archive |
| Escape ladder | `state/escape.ts` (built by ticket 06) | **capture** on `document`, then bubble | this ticket adds `escapeStopsActiveAgent` and steps 1–2 registrations |
| jump-hint modifiers | `state/jump-hints.ts` | `keydown` / `keyup` on `window`, plus `blur` / `visibilitychange` | never `preventDefault()` |
| composer keys | `components/composer.tsx::onKeyDown` | on the `<textarea>` | two modes: message / wizard |
| palette search keys | `components/picker-popover.tsx` | on the `<input>` | let-through list from §2.8 |
| `Mod+J` | **deleted** from `routes/chat-page.tsx:48-58` | — | moves into the global keymap, targeting the bottom dock (ticket 26) |

---

## 3. Pure logic to port

All signatures target `web/packages/app/src/state/shortcuts.ts`; tests go to
`web/packages/app/tests/shortcuts.test.ts`.

### 3.1 The keymap table (§4.16)

```ts
const JUMP_SLOTS = 9;
const JUMP_DEFAULTS: readonly string[];              // ["mod-1" … "mod-9"]
type ShortcutId =
  | "captureAppshot" | "saveFile" | "browserReload" | "toggleSidebar"
  | "toggleChanges" | "toggleTerminal" | "newSession" | "nextSession"
  | "prevSession" | "archiveSession" | { jumpSession: number };
const SHORTCUT_IDS: readonly ShortcutId[];           // 19 entries, in §2.1's order
defaultComboOn(id: ShortcutId, isMac: boolean): string;
shortcutLabel(id: ShortcutId): string;
shortcutGroup(id: ShortcutId): string;
shortcutAvailable(id: ShortcutId): boolean;          // false for appshot + browserReload
```

Cases to assert: `SHORTCUT_IDS.length === 19`; the order matches §2.1;
`defaultComboOn("nextSession", true) === "ctrl-tab"` and
`defaultComboOn("nextSession", false) === "mod-tab"`; same for `prevSession`;
every other id is platform-identical except `captureAppshot`
(`ctrl-alt-space` vs `mod-alt-space`).

### 3.2 Combo formatting (§4.10)

```ts
platformCombo(combo: string, isMac: boolean): string;
displayCombo(combo: string, isMac: boolean): string;
badgeCombo(combo: string, isMac: boolean): string;
comboModifiers(combo: string): { mod: boolean; alt: boolean; shift: boolean };
validOrDefault(combo: string, fallback: string, isMac: boolean): string;
```

Cases: `badgeCombo("mod-1", true) === "⌘1"`;
`badgeCombo("mod-shift-a", true) === "⇧⌘A"`;
`badgeCombo("mod-1", false) === "Ctrl+1"`;
`displayCombo("mod-shift-a", false) === "Ctrl+Shift+A"`;
`comboModifiers("mod-shift-a")` → `{mod:true, alt:false, shift:true}`;
`validOrDefault("nonsense-!!", "mod-b", false)` falls back to `"ctrl-b"`.

### 3.3 Jump hints (§4.9)

```ts
jumpHintsVisible(jumpCombos: readonly string[],
                 primary: boolean, alt: boolean, shift: boolean): boolean;
modifierSendHintVisible(primary: boolean, alt: boolean, shift: boolean): boolean;
```

Cases: no modifier held → `false`; `mod` alone with `mod-1..9` defaults → `true`;
`mod+shift` → `false`; a jump combo with no modifiers → `false`.

### 3.4 Chat cycling (§4.6)

```ts
cycleTarget(order: readonly string[], selected: string | null,
            forward: boolean): string | null;
```

Desktop tests (`tabs.rs:373-423`) → web tests:
- `steps_forward_and_back_through_the_list`
- `wraps_at_both_ends`
- `a_single_session_cycles_to_itself`
- `no_selection_enters_the_list_from_the_matching_end`
- `an_empty_list_has_nothing_to_select`

### 3.5 Recording and healing

```ts
comboFromKeystrokeOn(isMac: boolean, ctrl: boolean, alt: boolean,
                     shift: boolean, cmd: boolean, key: string): string | null;
healJumpSlots(slots: readonly string[]): string[];              // exactly 9
healReservedComposerShortcuts(config: KeymapConfig): KeymapConfig;  // resets "mod-enter"
```

Cases: a bare modifier key returns `null`; `combo_from_keystroke_on(false, true,
false, true, false, "A")` → `"mod-shift-a"`; on macOS a real Ctrl emits a bare
`ctrl` part; `healJumpSlots` pads a 3-entry list to 9 and truncates a 12-entry
list to 9; `healReservedComposerShortcuts` resets any `"mod-enter"` to its
default.

### 3.6 Escape resolution (§4.7)

`resolveShellEscape` is ticket 06's (`state/escape.ts`). This ticket only adds
the `escapeStopsActiveAgent` input and its three desktop tests, if ticket 06 did
not already: `escape_interrupts_only_the_active_live_chat` (`shell.rs:8300`),
`escape_ignores_non_live_or_ineligible_views` (8328),
`escape_interrupt_is_opt_in` (8380).

---

## 4. Gaps this ticket closes

Copied verbatim from research §5, filtered to this ticket.

### From 01 §5.3 Sidebar

| # | Item | Kind | Desktop | Web | Fix |
| --- | --- | --- | --- | --- | --- |
| S18 | **Jump-hint chips** | MISSING | holding the jump modifier replaces the status/time corner on the first nine rows with a mono key-cap chip (§3.10.1a, §4.9) | nothing — no jump shortcuts, no hints | implement `jump_hints_visible`, `badge_combo`, and the chip. `.identity-badge` in CSS is already exactly this chip's geometry — repurpose it *(superseded: tickets 04/06 delete `.identity-badge`; ticket 08 writes `.chat-row-jump` with the same geometry)* |
| S19 | `⌘1..⌘9` jump shortcuts | MISSING | 9 bindings (§4.16) | none | add |
| S20 | `Ctrl+Tab` / `Ctrl+Shift+Tab` chat cycling | MISSING | `cycle_session` over `sidebar_visible_order` (§4.6) | none | add |
| S21 | `Mod+Shift+A` archive chat | MISSING | `ArchiveSession` (`shell.rs:7811`) | none | add |
| S22 | `Mod+R` toggle right pane | MISSING | `ToggleChanges` (`shell.rs:326-330, 7798`) | none | add |
| S23 | `Mod+J` toggle terminal | WRONG BEHAVIOUR | `ToggleTerminal` toggles the **bottom dock** per chat, with focus handoff (§3.19) | `chat-page.tsx:50` (capture phase) calls `rightPaneStore.show(chatId, "terminal")` — the *right-pane* terminal surface | wire `Mod+J` to the bottom dock; the right-pane terminal is a separate surface reached from the `+` menu |
| S24 | `Mod+K` add-space palette | MISSING | fixed binding (`shell.rs:362-364`) | none | other doc, but note it here |

### From 01 §5.6 Routing, overlays, global

| # | Item | Kind | Desktop | Web | Fix |
| --- | --- | --- | --- | --- | --- |
| N8 | Escape handling | MISSING | a two-phase model: **capture** resolves shell surfaces (`capture_escape_surface`, §4.7) before a focused descendant can eat the key; **bubble** interrupts the live chat when `escape_stops_active_agent` | per-component `window keydown` listeners in bubble phase; no interrupt, no `escape_stops_active_agent` setting | implement the ordered ladder in a single capture-phase document listener |
| N9 | Escape blocks rather than closes for some surfaces | MISSING | delete-confirm, chat menu, space menu and user menu **block** Escape (returning `true`) without closing — they already have a Cancel path | Escape closes every menu | match the ladder exactly |
| N11 | `overlay_owns_keyboard` guard | MISSING | session-nav shortcuts (cycle / jump / archive) go **quiet** under the add-space palette or a composer picker, because gpui runs a matched binding before any `on_key_down` | no such guard (and no such shortcuts) | add alongside S19–S21 |
| N12 | Jump hints cleared on window blur | MISSING | `observe_window_activation` clears them — a Cmd+Tab away swallows the key-up | n/a | on web use `blur` / `visibilitychange` on `window` |
| N13 | Shortcut bus | WRONG BEHAVIOUR | every action is a gpui `Action` dispatched through the focus chain, with per-route guards | `state/shortcuts.ts` has exactly **two** events (`"new-chat"`, `"open-engines"`) | grow the bus to cover every action in §4.16 |
| N14 | **Silent Mod+N failure** | WRONG BEHAVIOUR | `NewSession` always works (`open_new_session` routes back to chat itself, so Settings is not a dead spot) | `NewChatListener` only subscribes while `connected` (`new-chat-button.tsx:65`), so while disconnected **Mod+N and the titlebar `+` do nothing, with no feedback at all** | post a notice, or disable the `+` visibly |

### From 04 §5 Composer

| # | Item | Kind | Desktop | Web | Fix |
| --- | --- | --- | --- | --- | --- |
| — | Key-binding **contexts** | MISSING | three named contexts — `MESSAGE_COMPOSER_CONTEXT`, `GENERIC_COMPOSER_CONTEXT` (wizard), `PALETTE_SEARCH_CONTEXT` — each with a different Enter/navigation-key policy (§3.12) | one `onKeyDown` on the textarea with a single fixed policy (composer.tsx:381–397); picker search inputs have no let-through rule | Give the textarea two modes (message vs wizard) and make every picker/palette search input let bare arrows, Enter and Tab bubble to its list frame |

---

## 5. Do not

**INVENTED — remove, do not re-add:**
- The local `Mod+J` capture-phase listener in `routes/chat-page.tsx:48-58` that
  opens the **right-pane** terminal surface. `Mod+J` is the bottom dock (S23).
- `composer.tsx:392`'s `Escape` → submit-while-working. Escape never sends; the
  interrupt is step 4 of the ladder and is opt-in.
- Gating `NewChatListener` on `connected` (`new-chat-button.tsx:65`) — `Mod+N`
  must never fail silently (N14). Either post a sidebar notice or disable the `+`
  visibly; do not leave a dead key.

**Desktop-only — do not attempt:**
- `CaptureAppshot` and the `appshots::set_shortcut` OS-global hotkey.
- `BrowserReload` and every `Some("Browser")`-context chord
  (`mod-t` / `mod-w` / `mod-[` / `mod-]`).
- The macOS app-menu bindings `cmd-q` / `cmd-h` / `alt-cmd-h` / `cmd-m` /
  `cmd-w` (`app_menus.rs:149-153`).
- `restore_mounted_focus` and gpui's focus-chain dispatch. The *observable*
  rules that do port: after clicking away from an input, global shortcuts still
  work; closing the right pane or the terminal returns focus to the composer.
- `Keystroke::parse`. On web, `validOrDefault` validates against a small parser
  of the `mod-ctrl-alt-shift-key` grammar — same contract, same log line, same
  fallback.

**Owned by other tickets — do not build:**
- The **Escape ladder itself**, the z-index ladder, the titlebar `+`, and the
  `useChrome` wiring → **ticket 06**.
- `close_right_plus`, the right-pane surface model, `ToggleChanges`'s target →
  **ticket 07**.
- The **jump-hint chip's rendering** inside the sidebar row's corner, and
  `sidebar_visible_order` (which both `cycleTarget` and `jump_to_session` read)
  → **ticket 08**.
- The **popover primitive** (`menu_step`, `classify_key`, arrow-key navigation in
  every popover) → **ticket 09**; the palette let-through rule here only decides
  what *reaches* that handler.
- The composer's pickers and the `jump_model_slot` first-refusal on
  `JumpSession` → **ticket 10**.
- The add-space palette (`Mod+K`'s target) → **ticket 11**.
- The **send path** (Send / Queue / Stop, `ComposerSendBehavior`'s effect) →
  **ticket 13**. This ticket maps keys to intents; ticket 13 executes them.
- The mention/slash completion popups and the input-request wizard (steps 1–2 of
  the Escape order, and the wizard's digit/Enter/Escape rules) → **ticket 14**.
- The **bottom terminal dock** that `Mod+J` toggles, and its focus handoff →
  **ticket 26**.
- The **Shortcuts settings page** — the five group cards, the recorder UI, the
  conflict list, the `"Stop active agent with Escape"` toggle, `Restore
  defaults` → **ticket 29**. It must import `SHORTCUT_IDS`, `shortcutLabel`,
  `shortcutGroup`, `shortcutAvailable`, `displayCombo`, `comboFromKeystrokeOn`
  and `BROWSER_RESERVED` from `state/shortcuts.ts`; this ticket must export all
  of them.

---

## 6. Acceptance

- [ ] `SHORTCUT_IDS` has exactly 19 entries in §2.1's order, with the verbatim
      labels and the platform-dependent `nextSession` / `prevSession` storage
      spellings (`ctrl-tab` on macOS, `mod-tab` elsewhere).
- [ ] `KeymapConfig` persists through ticket 03's store with `jumpSession` as a
      **list**; `healJumpSlots` forces exactly 9; `healReservedComposerShortcuts`
      resets any `"mod-enter"`.
- [ ] `applyKeymap` builds one keystroke→action lookup, consulted by **one**
      `window` `keydown` listener; `emitShortcut` covers every action in §2.1.1.
- [ ] Working bindings, verified by hand: `Mod+B` sidebar, `Mod+R` right pane,
      `Mod+J` **bottom dock**, `Mod+N` new chat (even while disconnected),
      `Mod+Shift+A` archive, `Ctrl+Tab` / `Ctrl+Shift+Tab` cycle, `Mod+1`…`Mod+9`
      jump, `Mod+K` palette, `Mod+,` settings, `Mod+S` save.
- [ ] Cycle / jump / archive go **quiet** while the add-space palette or a
      composer picker is open (`overlayOwnsKeyboard`).
- [ ] Holding the primary modifier alone reveals the jump chips; adding Shift or
      Alt hides them; releasing hides them; `blur` and `visibilitychange` clear
      them; opening a popover drops them on the same frame.
- [ ] Chip text is `badgeCombo` output: `⌘1` on macOS, `Ctrl+1` elsewhere, in the
      16 px / `padding-x 4` / radius 4 / `text_muted@8 %` background /
      `text_muted@85 %` / 10 px MEDIUM mono geometry, on the **first nine
      visible** rows.
- [ ] Escape follows the order completion > wizard > popover > interrupt; menus
      and confirm dialogs **block** without closing; the interrupt fires only when
      `escapeStopsActiveAgent` is on (default **off**), the route is chat, the
      indicator is Working or AwaitingInput, and no interrupt is already in
      flight.
- [ ] The composer textarea has two modes: message (Enter policy from the
      `ComposerSendBehavior` setting; `Mod+Enter` always submits) and wizard
      (bare Enter submits the page; no `ModifiedSubmit`), and calls
      `preventDefault()` only on keys it consumes.
- [ ] Every picker/palette search `<input>` lets bare
      `ArrowUp`/`ArrowDown`/`ArrowLeft`/`ArrowRight`/`Enter`/`Tab` bubble to its
      list frame; none of the keys in the "deliberately NOT bound" list is
      `preventDefault()`ed there.
- [ ] Bare `Tab` is never swallowed at shell level.
- [ ] `BROWSER_RESERVED` is exported from `state/shortcuts.ts` with the §2.9
      rows; `shortcutAvailable` returns `false` for `captureAppshot` and
      `browserReload`; a test asserts the Shortcuts page's caveat text comes from
      this table and is not duplicated.
- [ ] The `Mod+J` listener in `chat-page.tsx` and the two-key handler in
      `app-shell.tsx:126-151` are gone.
- [ ] Unit tests (`tests/shortcuts.test.ts`):
      `steps_forward_and_back_through_the_list`,
      `wraps_at_both_ends`,
      `a_single_session_cycles_to_itself`,
      `no_selection_enters_the_list_from_the_matching_end`,
      `an_empty_list_has_nothing_to_select` (`tabs.rs:373-423`);
      plus cases for `defaultComboOn` (19 rows, both platforms), `badgeCombo` /
      `displayCombo` / `platformCombo`, `comboModifiers`, `validOrDefault`,
      `jumpHintsVisible`, `modifierSendHintVisible`, `comboFromKeystrokeOn`,
      `healJumpSlots`, `healReservedComposerShortcuts`.
      `escape_interrupts_only_the_active_live_chat`,
      `escape_ignores_non_live_or_ineligible_views`,
      `escape_interrupt_is_opt_in` → `tests/escape.test.ts`.
- [ ] Screenshot pair, desktop vs web, states: (a) primary modifier held, sidebar
      showing nine `⌘1`…`⌘9` chips; (b) modifier + Shift held — no chips;
      (c) a popover open with the modifier held — no chips; (d) the Shortcuts
      settings page's five group cards showing `displayCombo` text *(ticket 29
      renders it; capture whatever exists)*.
- [ ] `pnpm -r build` green; `pnpm --filter @roboco/app test` green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)
