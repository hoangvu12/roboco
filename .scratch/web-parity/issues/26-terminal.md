# 26 — Terminal

**What to build:** Two independent terminal hosts exist, matching desktop:
a bottom drawer under the chat column (toggled by Mod-J, resizable by
dragging its top edge, double-click resets to 280px, 200ms open/close
tween) and the right pane's Terminal surface tabs (one pane chip per
terminal instance, using the same PTYs as the pane host's other tab kinds).
Tabs get real glyph icons instead of literal `+`/`×` text, the emulator's
cursor becomes a translucent theme-colored overlay instead of xterm's
opaque block, scrollback grows to the desktop's 10,000 lines, a styled
hover-only scrollbar overlay replaces the OS default, and focus is claimed
exactly once per open/select instead of being re-stolen on every pixel of
a resize drag.

**Blocked by:** 03 (Client settings store), 07 (Right pane host and
multi-instance tabs)

**Status:** done

**Research:** `../../web-client/research/11-terminal-preview.md` §2, §3
(all subsections), §4, §5 (rows quoted below); the dev-server preview
material in that file (§2's "Dev-server preview" branch, `preview_body`,
`PreviewPanel`) is **not** part of this ticket — it belongs to the
Browser/preview surface (desktop-only native browser; `preview-panel.tsx`
is an already-accepted substitute, tracked elsewhere) — do not touch
`components/preview-panel.tsx`, `lib/preview.ts`, or `state/preview.ts`
here.

**Desktop reference (for lookups only):**
- `crates/ui/src/shell.rs`: `render_terminal_container` (~6259–6368, drawer
  resize handle/highlight/height tween), `on_terminal_drag` (3064–3084),
  `render_right_pane` (~6449–6512, embeds `TerminalPanel` as a pane
  surface), `render_right_tab_strip` (~6687–7120, per-terminal-tab pane
  chips), `opening_terminals_focuses_the_terminal_once` test
  (9041–9120)
- `crates/ui/src/terminal/panel.rs` (1921 lines): `TerminalPanel::render`
  (1650–1725), `render_tab_bar` (1395–1642), `render_scrollbar`
  (1270–1314), `spawn_session`/`apply_stream_event` (PTY lifecycle), pure
  helpers at 1733–1899 (`clamp_terminal_height`, `backoff_ms`,
  `reorder_tabs`, `drop_index`, `slide_offset`, `active_after_reorder`,
  `active_after_close`, `exit_message`, `shell_title`,
  `decode_base64`/`encode_base64`)
- `crates/ui/src/terminal/view.rs` (1105 lines): `TerminalElement`
  (357–606), `keystroke_bytes`/`control_bytes`/`paste_bytes`
  (746–903), `InputCoalescer` (906), `cell_at` (1007–1099),
  `extended_indexed_rgb` (921–945)
- `crates/ui/src/terminal/emulator.rs` (740 lines): `Emulator`,
  `SCROLLBACK_LINES` (44), bell capture (174, 228–231)
- `crates/ui/src/theme.rs` (`TerminalColors` ~750, `Theme::cursor` ~713),
  `crates/theme/src/lib.rs`, `crates/theme/src/builtins.rs` (palette
  derivation)
- `crates/proto/src/motion.rs` (`RESIZE`, `TAB_SLIDE` motion specs)
- `crates/ui/src/settings.rs` (`TERMINAL_MIN_HEIGHT`, `TERMINAL_MAX_VH`,
  `TERMINAL_DEFAULT_HEIGHT`, `TERMINAL_ABS_MAX_HEIGHT`, `SAVE_DEBOUNCE_MS`,
  `ShortcutId::ToggleTerminal` default `mod-j`)

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/terminal/store.tsx` | edit | `TerminalStore`; drawer-vs-pane host separation, `focusActive` dependency fix, `retheme` cursor role wiring |
| `web/packages/app/src/terminal/terminal-dock.tsx` | edit | `TerminalDock`, `DockBody`, `TabBar`, `TabChip`; icons, double-click height reset, drawer open/close tween, scrollbar overlay mount point |
| `web/packages/app/src/terminal/session.ts` | edit | `TerminalSessionController`; verify PTY lifecycle/backoff/coalescing already match (§4 — likely no changes needed, confirm) |
| `web/packages/app/src/terminal/tabs.ts` | no change expected | pure logic already ported verbatim — verify while reading, do not restructure |
| `web/packages/app/src/terminal/theme.ts` | edit | `xtermThemeFromPalette`; cursor role fix (`--rb-cursor` instead of `palette.foreground`) |
| `web/packages/app/src/components/right-pane.tsx` | edit | mount the Terminal surface as a pane-host tab (per ticket 07's model) instead of the current fixed `pane.active === "terminal"` branch, if ticket 07 changes that shape |
| `web/packages/app/src/components/right-tab-strip.tsx` | edit | per-terminal-tab pane chip (§2.3) — one chip per terminal instance, `CHIP_W`/`CHIP_SLOT` geometry, icon, close-on-hover |
| `web/packages/app/src/routes/chat-page.tsx` | edit | drawer toggle binding (Mod-J opens/closes the **drawer**, not the pane's Terminal tab — see §5) |
| `web/packages/app/src/state/shortcuts.ts` | edit | register `ToggleTerminal` (default `mod-j`) in the shared shortcuts catalog instead of a hardcoded handler in `chat-page.tsx` |
| `web/packages/app/src/styles/app.css` | edit | `.term-dock-handle` (highlight line, hover/active states), `.term-tab` icon slot, `.term-tab-add`/`.term-tab-close` → icon-based, `.xterm-viewport` scrollbar override (new `.term-scrollbar`), `--rb-term-*`/`--rb-cursor` usage |

## 1. Context a fresh session needs

- The terminal has **two hosts on desktop that never share state**: a
  bottom drawer under the chat column (`Shell::terminal`, Mod-J), and any
  number of right-pane surface tabs (`Shell::right_terminal`), each with
  its own tab set. On web today, `TerminalDock` is mounted **only** inside
  `components/right-pane.tsx` with `docked` always `true`; there is no
  drawer at all, and Mod-J (bound in `chat-page.tsx`) calls
  `rightPaneStore.show(chatId, "terminal")` — it opens the *pane's*
  Terminal tab, not a drawer. The `docked={false}` code path already
  exists in `terminal-dock.tsx` (height, `.term-dock-handle`) but is dead
  code today. This ticket makes the drawer real: mount a second
  `TerminalDock` instance (`docked={false}`) in the chat column itself
  (sibling of the transcript/composer, not inside the right pane), bind
  Mod-J to toggle *that* drawer via `TerminalStore.toggle`, and keep the
  pane's Terminal surface as its own, separate `TerminalStore`-backed
  host. Confirm with the ticket-07 author whether the store should key
  drawer vs. pane tabs under different namespaces internally (e.g.
  `chatId` vs `chatId:pane`) — do not silently merge the two into one tab
  set, since the desktop treats them as genuinely independent PTYs.
- `TerminalStore` (`terminal/store.tsx`) is a per-provider class keyed by
  `chatId`, holding `xterm.js` `Terminal` instances, a `FitAddon`, and a
  `TerminalSessionController` per tab. It already implements: tab
  add/select/close/reorder, height clamp + persistence via `setHeight`,
  attach/fit/focus, and re-theming on variant change. Read it before
  touching anything — most of the PTY lifecycle, backoff, and tab math
  (`terminal/tabs.ts`) is already ported **verbatim** and passes;
  §4/§5 lists exactly what still needs fixing.
- `terminal/tabs.ts` is pure, DOM-free logic (`clampTerminalHeight`,
  `backoffMs`, `reorderTabs`, `dropIndex`, `slideOffset`,
  `activeAfterReorder`, `activeAfterClose`, `exitMessage`, `shellTitle`,
  `encodeBase64`/`decodeBase64`) — already verified against desktop
  constants and tests. Do not restructure this file; only add new pure
  helpers here if a gap in §4 needs one.
- The right pane's terminal chip (§2.3) is infrastructure shared with
  whatever ticket 07 builds for its generic multi-instance tab strip
  (`right-tab-strip.tsx`); this ticket only needs the Terminal-specific
  chip content (icon, title, close), not the strip's generic
  add/reorder/close mechanics if ticket 07 already owns those.
- `terminal/theme.ts::currentTerminalTheme` reads `--rb-term-*` custom
  properties off `<html>` (written by `@roboco/theme`'s
  `variantCssVars`). A `--rb-cursor` role already exists in that same
  theme package (`cursor: "cursor"` in `variantCssVars`'s role map) — use
  it for the cursor fix in §2.4/§5 rather than inventing a new custom
  property.
- Tokens/motion rules from `spec.md` apply: `var(--rb-*)` colors,
  `var(--rb-motion-resize)` + `var(--rb-ease-ease-out)` for the drawer
  height tween (200ms), `var(--rb-motion-tab-slide)` (150ms) for tab
  sibling-slide during drag if such tokens exist by the time this ticket
  lands — otherwise use the literal ms/curve values below and flag the
  missing token in Comments for ticket 02.
- Vocabulary: chat, engine, PTY (not "session" — Session is reserved for
  the pairing credential per `spec.md`).

## 2. Spec

### 2.1 Drawer resize handle (`render_terminal_container`)

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| Hitbox height | `TERMINAL_RESIZE_HITBOX_HEIGHT` = 10.0px | shell.rs:175, 8405 |
| Highlight line height | 1.0px, absolute, full width, painted at hitbox top | shell.rs:6300–6308 |
| Container border-top | 1px, `theme.border` | shell.rs:6363–6364 |
| Container height | `terminal_height`, animated by a 200ms tween on toggle, tracks pointer 1:1 while dragging | shell.rs:6291,6365,3015–3021,3081 |
| Panel min/max height | 160px … `max(viewport_h * 0.55, 160)` | `TERMINAL_MIN_HEIGHT`/`TERMINAL_MAX_VH`, settings.rs:45–46 |
| Default height | 280px (`TERMINAL_DEFAULT_HEIGHT`) | settings.rs:48 |
| Persisted absolute healing cap | 2000px (only clamps a hand-edited settings file, not live drag) | settings.rs:47,1161–1166 |

**States**

| State | Condition | What changes |
| --- | --- | --- |
| Rest | not hovered/dragging | highlight opacity 0 |
| Hover | pointer over the 10px hitbox | highlight fades to `theme.border_strong` |
| Active drag | dragging in progress | highlight solid `theme.border_strong` |
| Constrained (pinned to min/max) | dragging but pinned at a limit | highlight forced back to opacity 0 |

**Interactions**
- Pointer-down on the handle: anchor = (pointerY, currentHeight); begin
  drag.
- Drag: `requested = anchorHeight + (anchorY - pointerY)`; `height =
  clampTerminalHeight(requested, viewportH)`; report "active" only while
  `requested` is strictly inside `(MIN, max)` — at the clamp boundary
  report "constrained" instead. Debounce the height *persistence* write
  (not the live visual height) at 400ms.
- **Double-click the handle** resets height to 280px and persists
  immediately.
- Pointer-up (anywhere) ends the drag, clears hover.

**Motion**

| What animates | Trigger | Spec | From → to | Reduced motion |
| --- | --- | --- | --- | --- |
| Drawer height | toggle open/close | 200ms, ease-out cubic-bezier(0,0,0.58,1) | 0 ↔ `terminal_height` | snaps to target |
| Handle highlight | hover/active | opacity fade | 0 → `border_strong` alpha | n/a |
| Live drag | pointer move | none (1:1 tracking) | — | — |

### 2.2 `TerminalPanel` tab bar (`render_tab_bar`) — the drawer's own bar

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| Bar height | `TAB_BAR_HEIGHT` = 40.0px | panel.rs:43 |
| Bar padding | `pl(8.0) pr(6.0)` | panel.rs:1437–1438 |
| Bar gap | 4.0px | panel.rs:1436 |
| Bar border-bottom | 1px, hairline(0.07) | panel.rs:1440 |
| Tab width | `TAB_WIDTH` = 118.0px (fixed) | panel.rs:42 |
| Tab height | 28.0px | panel.rs:1506 |
| Tab radius | 8.0px | panel.rs:1514 |
| Tab padding | `pl(8.0) pr(4.0)` | panel.rs:1512–1513 |
| Tab inner gap | 6.0px | panel.rs:1511 |
| Tab font | 12.0px | panel.rs:1522 |
| Close button size | 20×20px, radius 6.0px | panel.rs:1485,1490 |
| Close glyph | `icons::CLOSE`, 12px, `text_muted.opacity(0.8)` | panel.rs:1499–1501 |
| Terminal glyph (tab) | `icons::TERMINAL`, 16px | panel.rs:1552–1554 |
| New-tab / collapse buttons | 28×28px, radius 8.0px | panel.rs:1589,1620 |
| New-tab glyph | `icons::PLUS`, 16px, `text_muted.opacity(0.6)` | panel.rs:1609–1612 |
| Collapse glyph | `icons::ALT_ARROW_DOWN`, 13px, `text_muted.opacity(0.55)` | panel.rs:1636–1639 |

**States**

| State | Condition | What changes |
| --- | --- | --- |
| Active tab | selected | text `theme.text`; bg `ink(0.08)`; glyph alpha 0.8; close button visible |
| Inactive tab | else | text `text_muted.opacity(0.6)`; bg transparent (hover → `element_hover`); glyph alpha 0.6; close button `invisible()` (occupies layout, hidden) |
| Exited tab | PTY exited | whole chip `opacity(0.55)` |
| Dragging (source tab) | being dragged | replaced by an invisible 118×28 spacer; the actual chip follows the cursor as a ghost — `surface_raised` bg, `border_strong` 1px border, 12px text, `opacity(0.85)` |
| Sibling during drag | not the dragged tab | slides ±`TAB_WIDTH` toward the vacated slot, animated |

**Interactions**
- Click a tab: select + focus.
- Middle-click a tab: closes it.
- Pointer-drag a tab: reorders; drop index = `dropIndex(relX, TAB_WIDTH,
  count)`; commit reorders the array and re-picks the active index via
  `activeAfterReorder`.
- Click "+": opens a new tab, focuses it.
- Click close (×): removes the tab, re-picks active via
  `activeAfterClose`; if it was the **last** tab and this is the
  **drawer** (not the embedded pane), also collapse the drawer (an empty
  dock is dead space) — embedded pane instances never auto-collapse; the
  pane just falls back to whatever ticket 07's empty-surface state is.
- Click the collapse chevron: toggles the drawer closed (drawer only —
  the embedded pane's own bar is hidden entirely, since pane tabs are
  promoted to the outer pane chip strip, §2.3).

**Motion**

| What animates | Trigger | Spec | From → to | Reduced motion |
| --- | --- | --- | --- | --- |
| Sibling tab slide | drag-over changes | `TAB_SLIDE` = 150ms, ease-out | `slideOffset(ix,from,prevOver)*TAB_WIDTH` → `slideOffset(ix,from,over)*TAB_WIDTH` | n/a (short tween) |
| Tab bg hover | hover | opacity/color blend | transparent → `element_hover` | — |

**Text**: empty state (no chat selected) — `"Select a chat to open a
terminal"`, 12px, `text_faint`.

### 2.3 Right-pane terminal chip (shared infra, terminal-relevant)

The right pane hosts each terminal **tab** as its own chip in the pane's
outer strip — not inside a nested tab bar. The embedded `TerminalPanel`'s
own bar is hidden entirely for pane-hosted terminals.

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| Chip width | `CHIP_W` = 112.0px | shell.rs:6690 |
| Chip slot (width + gap) | `CHIP_SLOT` = 116.0px | shell.rs:6691 |
| Chip height | 24.0px | shell.rs:6823 |
| Chip radius | 6.0px | shell.rs:6828 |
| Chip padding | `pl(4.0) pr(8.0)` | shell.rs:6826–6827 |
| Chip inner gap | 3.0px | shell.rs:6832 |
| Icon slot | 18×18px, `icons::TERMINAL` at 12px | shell.rs:6890–6894,6939–6940 |
| Title font | `ui_rems(11.5)` | shell.rs:6969 |
| Tooltip delay | 350ms (Terminal chips have no `detail` string, so effectively unused) | shell.rs:6843 |

**States**: active chip bg `wash(0.10)`; inactive hover `wash(0.06)`;
close ✕ swaps in for the icon on chip hover (opacity cross-fade, same
slot).

**Motion**: sibling slide during drag — `TAB_SLIDE` (150ms ease-out),
identical mechanics to §2.2.

**Note**: this geometry (`CHIP_W`/`CHIP_SLOT`/24px height/6px radius)
already matches on web per research §5 ("used for the Terminal tab when
it does render") — the work here is making the *content* (icon, title,
close) correct once ticket 07 actually renders per-instance chips, not
the geometry.

### 2.4 The emulator view (`TerminalElement` → `xterm.js` mapping)

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| Font family | `theme.font_mono` = "Geist Mono" | theme.rs:1088,1184; view.rs:433 |
| Font size | `TERM_FONT_SIZE` = 13.0px | view.rs:25 |
| Line height | `TERM_LINE_HEIGHT` = 18.0px | view.rs:26 |
| Grid padding | `TERM_PADDING` = 12.0px, all sides | view.rs:28,449–450,456–459 |
| Ligatures | OFF (`liga`/`calt`/`dlig` forced to 0) | view.rs:424–438 |
| Client-side scrollback | `SCROLLBACK_LINES` = 10,000 lines | emulator.rs:44,180–184 |
| Cursor (focused) | translucent filled quad, color `theme.cursor` | view.rs:548–550 |
| Cursor (unfocused) | outline quad, same color | view.rs:551–552 |
| Selection wash | `theme.terminal.selection`, merged per-row quads under glyphs | view.rs:490–508 |

**Palette / color roles**

| Role | Desktop source | Value (roboco-dark / roboco-light) |
| --- | --- | --- |
| `theme.terminal.background` | `TerminalPalette.background` | `#090909` / `#fafafa` |
| `theme.terminal.foreground` | `text.ensure_contrast(background, 4.5)` | derived from `text`, contrast-adjusted |
| `theme.terminal.selection` | `border_tone.with_alpha(dark?0.22:0.16)` | white@0.22 (dark) / black@0.16 (light) |
| `theme.terminal.ansi[0..16]` | fixed hex arrays | dark: `#242424 #f87171 #4ade80 #facc15 #60a5fa #c084fc #22d3ee #d4d4d8 #52525b #fca5a5 #86efac #fde047 #93c5fd #d8b4fe #67e8f9 #fafafa`; light: `#1f1f1f #dc2626 #16a34a #b45309 #2563eb #9333ea #0e7490 #3f3f46 #71717a #b91c1c #15803d #92400e #1d4ed8 #7e22ce #155e75 #18181b` |
| `theme.cursor` (**distinct role from `terminal.foreground`**) | `text.with_alpha(dark?0.40:0.55)` | translucent text-tinted fill, not opaque |
| Panel background (glass) | `theme.terminal.background.opacity(0.4)` when glass, else opaque | — |
| Extended 256-color cube (16–231) | fixed `CUBE_LEVELS [0,95,135,175,215,255]`, appearance-independent | — |
| Grayscale ramp (232–255) | dark: 232→8, 255→238; light **mirrors** it (232→238, 255→8) | — |

**Cell attributes**: bold, italic, underline (1px solid, glyph-colored),
dim (`alpha *= 0.6`), inverse (swap fg/bg), hidden (fg painted as bg),
wide chars — all of these are xterm.js's own rendering contract; no
web-side work needed beyond confirming xterm.js honors them (it does by
default).

**Motion**: none — the grid repaints every frame; no glyph-level
transition.

### 2.5 Scrollbar overlay (new — `render_scrollbar`)

**Layout**

| Property | Value | Source |
| --- | --- | --- |
| Hit width | `SCROLLBAR_HIT_WIDTH` = 10.0px, full height, right-anchored | panel.rs:46,1287 |
| Track inset | `SCROLLBAR_TRACK_INSET` = 4.0px (top/bottom) | panel.rs:45,251 |
| Thumb width (rest) | `SCROLLBAR_THUMB_WIDTH` = 3.0px | panel.rs:47 |
| Thumb width (hover) | `SCROLLBAR_HOVER_THUMB_WIDTH` = 4.5px | panel.rs:48 |
| Thumb min height | `SCROLLBAR_MIN_THUMB` = 24.0px | panel.rs:49 |
| Thumb color | `theme.text_faint.opacity(0.52)` | panel.rs:1310 |
| Thumb inset from right | 2.0px | panel.rs:1303 |

**States**: only rendered while the terminal body is hovered AND there is
scrollback; thumb widens on its own hover.

**Interactions**: click-drag the thumb maps pointer Y linearly to a
scrollback offset; clicking the track jumps the thumb under the pointer.

Implement via `.xterm-viewport::-webkit-scrollbar` styling (width,
thumb color/radius, hover width) plus Firefox's `scrollbar-width`/
`scrollbar-color`, as the closest CSS-only approximation of the
hover-only overlay — a fully custom overlay (hiding the native scrollbar
entirely and painting a synced thumb) is acceptable too if the CSS-only
approximation can't match the hover-reveal behavior; either way replace
today's always-visible OS-default scrollbar.

### 2.6 Keyboard, selection, clipboard

**Which keys go to the pty vs the app**
- Any keystroke with the platform modifier (Cmd on macOS / Super
  elsewhere) is refused and falls through to the app keymap — this is how
  Mod-J and other app chords reach the shell instead of the pty.
- Alt recurses without alt and ESC-prefixes the result.
- Control maps through `control_bytes`: `a..z` → `\x01..\x1a`, `@` → NUL,
  `[`/`\`/`]`/`^`/`_`/`/`/`?` → their caret-notation bytes; `space`/
  `backspace`/`enter` get literal NUL/BS/CR; anything else (e.g. Ctrl+1)
  is refused.
- Named keys (arrows, home/end, ins/del, pageup/down, f1–f12) map to fixed
  CSI/SS3 sequences; arrows/home/end switch CSI↔SS3 per DECCKM
  (`app_cursor_mode`).
- Printable keys prefer `key_char` (IME/shift-aware) with a fallback to
  the single-char key name.

**Copy/paste**
- Paste: Cmd+V (mac) / Ctrl+Shift+V (elsewhere) → reads the clipboard,
  wraps in bracketed-paste markers iff bracketed-paste mode is on, strips
  any injected end-marker from the pasted text first.
- Copy: Cmd+C (mac) / Ctrl+Shift+C (elsewhere) → copies the selection;
  the keystroke is swallowed **only if something was actually copied** —
  plain Ctrl+C (no Shift) is never intercepted and always reaches the
  shell as `0x03` (SIGINT), even with an active selection.
- Any keypress while scrolled back snaps the view to the live bottom.

**Selection** (mouse): click-count picks granularity (1 = character, 2 =
word, 3 = line). A plain press doesn't select until the pointer travels
2.0px past the press point. Shift+click extends. Dragging past the
top/bottom edge auto-scrolls (speed ramps with penetration depth, tick =
24ms).

**Search/clear**: neither exists on desktop — do not build an in-terminal
search overlay.

**Bell**: captured but never surfaced in the UI on desktop (dead
capability) — do not add a visible/audible bell effect.

**Open question to resolve during implementation** (§5/§7 of research):
verify whether xterm.js's own default keydown handling already matches
the Ctrl/Cmd+C/V gating above (copy only intercepts with a selection;
plain Ctrl+C always reaches the pty). If it diverges, add an explicit
`attachCustomKeyEventHandler`. Record the finding in Comments either way.

### 2.7 PTY lifecycle over RPC

Already implemented and matching per research §5's closing summary — no
changes expected here, listed for completeness:
1. `OpenTerminal {chatId, cols, rows[, targetDeviceId]}` → `{id, cwd,
   shell}`. Failure feeds a red `"failed to open terminal: {err}"` line
   and marks the tab exited (-1).
2. If closed before open resolves, the just-opened PTY is released via
   `CloseTerminal` immediately.
3. `SubscribeTerminal {terminalId, afterSeq[, targetDeviceId]}` streams
   `Data{seq,data}` (base64) or `Exit{seq,exitCode,signal}`.
4. Dropped-non-exit stream: reconnect with `backoffMs(attempt) =
   min(500 << min(attempt,4), 8000)`, resuming from `afterSeq`.
5. `Data` frames feed the emulator; emulator-originated response bytes go
   straight to `WriteTerminal` uncoalesced.
6. Keyboard bytes coalesce for 12ms before `WriteTerminal` (base64); a
   flush with no terminal id yet (open in flight) keeps the buffer and
   retries in another 12ms.
7. Grid resizes apply to the emulator immediately; `ResizeTerminal`
   debounces 80ms, re-reading the current size at flush time.
8. `Exit{exitCode}` appends `exitMessage(code)`, marks exited, stops
   reconnecting.
9. Closing a tab always fires `CloseTerminal {terminalId}` if an id was
   ever assigned.

### 2.8 "Opening terminals focuses the terminal once"

Rule: every time a terminal panel transitions to visible — the drawer
opening, or an embedded pane tab being added/selected — focus is claimed
**exactly once**. Ordinary redraws (a later render with no new open/select
event — including a live height-drag resize) must **not** re-steal focus.
Toggling the drawer **closed** hands focus back to the composer.

## 3. Pure logic to port

All of the following are already ported byte-for-byte in
`web/packages/app/src/terminal/tabs.ts` — verify against these desktop
test names, do not re-port:
- `clampTerminalHeight` — `height_clamps_between_160_and_55vh`
- `backoffMs` — `backoff_doubles_and_caps`
- `reorderTabs` — `reorder_moves_forward_and_backward`
- `dropIndex` — `drop_index_quantizes_and_clamps`
- `slideOffset` — `slide_offsets_shift_toward_the_gap`
- `activeAfterReorder` — `active_index_tracks_reorders`
- `activeAfterClose` — `active_index_tracks_closes`
- `exitMessage` — `exit_message_format`,
  `exit_message_feeds_cleanly_through_the_emulator`
- `shellTitle` — `shell_titles`
- `decodeBase64`/`encodeBase64` — `base64_round_trip_and_tolerance`

Delegated to xterm.js, not hand-ported (its contract to uphold, flagged
only in case it's ever doubted): `keystroke_bytes`/`control_bytes`/
`paste_bytes`, `InputCoalescer`, `cell_at`, `extended_indexed_rgb`,
alt-screen swap, DSR cursor-position query, UTF-8-split-across-feeds
reassembly, selection-follows-scrolled-output. If §2.6's open question
finds a real divergence in copy/paste gating, that specific behavior
becomes something to hand-implement (via
`attachCustomKeyEventHandler`), not "delegate and hope."

## 4. Gaps this ticket closes

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Right-pane surfaces render nothing | WRONG BEHAVIOR (blocking) | Right pane always shows the active surface | `SURFACES_DISABLED = true` in `components/right-pane.tsx:39` — owned by ticket 07, not this ticket, but this ticket cannot be verified visually until it's flipped | Confirm with ticket 07 that the flag is gone before screenshotting this ticket's acceptance states |
| No bottom-drawer terminal | MISSING (architecture) | two independent hosts: drawer (always available via Mod-J) + pane tabs | `TerminalDock` mounted only inside the pane, `docked` always `true`; the `docked={false}` path is dead code | Mount a second `TerminalDock` in the chat column, `docked={false}`; bind Mod-J to its store toggle |
| Multiple terminal tabs not promoted to pane chips | WRONG BEHAVIOR (architecture) | embedded panel hides its own bar; each instance is a pane-strip chip | web has one `"terminal"` pane slot; multiple PTYs only reachable via the dock's own always-visible internal bar | Promote each pane-hosted terminal tab into the outer `right-tab-strip`, per §2.3, once ticket 07's generic strip supports it |
| Terminal glyph missing from tab chip | MISSING | `icons::TERMINAL` 16px in every tab, alpha 0.6/0.8 | `TabChip` has no icon | Add the icon before `.term-tab-title` |
| "+" / collapse glyphs are literal text | WRONG VALUE | `icons::PLUS` (16px), `icons::ALT_ARROW_DOWN` (13px) SVGs | literal `"+"`/`"×"` text (`terminal-dock.tsx:204,214`) | Swap for `@roboco/icons` glyphs |
| Tab strip scrolls; desktop's doesn't | INVENTED | plain flex row, overflows with enough tabs | `.term-tabstrip{overflow-x:auto}` (app.css:2379-2391) | Low priority — note the divergence, decide whether to keep it as an improvement or remove for strict parity |
| No double-click-to-reset-height | MISSING | double-click resets to 280px | `startHeightDrag` has no click/dblclick handler | Add `onDoubleClick` on `.term-dock-handle` |
| Drawer open/close has no height animation | MISSING | 200ms ease-out height tween | drawer didn't exist; now that it does, animate its mount/height transition | Add the tween per §2.1 |
| Terminal cursor uses the wrong color role | WRONG VALUE | `theme.cursor` = translucent text-tinted fill (focused/outline unfocused), distinct from `terminal.foreground` | `terminal/theme.ts:16-17` sets xterm's `cursor: palette.foreground, cursorAccent: palette.background` — opaque, inverts the glyph | Read `--rb-cursor` instead of `palette.foreground`; accept xterm's block-cursor model as the closest achievable analog if a true translucent-overlay cursor isn't feasible with xterm's renderer, but use the correct color role either way |
| No custom scrollbar overlay | WRONG VALUE / MISSING | hover-only overlay, specific thumb geometry | OS/browser default scrollbar, always visible | Style per §2.5 |
| `focusActive` fires on every resize, not just open/select | WRONG BEHAVIOR | focus claimed exactly once per open/select | `DockBody`'s effect deps include `chat.height` — every pixel of a resize drag re-focuses | Drop `chat.height` from the focus effect's dependency array; keep it for the fit effect only |
| No explicit copy/paste keydown policy | OPEN QUESTION | precise modifier gating (§2.6) | relies entirely on xterm.js defaults | Verify against the pinned `@xterm/xterm` version; add a handler if it diverges |
| xterm scrollback not set to 10,000 lines | WRONG VALUE | `SCROLLBACK_LINES` = 10,000 | no `scrollback` option passed — xterm.js default of 1,000 | Pass `scrollback: 10_000` in the `XTerm` constructor |
| No customizable terminal-toggle shortcut | MISSING | `ShortcutId::ToggleTerminal`, remappable, default `mod-j` | `chat-page.tsx:50` hardcodes `metaKey\|\|ctrlKey` + `"j"` | Register in `state/shortcuts.ts` |

Everything **not** listed above already matches and must not be
re-implemented: `TAB_WIDTH` (118), `TAB_BAR_HEIGHT` (40), tab/close/
new-tab button sizes and radii, `COALESCE_MS` (12), `RESIZE_DEBOUNCE_MS`
(80), `TERMINAL_MIN_HEIGHT`/`TERMINAL_MAX_VH`/`TERMINAL_DEFAULT_HEIGHT`
(160/0.55/280), the reconnect backoff curve, the full `OpenTerminal`/
`SubscribeTerminal`/`WriteTerminal`/`ResizeTerminal`/`CloseTerminal` RPC
contract including `afterSeq` resume, the exit trailer format,
shell-basename tab titling with OSC-title override, drag-reorder math,
base64 tolerance, the terminal font (Geist Mono, 13px/18px line height),
grid padding (12px), the theme-derived ANSI16/background/foreground/
selection palette plumbing, and the right-pane chip geometry (`CHIP_W`
=112/`CHIP_SLOT`=116, 24px height, 6px radius).

## 5. Do not

- Do not build the native embedded browser or its dev-server preview list
  (`crates/ui/src/browser/*`, `components/preview-panel.tsx`,
  `lib/preview.ts`, `state/preview.ts`) — desktop-only, out of scope, a
  different surface entirely.
- Do not merge the drawer's tabs and the pane's tabs into one shared tab
  set — the desktop treats them as genuinely independent PTYs/hosts, even
  though both use the same `TerminalPanel` component shape.
- Do not build an in-terminal search overlay or a visible/audible bell —
  confirmed absent/dead on desktop.
- Do not hand-port `keystroke_bytes`/`control_bytes`/`paste_bytes`/
  `cell_at`/`extended_indexed_rgb`/the emulator's alt-screen/DSR/UTF-8
  reassembly logic — xterm.js already provides this; only add an explicit
  handler if §2.6's verification step finds a real divergence.
- Do not implement the `click_activation_drag_enabled()` platform gate on
  tab dragging — a GPUI/platform-specific quirk with no web equivalent
  needed.
- Do not build the generic right-pane surface picker / `+` menu / dynamic
  tab architecture here — that is ticket 07's. This ticket only supplies
  the Terminal-specific chip content and the drawer host once ticket 07's
  shape exists.

## 6. Acceptance

- [ ] A second `TerminalDock` (`docked={false}`) renders as a bottom
      drawer in the chat column, independent of the pane's Terminal
      surface; Mod-J toggles it via the shared shortcuts catalog.
- [ ] Drawer resize: 10px hitbox, hover/active/constrained highlight
      states, 1:1 drag tracking, double-click resets to 280px, 200ms
      ease-out open/close tween.
- [ ] Tab bar: `icons::TERMINAL` glyph in every tab, real plus/chevron
      icons (not literal text), drag-reorder with sibling slide, middle-
      click close, exited-tab dimming.
- [ ] Right-pane terminal chip: icon, active/hover wash, close-on-hover
      cross-fade, per §2.3 geometry (already correct — verify, don't
      regress).
- [ ] Emulator: Geist Mono 13px/18px, 12px grid padding, ligatures off,
      10,000-line scrollback, translucent `--rb-cursor`-colored cursor
      (focused: filled; unfocused: outline).
- [ ] Custom scrollbar overlay replaces the OS default on `.xterm-viewport`.
- [ ] `focusActive` fires once per open/select, not on every resize pixel
      (verify by dragging the resize handle while a decoy control is
      focused elsewhere — focus must not jump back to the terminal).
- [ ] Copy/paste modifier gating verified against the pinned xterm.js
      version; a handler added if it diverges, with the finding recorded
      in Comments.
- [ ] Unit tests: existing `terminal/tabs.ts` coverage stays green (no
      re-port needed); any new pure helper added for this ticket gets a
      test named after its desktop counterpart.
- [ ] Screenshot pair, desktop vs web, states: drawer open with two tabs
      (one active, one exited), pane Terminal tab with the chip strip,
      drawer mid-resize-drag with the highlight visible, a focused
      terminal with a translucent cursor.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

### Implementation (2026-09-19, branch `wp2/26-terminal`)

**What landed:**

- **Two independent hosts** (`terminal/store.tsx`): `TerminalStore` gained a
  `drawer | embedded` mode and is instantiated twice as module singletons —
  `drawerTerminalStore` (`Shell::terminal`) and `paneTerminalStore`
  (`Shell::right_terminal`) — never sharing PTYs or tab sets.
  `TerminalProvider` binds the engine session to both and re-themes both on
  variant change; the React context (`useTerminalStore`) was removed (the
  store was already read through `useSyncExternalStore`; the context only
  handed the object over). Tab keys are now strings — the pane's surface ids
  ARE terminal tab keys.
- **The drawer is real** (`terminal/terminal-dock.tsx` `DrawerDock` +
  `routes/chat-page.tsx`): mounted as the last child of the chat column's
  bottom stack (below the composer, `render_main`'s
  `render_terminal_container` slot, shell.rs:6124). Animated OUTER container
  + fixed-height INNER child (the clip-don't-squeeze trick) with the 200ms
  `evalWidthTween` rAF loop on toggle and 1:1 pointer tracking while
  dragging; reduced motion snaps. 10px hitbox handle with the 1px
  highlight line and the exact state table (rest 0 / hover fade / active
  solid / constrained forced back to 0); double-click resets to 280. The
  height persists through `uiSettings.terminalHeight` — initial value from
  the store, debounced 400ms write on drag (`updateDebounced` =
  `schedule_save`).
- **Per-instance pane terminal surfaces**: `RightPaneStore.addTerminalSurface`
  mints a FRESH embedded terminal tab per click (desktop
  `add_terminal_surface`, shell.rs:2634-2650); the pane chip ✕ / middle-click
  closes THAT tab (`close_tab_by_key`), and `describe` reads the tab's live
  OSC/shell-basename title (null = gone = row skipped, the desktop's
  `tab_summaries` peer). The embedded host is handed over through a small
  `PaneTerminalSource` seam injected by `surface-registry.tsx` at boot (so
  `state/right-pane.ts` stays free of the xterm import in the node test
  environment), with the pane store's version bumps fanning into
  `rightPaneStore.notify()`. The dock's own tab bar is hidden entirely when
  `docked` (`render_right_pane`'s embedded shape), and the surface's id is
  selected through `select_tab_by_key` in a layout effect.
- **Tab bar parity** (`terminal-dock.tsx` + `app.css`): `icons::TERMINAL`
  16px glyph in every tab chip (alpha 0.6/0.8), real `plus` (16px) and
  `altArrowDown` (13px) glyphs replacing the literal `+`/`×` text, `close`
  12px in the tab close button; new-tab/collapse buttons 28×28 r8 (were
  24×24 r6); bar border-bottom is `hairline(0.07)`; active tab bg
  `rgb(var(--rb-ink) / 0.08)`; exited tab = whole-chip `opacity: 0.55`
  (replacing the invented 6px dot); the dragged chip follows the pointer
  styled as the ghost (raised bg, border-strong, 0.85). The exit tab title
  keeps the live OSC title, matching the desktop's `display_title`.
- **Emulator** (`store.tsx` + `theme.ts`): `scrollback: 10_000`;
  `cursorInactiveStyle: "outline"` (the desktop's unfocused outline cursor);
  ligatures forced off in CSS; the cursor now reads `--rb-cursor`
  (`theme.cursor`, distinct from `terminal.foreground`) with
  `cursorAccent: foreground` so the block reads as an overlay, not an
  inversion.
- **Scrollbar overlay** (§2.5): @xterm/xterm 6.0.0 ships a VS Code-style DOM
  scrollbar (`xterm-scrollable-element`) that is already a hover-revealed
  fading overlay — the "always-visible OS default" of the research is gone
  by construction. Themed through the xterm `ITheme`
  (`scrollbarSlider*` = `--rb-text-faint` at 0.52), geometry via CSS
  `!important` overrides of the widget's inline sizing: 10px rail at a 2px
  right inset, 3px thumb widening to 4.5px on its own hover, 1.5px radius.
- **Focus once** (§2.8): the focus effect's deps are
  `[store, chatId, chat.active]` — a height-drag no longer re-claims focus;
  the close handoff lands on the drawer terminal's UNMOUNT (DockBody
  cleanup): xterm's own keyup handler re-grabs focus after any chord that
  closed the drawer, which ate the at-flip `composer.focus()` — the unmount
  handoff fires after it and restores the composer (`shell.rs:3049`).
- **Copy/paste policy** (§2.6): explicit `attachCustomKeyEventHandler` per
  tab — paste chord (Cmd+V mac / Ctrl+Shift+V elsewhere) reads the
  clipboard, sanitizes + wraps through the ported `pasteBytes`
  (`paste_bytes`, view.rs:309-319, unit-tested as
  `paste wraps when bracketed`) and queues it through the 12ms coalescer;
  copy chord copies only when a selection exists and swallows only then;
  `metaKey` chords (Cmd on macOS, Super elsewhere — GPUI's
  `Modifiers::platform`) are refused so app shortcuts reach the shell
  keymap; already-prevented events fall through.
- **Mod+J** binds the DRAWER through ticket 12's `toggle-terminal` catalog
  entry (`TerminalShortcutBridge` now drives `drawerTerminalStore`;
  `state/shortcuts.ts` itself needed no edit — ticket 12 had already
  registered `ToggleTerminal`/`mod-j`, verified).
- `terminal/session.ts`: unchanged — §2.7 confirmed as already matching
  (open/subscribe/afterSeq resume/backoff/coalescing/resize debounce/exit
  trailer all read against the desktop; the controller tests cover them).
- Tests: `right-pane.test.ts` — the fresh store takes a fake
  `PaneTerminalSource` (keeps xterm out of the node environment), plus new
  `terminal_surfaces_are_per_instance` and no-host no-op cases;
  `terminal.test.ts` — theme signature updated (cursor + scrollbar
  assertions) and the `pasteBytes` test. 1018 tests green.

**Deviations / findings:**

- **Double-click reset persistence**: the ticket said "persists immediately",
  but the cited source (shell.rs:6329) routes it through `schedule_save` —
  the same 400ms debounced write as a drag. Implemented per the source.
- **§2.6 open question, resolved against the pinned @xterm/xterm 6.0.0**:
  the core's keydown path (`evaluateKeyboardEvent`) never touches the
  clipboard — plain Ctrl+C maps to `\x03` (verified live: cmd aborted the
  line on Ctrl+C; Ctrl+Shift+C with no selection fell through without
  reaching the shell), and on non-mac NOTHING in xterm itself handles
  Ctrl+Shift+C/V. Divergence was real (silent swallow, no copy/paste), so
  the explicit handler above was added, per the ticket's fallback rule.
  Clipboard read/write may prompt for permission in some browsers; failures
  no-op gracefully.
- **Cursor translucency**: xterm 6's DOM renderer flattens the theme color's
  alpha against the terminal background (computed `rgb(98,98,99)` =
  `#e8e8ea66` over `#090909`) — the role is correct
  (`--rb-cursor`/text-faint for the scrollbar), and the visual result over
  the background is the same; the desktop's under-glyph compositing is not
  achievable with this renderer (the ticket's sanctioned closest analog).
- **Drag ghost**: the real chip follows the pointer with ghost styling
  rather than an invisible spacer + separate cursor-follower — same visual
  result, one less portal.
- **`set_resize_suspended`** (the pane's glide guard) is not ported: the
  pane's inner holds the content width through a glide, so the grid never
  reflows mid-glide; the resize RPC already debounces.
- **Empty state** ("Select a chat to open a terminal") is unreachable on
  web — the drawer mounts only on a chat route and Mod+J no-ops on the
  canvas; skipped as the research marked it low priority.
- **Transcript clearance**: the drawer joins the measured bottom stack, so
  the fade band and clearance track the live (animating) height per frame
  rather than reserving the destination footprint like the desktop. No pump
  observed; flagging for ticket 18's owner if a mid-tween flicker ever shows.
- The tab strip's INVENTED horizontal scroll was already removed by ticket
  04; verified gone (plain overflow, like the desktop).

**Verification:**

- `pnpm -r build` green; `@roboco/app` vitest 62 files / 1018 tests green.
- web_smoke round on Windows with real ConPTY `cmd.exe` PTYs: Mod+J opens
  the drawer (280px, one tab auto-spawned, focus claimed once by the
  terminal); a second tab via `+`, the first exited via `exit` (chip dimmed
  0.55, `[process exited 0]` trailer, title = the live OSC path); resize
  drag 280→250 with handle `data-state="active"` and the focused composer
  KEEPING focus through the whole drag (the focus-once acceptance); the
  height persisted to `localStorage.roboco.ui-settings.v1.terminalHeight`;
  double-click reset to 280. Pane: picker Terminal row + the strip's `+`
  menu minted two per-instance terminal chips (live cmd titles, no internal
  bar, chip click switches PTYs), drawer (2 hosts) and pane (2 hosts)
  coexist as independent hosts; closing the drawer left the pane untouched.
  Scrollbar: hover-revealed (`visible`, opacity 1, proportional 98px thumb
  over 300 lines of scrollback), 10px rail / 3px thumb / 2px inset; the
  unfocused cursor swaps to `xterm-cursor-outline`; phone-width (375px)
  boot renders with no error boundary.
- Screenshots (web halves) in `.scratch/web-parity/shots/26/`:
  `web-00-boot-check.png`, `web-01-drawer-two-tabs.png`,
  `web-02-pane-terminal-chip-strip.png`,
  `web-03-drawer-resize-highlight.png`,
  `web-04-focused-terminal-cursor.png`,
  `web-05-scrollbar-hover-reveal.png` (the auto-hide fade window is shorter
  than a capture round-trip — the revealed state is evidenced by the DOM
  reads above), `web-06-phone-layer-boot.png`.
  - **Desktop halves of all pairs: skipped, documented per the runbook** —
    no `roboco` desktop process is running and none was started (same skip
    as tickets 09/10/24).

