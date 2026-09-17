# 07 — Right pane host and multi-instance tabs

**What to build:** The right pane stops being an empty box. After this ticket it
mounts real surfaces again (`SURFACES_DISABLED` is gone), and its tab model is
the desktop's: tabs are **created on demand** from a surface picker or a `+`
menu, the list starts **empty**, N file tabs and N commit-diff tabs can coexist
alongside one Files tab and one Terminal tab, each tab shows a contextual title
with a dirty dot and a 350 ms path tooltip, ✕ closes **that tab** (not the pane),
tabs drag-reorder with a real ghost chip, the strip fades at whichever edge hides
chips, and the pane's resize seam bounces when it hits a limit. The surface
bodies themselves stay stubbed — the host mounts them through a registry that
tickets 22 / 24 / 26 / 27 plug into.

**Blocked by:** 02 (Foundation tokens), 03 (Client settings store).

**Status:** ready-for-agent

**Research:** `../../web-client/research/01-shell-chrome.md` §3.20, §3.21, §3.22,
§3.23, §3.27, §3.29, §4.3, §4.4, §4.11, §4.12, §5.1 rows B1–B3, §5.4 rows
P5–P19, §5.5 rows R1–R19.

**Desktop reference (for lookups only):**
`crates/ui/src/shell.rs::render_right_pane` (6449), `::right_pane_container`
(3826), `::render_right_tab_strip` (6687), `::render_surface_picker` (6594),
`::resize_handle` (5708), `::on_right_pane_drag` (3123), `::on_sidebar_drag`
(3086), `::toggle_right_pane` (1959), `::toggle_right_pane_expand` (7204),
`::resolved_right_active` (2136), `::right_surface_rows` (2007),
`::push_unique_right_surface` / `::workspace_file_title` (457-482),
`::SurfaceTabGhost` (749), `::SurfaceTabTooltip` (773),
`::eval_resize_edge_bounce` (3769); `crates/ui/src/motion.rs::resize_drag_sample`
(266), `::resize_bounce_offset` (310); `crates/ui/src/surface_chrome.rs`.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/right-pane.tsx` | edit | `RightPane`, `usePaneGlide`, `PaneGlide`; **delete** `SURFACES_DISABLED` |
| `web/packages/app/src/components/right-tab-strip.tsx` | edit | `RightTabStrip`, `TabChip`, `slideOffset`; **new** `SurfaceTabGhost`, `SurfaceTabTooltip`, `AddSurfaceButton`, `AddSurfaceMenu` |
| `web/packages/app/src/components/surface-picker.tsx` | **new** | `SurfacePicker`, `SurfacePickerRow` |
| `web/packages/app/src/components/surface-registry.tsx` | **new** | `RightSurfaceRegistry`, `registerRightSurface`, `renderRightSurface` |
| `web/packages/app/src/state/right-pane.ts` | edit | `RightSurface` (rewritten), `RIGHT_SURFACES` (**delete**), `SURFACE_TITLES` / `SURFACE_ICONS` (rewritten as functions), `ChatPaneState`, `RightPaneStore` (`toggle`, `show`, `setActive`, `toggleExpanded`, `close`, `setWidth`, `resetWidth`, `moveTab`; **new** `addSurface`, `closeSurface`, `resolvedActive`), `resolvePaneWidth`, `useRightPane` |
| `web/packages/app/src/state/layout.ts` | edit | `resizeDragSample`, `resizeBounceOffset`, `ResizeEdge` (new exports) |
| `web/packages/app/src/components/pane-seam.tsx` | edit | `PaneSeam` — constrained state, edge latch, bounce |
| `web/packages/app/src/components/app-shell.tsx` | edit | the `hasPane`/`pane.open` mounting conditions, the seam's `!tween_active` guard, `--rb-pane-now` / `--rb-pane-open` |
| `web/packages/app/src/routes/changes-page.tsx` | edit | `ChangesSurface` — becomes a registry entry, body stays as is |
| `web/packages/app/src/routes/files-page.tsx` | edit | `FilesSurface` — becomes a registry entry, body stays as is |
| `web/packages/app/src/terminal/terminal-dock.tsx` | edit | registry entry only; dock behaviour untouched |
| `web/packages/app/src/components/preview-panel.tsx` | **delete** (if ticket 04 row 40 has not already) | (INVENTED standalone surface — see §5) |
| `web/packages/app/src/router.tsx` | edit | **delete** `filesRoute`, `changesRoute` and their exports |
| `web/packages/app/src/styles/app.css` | edit | `.right-tab-strip`, `.right-tab`, `.right-tab-active`, `.right-tab-slot`, `.right-tab-icon`, `.right-tab-close`, `.right-tab-title`, `.right-pane`, `.right-pane-inner`, `.right-pane-expanded`, `.right-pane-body`, `.pane-seam`, `.pane-seam-sidebar`, `.pane-seam-right`, `.pane-seam-line`, `.pane-seam-dragging`; **new** `.right-tab-dirty`, `.right-tab-ghost`, `.right-tab-tooltip`, `.right-tab-fade-left`, `.right-tab-fade-right`, `.right-surface-add`, `.right-plus-menu`, `.surface-picker`, `.surface-picker-row`, `.surface-toolbar`, `.pane-seam-constrained` |
| `web/packages/app/tests/right-pane.test.ts` | **new** | surface model, `resolvedActive`, single-instance Files, stable file titles, drop index, slide offsets |
| `web/packages/app/tests/layout.test.ts` | edit | `resizeDragSample`, `resizeBounceOffset`, `right_panel_content_width` |

---

## 1. Context a fresh session needs

- The right pane is the **third shell column**, a sibling of the sidebar and the
  conversation column in `AppShell`'s flex row — not a child of the chat route.
  Its surface and its left border run the **full window height** behind the
  overlaid titlebar; only `.right-pane-body` pads down by
  `var(--rb-titlebar-height)` (38 px).
- Its **tabs live in the titlebar band**, not in the pane, because the titlebar
  overlay owns that band's hit-testing. `RightTabStrip` is rendered by
  `Titlebar` via the `paneTabs` prop from `app-shell.tsx:251-253`. **Ticket 06
  owns the band and the 28 px toggle/expand buttons; this ticket owns everything
  inside the band.**
- The pane's state is **per chat, in memory, all-defaults-closed**
  (`state/right-pane.ts`). The *width* is the one piece that should persist
  globally (gap B3) — through ticket 03's settings store, not per chat.
- Widths: `resolvePaneWidth` → `rightPaneMaxWidth` / `rightPaneTakeoverWidth` in
  `state/layout.ts`. Those already match the desktop exactly; do not change them.
- The pane column animates via CSS `transition: width var(--rb-motion-resize)
  var(--rb-ease-ease-out)` on `.right-pane`, with `.right-pane-inner`
  absolutely right-anchored at the held content width (`usePaneGlide`). That is
  the desktop's `right_pane_container` clip-don't-squeeze trick; keep it.
- `:root[data-rb-resizing]` suppresses those transitions during a seam drag —
  the web equivalent of the desktop clearing `right_tween`.
- Colors are `--rb-*` only: neutral washes are `rgb(var(--rb-wash) / a)` /
  `rgb(var(--rb-ink) / a)` / `rgb(var(--rb-hairline) / a)`. Motion is
  `var(--rb-motion-<spec>)` + `var(--rb-ease-<curve>)`.
- Vocabulary (`CONTEXT.md`): **chat**, **harness**, **engine**, **space**.
  Desktop identifiers (`RightSurface`, `SessionPanels`) keep their Rust spelling
  in code comments; user-visible strings are copied verbatim.
- **Decision already settled** (`spec.md` §2): right-pane tabs are
  multi-instance. This ticket generalizes the model; later pane tickets depend
  on it.

### Token table used throughout

| Token | Value | Source |
| --- | --- | --- |
| `Theme::SPACE_XS` | 4 | `proto/layout.rs:16` |
| `Theme::SPACE_SM` | 8 | `proto/layout.rs:17` |
| `Theme::SPACE_MD` | 12 | `proto/layout.rs:18` |
| `Theme::PANEL_RADIUS` | 10 | `proto/layout.rs:32` |
| `Theme::CONTROL_RADIUS` | 6 | `proto/layout.rs:34` |
| `popover::CARD_RADIUS` | 12 | `popover.rs:306` |
| `Theme::TITLEBAR_HEIGHT` | 38 | `proto/layout.rs:44` |
| `RIGHT_PANE_MIN` | 360 | `settings.rs:37` |
| `RIGHT_PANE_DEFAULT` | 520 | `settings.rs:38` |
| `CHAT_PANEL_MIN` | 300 | `settings.rs:40` |
| `SIDEBAR_MIN` / `_MAX` / `_DEFAULT` | 224 / 400 / 256 | `settings.rs:30-32` |

### Motion catalog used throughout

| Name | Duration | Curve | Source |
| --- | --- | --- | --- |
| `MENU_IN` | 140 ms | `EASE` = cubic-bezier(0.25, 0.1, 0.25, 1) | `proto/motion.rs:293` |
| `MENU_OUT` | 100 ms | `EASE` | `proto/motion.rs:296` |
| `RESIZE` | 200 ms | `EASE_OUT` = cubic-bezier(0, 0, 0.58, 1) | `proto/motion.rs:302, 217` |
| `TAB_SLIDE` | 150 ms | `EASE_OUT` | `proto/motion.rs:304` |
| `HOVER_FADE` | 150 ms | `EASE_TAILWIND` = cubic-bezier(0.4, 0, 0.2, 1) | `proto/motion.rs:321, 229` |
| `RESIZE_EDGE_NUDGE` | 5 px | — | `proto/motion.rs:429` |
| `RESIZE_EDGE_BOUNCE_MS` | 220 ms | two-phase smoothstep | `proto/motion.rs:430` |
| `RESIZE_EDGE_BOUNCE_OUT_FRACTION` | 0.32 | — | `proto/motion.rs:431` |

Reduced motion: every tween below returns the target instantly under
`@media (prefers-reduced-motion: reduce)`, matching `motion::reduced_motion`.
`eval_resize_edge_bounce` returns **0** under reduced motion (`shell.rs:3769-3788`).

---

## 2. Spec

### 2.0 Delete `SURFACES_DISABLED`

`right-pane.tsx:34-39` declares:

```ts
/**
 * TEMPORARY — bisecting the sidebar-toggle flash. The pane renders as an empty
 * box so nothing inside it can repaint while the sidebar animates. Flip back
 * to `false` (or delete this and its one use below) once the cause is found.
 */
const SURFACES_DISABLED = true;
```

and `:82` short-circuits every surface to `null`. **Delete the constant, its
doc comment and the ternary.** Until it is gone, no pane-surface parity claim on
web is true (gap B1). If the sidebar-toggle flash returns, fix it at its source —
the clip-don't-squeeze contract in §2.2 is what prevents it — do not reinstate
the flag.

Deleting it also fixes gap B2: `right-pane.tsx:58` currently opens a PTY when
`pane.open && active === "terminal"` while `TerminalDock` never mounts. After
this ticket the PTY is minted by the Terminal surface mounting, not by an effect
beside it.

---

### 2.1 The `RightSurface` model

`shell.rs:457-482`, §4.12.

```
RightSurface = Picker | Files | File(u64) | Browser(u64)
             | Diff(u64) | Terminal(u64) | Subagent(u64)

push_unique_right_surface(tabs, s) → false if already present, else push + true
workspace_file_title(path)         = path.rsplit('/').next().unwrap_or(path)
```

TypeScript shape (replacing `type RightSurface = "changes" | "files" |
"terminal" | "preview"` at `state/right-pane.ts:16` and the
`RIGHT_SURFACES` constant at `:18`):

```ts
export type RightSurface =
  | { kind: "picker" }
  | { kind: "files" }
  | { kind: "file"; id: string }
  | { kind: "diff"; id: string }
  | { kind: "terminal"; id: string }
  | { kind: "subagent"; id: string };
```

**`Browser(u64)` is desktop-only** — a native embedded web view; a browser client
cannot host arbitrary cross-origin pages in a pane (§6 of the research). Omit the
variant. Where the desktop offers `"Browser"` in the picker and the `+` menu,
the web omits that row.

Surfaces are compared by **value** (kind + id), so tabs need a stable string key:
`surfaceKey(s) = s.kind + (":" + s.id ?? "")`.

**`right_surface_rows`** (`shell.rs:2007-2072`) walks the **stored**
(drag-reorderable) order and **skips** entries whose backing entity is gone. It
returns `(surface, title, is_dirty, detail)`, where `detail` is the
tooltip/accessible path — a file path (or a browser URL on desktop).

**Titles are contextual** (gap R18), not a fixed map:

| Surface | Title | Detail (tooltip / aria) |
| --- | --- | --- |
| `Files` | the Files surface's own title | — |
| `File(id)` | `workspace_file_title(path)` — the basename | the full workspace path |
| `Diff(id)` | the diff's scope label, or the pinned commit's subject | — |
| `Terminal(id)` | the terminal tab's title | — |
| `Subagent(id)` | the subagent's name | — |
| `Picker` | *(never rendered as a chip)* | — |

**Icon per surface kind** (`shell.rs:6763-6781`)

| `RightSurface` | Icon asset |
| --- | --- |
| `Files` | `folder-with-files` |
| `File(_)` | `document` (overridden by the file-type icon in the leading slot) |
| `Diff(id)` | `git-branch` if that `Changes` `is_history()`, else `list` |
| `Subagent(_)` | `bot` |
| `Terminal(_)` | `terminal` |
| `Browser(_)` | `globe` *(desktop only)* |
| `Picker` | `plus` |

`SURFACE_ICONS` / `SURFACE_TITLES` in `state/right-pane.ts` become **functions of
a surface + its live backing row**, not `Record` literals.

#### `SessionPanels` / `ChatPanels` — per-chat flags (§4.11, `shell.rs:493-532`)

* `ChatPanels { terminal_open: bool, changes_open: bool, right_active: RightSurface }`
  — **everything defaults CLOSED**, the right pane included.
* Keyed by chat id; the new-chat canvas keys **per space** —
  `panel_key()` = `format!("space-canvas:{space}")` when no chat is selected,
  else the chat id (`shell.rs:1901-1913`). One shared `""` key made a canvas
  toggle read as global state (user report). **The web currently uses `""` for
  the canvas** (`app-shell.tsx:87`) — replace it with the per-space key.
* **Not persisted** — a fresh app run starts with everything closed. Heights and
  every other setting stay global in `UiSettings` (and, on web, ticket 03's
  settings store).
* `resolved_right_active` (`shell.rs:2136-2150`): the stored pick if it still
  exists in the live tab list, else the **first remaining tab**, else `Picker`.
  Terminal keys go stale when their tab closes/exits — never render a dead
  surface.

**`right_pane_open`** (`shell.rs:1920-1922`): `!active_chat.is_empty() &&
panels.get(panel_key).changes_open`. **Not** gated on git any more (the pane is a
surface host), but still hidden on the new-chat canvas, where the titlebar
carries no toggle to close it again.

---

### 2.2 `render_right_pane` — the pane itself

`shell.rs:6449-6589`

**Content selection** — evaluated when `right_pane_open || tween_active(right_tween)`:

| `resolved_right_active()` | Content | Source |
| --- | --- | --- |
| `Files` / `File(_)` while **closing** (`!right_pane_open`) | `Empty` — the Files surface stays unmounted throughout the closing animation after its resources are suspended | 6455-6459 |
| `Files` | the chat's `FilesSurface` (`ensure_loaded`), else the picker | 6460-6468 |
| `File(id)` | that file editor (`ensure_loaded`), else the picker | 6469-6476 |
| `Diff(id)` (present) | `flex column` of `surface_chrome::toolbar(theme).child(changes.render_header_controls())` then `flex_1 min-h:0` holding the `Changes` view. The diff *options* (scope dropdown, ref selector, fold-all) live in this second row, **not** in the titlebar | 6477-6494 |
| `Browser(id)` | that browser surface, else the picker *(desktop only)* | 6495-6500 |
| `Terminal(tab)` | the embedded terminal panel with `set_resize_suspended(tween_active)` and `select_tab_by_key(tab)` | 6501-6511 |
| `Subagent(id)` | `size_full; relative; flex column` of `flex_1 min-h:0` holding the read-only transcript, plus its own jump pill | 6512-6547 |
| anything else | `render_surface_picker` | 6548 |

**Panel** (6562-6577)

| Property | Value |
| --- | --- |
| size | `100% × 100%`, `flex column`, `overflow hidden` |
| border-left | `1px solid theme.border` — **omitted in takeover**, where the panel's left edge IS the sidebar seam and already carries the sidebar tone's right hairline |
| background | `theme.is_glass() ? theme.bg.opacity(0.4) : theme.bg` |
| padding-top | `Theme::TITLEBAR_HEIGHT` (38) — the titlebar is a glass overlay over the full-height row; the panel's own chrome starts below it |

> `.right-pane-inner` hard-codes `color-mix(in srgb, var(--rb-bg) 40%,
> transparent)` unconditionally (gap P16). Branch it on the resolved surface
> treatment from ticket 02's tokens: glass → 40 % `--rb-bg`, opaque → `--rb-bg`.

**Container — `right_pane_container`** (3826-3855)

```
takeover_width = active_tween_endpoints(right_takeover_content_tween)
                   .map(|_| eval_tween(right_takeover_content_tween, target))
content_width  = right_panel_content_width(target,
                                           active_tween_endpoints(right_tween),
                                           takeover_width)
                 + edge_offset
```

Outer: `h-full; flex-none; relative; overflow hidden; width =
eval_tween(right_tween, target) + edge_offset`.
Inner: `absolute; top 0; right 0; h-full; width content_width` holding the panel.
**The outer clips; the inner is right-anchored at the larger endpoint's width**,
so descendants keep their geometry for the whole 200 ms transition instead of
reflowing. `usePaneGlide` + `.right-pane-inner { position:absolute; right:0 }`
already reproduce this (gap P15) — keep it, and add `edge_offset`.

`edge_offset = eval_resize_edge_bounce(right_edge_bounce, enabled =
right_pane_open && !right_pane_expanded)` (6579-6582).

**Width math** (444-452, 177-191) — §4.3:

```
right_pane_max_width(viewport, sidebar)      = max(viewport − sidebar − CHAT_PANEL_MIN(300), 0)
right_pane_takeover_width(viewport, sidebar) = max(viewport − sidebar, 0)
conversation_width(viewport, sidebar, right) = max(viewport − sidebar − right, 0)
stable_panel_content_width(target, transition)
      = transition.map(|(from,to)| max(from,to)).unwrap_or(target)
right_panel_content_width(target, transition, takeover)
      = takeover.unwrap_or_else(|| stable_panel_content_width(target, transition))
```

`right_target(cx)` (1929-1945): `0` when closed; in takeover
`right_pane_takeover_width(viewport_width, sidebar_now())`; otherwise
`min(settings.right_pane_width, right_pane_max_width(viewport_width, sidebar_now()))`.
Both ride the **animated** sidebar width so toggling the sidebar stays seamless.

Deliberate consequence (comment at 440-443): on unusually small windows the
ceiling falls **below** `RIGHT_PANE_MIN` — the chat stays usable and the side
surface yields. `right_pane_max_width(800, 256) = 244` (test at 8283-8291).

**`toggle_right_pane`** (1959-1993): captures the visible width (so a toggle
mid-animation reverses from what is painted), clears bounce/edge state, flips the
per-chat flag; **closing always leaves takeover mode**; starts the `right_tween`,
clears `right_takeover_content_tween`, and sets `main_takeover_tween` only if it
*was* expanded. Reopening onto a diff tab revalidates its watch.

> `rightPaneStore.toggle` currently leaves `expanded` alone; only `close()`
> clears it, so closing from takeover and reopening lands **back in takeover**
> (gap P19). Clear `expanded` on close in `toggle()`.

**`toggle_right_pane_expand`** (7204-7221): flips `right_pane_expanded`, sets
`right_tween`, `right_takeover_content_tween` (to the same transition — this is
what lets the *contents* resize with the frame in takeover), and
`main_takeover_tween` from the old to the new conversation width. **Session-local
view state — never persisted, reset on close.**

**The surface registry.** Surface **bodies** are other tickets (Changes 22,
Files 24, Terminal 26, History 27). Build the host so they plug in:

```ts
// components/surface-registry.tsx
export interface RightSurfaceEntry {
  readonly kind: RightSurface["kind"];
  readonly title: (s: RightSurface, ctx: SurfaceContext) => string;
  readonly detail?: (s: RightSurface, ctx: SurfaceContext) => string | null;
  readonly icon: (s: RightSurface, ctx: SurfaceContext) => IconName;
  readonly isDirty?: (s: RightSurface, ctx: SurfaceContext) => boolean;
  readonly isClosable?: (s: RightSurface) => boolean;   // default: true
  readonly toolbar?: (s: RightSurface, ctx: SurfaceContext) => ReactNode;
  readonly render: (s: RightSurface, ctx: SurfaceContext) => ReactNode;
}
export function registerRightSurface(entry: RightSurfaceEntry): void;
```

This ticket registers **stub bodies** for `files`, `diff`, `terminal` and
`subagent` (a centred muted `"…"` placeholder is fine) plus the real `picker`;
it wires the real `ChangesSurface` / `FilesSurface` / `TerminalDock` only as far
as mounting them unchanged where they already exist. The later tickets replace
`render` without touching this file's shape.

**The `Diff` toolbar row** uses `surface_chrome::toolbar` (§3.27):

| Property | Value |
| --- | --- |
| height | `HEADER_HEIGHT` = `Theme::TITLEBAR_HEIGHT` = **38** — **border-box**, the two 1 px borders are inside it |
| width | `100%`, `flex-none` |
| padding-x | `EDGE_INSET` (8) |
| display | `flex; align-items:center; gap CONTROL_GAP (4)` |
| border-top / border-bottom | `1px solid theme.border` |
| background | `theme.is_glass() ? theme.surface.opacity(0.26) : theme.surface` |

Other `surface_chrome` constants: `CONTROL_SIZE` 24, `CONTROL_RADIUS` 6,
`ICON_SIZE` 14. `surface_chrome::input()` (the shared field treatment): `height
24; min-w:0; flex: 1 1 0%; padding-x 8; radius 6; background Theme::ink(0.035);
flex; align-items:center; gap 6; font-size **px(11.5)** (a raw px, not ui_rems)`.
Build `.surface-toolbar` and `.surface-input` here; tickets 22/24/26 fill them.

---

### 2.3 `render_right_tab_strip`

`shell.rs:6687-7198`

**Local constants**

| Constant | Value | Source |
| --- | --- | --- |
| `CHIP_W` | **112** | 6690 |
| `CHIP_SLOT` | **116** = `CHIP_W + 4` (chip + the strip's own gap) | 6691 |
| `FADE_WIDTH` | **36** | 6709 |

**Scroller** `#right-surface-strip` (6719-6759)

| Property | Value |
| --- | --- |
| display | `flex row; align-items:center; gap 4` |
| sizing | `min-w:0`, `overflow-x: scroll`, `track_scroll(right_tab_scroll)` |
| Windows only | `.occlude()` so caption hit-testing cannot claim tab clicks while wheel events still reach the scroller *(desktop-only)* |

**Fade flags** (6710-6713), computed from the **previous** frame's scroll state:

```
scrolled   = −right_tab_scroll.offset().x
max_scroll =  right_tab_scroll.max_offset().x
fade_left  = scrolled > 1.0
fade_right = scrolled < max_scroll − 1.0
```

**Chip** (6819-6985)

| Property | Value |
| --- | --- |
| id | `("right-surface-tab", ix)` |
| group | `"right-surface-tab-{ix}"` (drives the icon↔✕ swap) |
| height / width | `24` / `CHIP_W` (112), `flex-none` |
| padding | `padding-left 4; padding-right 8` |
| radius | `6` |
| display | `flex row; align-items:center; gap 3` |
| cursor | `pointer` |
| role / aria | `Role::Button`, `aria_label` = the detail path (or title), suffixed `", unsaved changes"` when dirty |
| background (active) | `Theme::wash(0.10)` |
| background (hover, inactive) | `Theme::wash(0.06)` |
| hit-testing | `.block_mouse_except_scroll()` — **not** `.occlude()`: a full BlockMouse hitbox ended the hit test and the scroll container behind the tiled tabs never saw wheel events |
| tooltip | present only when `detail` is `Some`; `SurfaceTabTooltip`, **350 ms** show delay |

Chip children:

1. **Leading slot** — `id ("right-surface-close", ix); flex-none; size 18;
   radius 4; relative; hover background Theme::wash(0.12)`. Two stacked
   absolutely-inset layers swapped by group hover (6887-6963):
   * **icon layer** — `group_hover → opacity 0`. Content, in priority order:
     * `subagent_running` → `loaders::mini_glyph_spinner("subagent-tab-{ix}",
       speed 2.0, theme.glyph)`;
     * a browser favicon → `img(favicon)` at `size 12` *(desktop only)*;
     * `RightSurface::File(_)` → `file_icons::icon(...)` at **`size 14`**, plus
       `opacity 0.78` when inactive;
     * otherwise `icon(icon_path)` at **`size 12`**, colour `theme.text_muted`
       when active, `theme.text_muted.opacity(0.7)` when not.
   * **close layer** — `opacity 0`, `group_hover → opacity 1`, containing
     `icons::CLOSE` at **`size 12`**, colour `theme.text_muted`.
   * The close slot's own `mouse-down` calls `prevent_default()` **and**
     `stop_propagation()` — it must claim the press before it reaches the
     drag-carrying parent, or GPUI starts a tab drag instead of delivering the
     close click (6898-6904). Web: `onPointerDown` → `stopPropagation()` so the
     custom drag never arms.
2. **Title** — `min-w:0; truncate; font-size **ui_rems(11.5)**`; colour
   `theme.text` when active, `theme.text_muted` when not (6965-6976).
3. **Dirty dot** — only when the surface has unsaved changes: `flex-none; size
   6; rounded-full; background theme.text_muted` (6977-6985).

**Chip interactions**

| Interaction | Effect | Source |
| --- | --- | --- |
| left mouse-down | `window.prevent_default()` | 6853-6855 |
| click | `stop_propagation`; `set_right_active(surface)`; `focus_right_file_editor(surface)` | 6860-6864 |
| **middle** mouse-down | `close_right_surface(surface)` | 6866-6871 |
| drag (when `click_activation_drag_enabled()`) | payload `RightTabDrag { panel_key, from: ix, title, workspace_path }`; ghost is `SurfaceTabGhost` | 6872-6886 |
| ✕ click | `close_right_surface(surface)` — **closes that tab, not the pane** | 6898-6904, §5.5 R9 |

**Close semantics** — `close_right_surface(surface)` removes **that tab** from
the stored order. After a close, `resolved_right_active` picks the first
remaining tab, or `Picker` when the list empties (§2.1). **The pane does not
close.** Today `onClose = rightPaneStore.close(chatId)` closes the whole pane
while the aria-label says `"Close Changes"` (`right-tab-strip.tsx:60`, gap R9).

A surface's **closability** is `closable_right_surface` — see §2.3.1, which
transcribes it and every add/close path directly from the Rust.

#### 2.3.1 Surface add / close behaviours — transcribed from `shell.rs`

Research file 01 does not cover these; the following is transcribed from
`crates/ui/src/shell.rs:2284-3004` so the implementer does not have to guess.

**`session_links(source_session, cx)` — `shell.rs:2284-2299`** *(behaviour only)*
Builds the `LinkUi` every transcript (main and subagent) is handed: it carries
the owning chat id plus one handler that forwards each link activation to
`activate_session_link`. Web analogue: one link-click handler installed per
transcript, closed over the chat id that owns it.

**`activate_session_link(activation, …) -> LinkOutcome` — `shell.rs:2301-2341`**

| Step | Rule | Line |
| --- | --- | --- |
| 1 | **Reject** unless a chat is active **and** `activation.source_session == active_chat` **and** the app's `selected_chat == active_chat`. A link in a background chat's transcript can never act. | 2308-2313 |
| 2 | If the target is **not** a navigable URL (`navigation.is_err()`): for `LinkAction::Primary` or `Internal`, try `open_workspace_file_link(original)`; `Internal` on success, `Rejected` otherwise. | 2314-2324 |
| 3 | A `Primary` action resolves to `Internal` when the setting `open_web_links_in_roboco` is on, else `External`. | 2326-2332 |
| 4 | `resolved.web_outcome(macos_or_linux)` decides the final outcome. | 2333 |
| 5 | On `Internal`: open the right pane if closed (`toggle_right_pane`), then `add_browser_surface(url)`. | 2334-2339 |

**Web:** steps 1–3 port as-is (step 3's setting is ticket 03's). Step 5 is
**desktop-only** — with no `Browser` surface, an `Internal` outcome on web
degrades to `External` (open in a new browser tab). Step 2's workspace-file path
is the valuable half: a non-URL link opens a `File` tab.

**`add_browser_surface(url, …)` — `shell.rs:2343-2402`** — **desktop-only.** It
mints a native `BrowserSurface` (remote-aware), subscribes to its
`Changed` / `NewTab` / `Close` events, pushes a `Browser(id)` tab and activates
it. Do not port; there is no `Browser` variant on web.

**`add_diff_surface()` — `shell.rs:2404-2410`**
> "The picker's Diffs card / the `+` menu's Diff row: every click opens a
> **FRESH** diff tab with its own scope/base selection (multiple diff panels,
> user request)."

Creates `Changes::new(state)` and hands it to `register_diff_surface`. **No
dedupe** — N clicks make N tabs.

**`add_files_surface(window, cx)` — `shell.rs:2412-2472`**
> "Files is **single-instance per chat**: both the picker and the `+` menu focus
> the existing surface instead of creating duplicate trees and duplicate
> workspace subscriptions."

| Step | Rule | Line |
| --- | --- | --- |
| 1 | No-op when no chat is active. | 2416-2418 |
| 2 | `key = panel_key(cx)`. If `files` has **no** entry for `key`, build one `FilesSurface` seeded from the settings `files_autosave_enabled`, `files_autosave_delay_ms`, `files_editor_font_size`, `files_word_wrap`, `files_show_all`. | 2419-2437 |
| 3 | Subscribe to its `FilesEvent`s: `OpenFile(path)` → `add_file_surface(path)`; `OpenWebLink` → `activate_session_link`, opening the URL externally on `External`; `TitleChanged` / `FileRenamed` → repaint; `WordWrapChanged` / `ShowAllFilesChanged` → write the setting back; `CloseReady` → `on_file_close_ready(Files, key)`; `CloseCancelled` → `cancel_file_close(Files)`. | 2438-2464 |
| 4 | `push_unique_right_surface(tabs, Files)` — **already present ⇒ no second tab**. | 2468-2469 |
| 5 | `set_right_active(Files)` **and** `focus_right_file_editor(Files, window)` — so a repeat click **activates and focuses** the existing tab rather than adding one. | 2470-2471 |

**`add_file_surface(path, window, cx)` — `shell.rs:2474-2543`**
> "Open a workspace file as a first-class right-pane tab. Every editor is a
> separate `FilesSurface` so its tree, search, watcher and split layout stay
> stable while users move among open files."

| Step | Rule | Line |
| --- | --- | --- |
| 1 | No-op when no chat is active. | 2478-2480 |
| 2 | Lookup key is **`(panel_key, path)`**. If `file_surface_keys` already holds an id for it → `set_right_active(File(id))` + `focus_right_file_editor(File(id))` and **return** (no duplicate tab). | 2481-2488 |
| 3 | Otherwise `file_surface_seq += 1` — a **monotonic, stable id**, never reused — and build `FilesSurface::new_editor(state, chat, path, …same five settings…)`. | 2490-2504 |
| 4 | Subscribe to the same `FilesEvent` set as §Files, except `FileRenamed { old_path, new_path }` → `rename_file_surface(id, panel_key, old, new)`, and the close events carry `File(id)`. | 2505-2533 |
| 5 | Record `file_surfaces[id]`, `file_surface_paths[id] = path`, `file_surface_keys[(panel_key, path)] = id`, `file_surface_subs[id]`. | 2534-2537 |
| 6 | Push `File(id)` onto the tab list and `set_right_active(File(id))`. **Note: no `focus_right_file_editor` on the fresh-open path** — only on the re-activation path in step 2. | 2538-2542 |

The chip's **title** is `workspace_file_title(path)` (the basename) and is
**stable** because the id is stable and the path only changes through
`rename_file_surface`.

**`open_workspace_file_link(target, window, cx) -> bool` — `shell.rs:2545-2576`**

| Step | Rule | Line |
| --- | --- | --- |
| 1 | `false` unless the active chat exists and has a `cwd` root. | 2551-2562 |
| 2 | `resolve_workspace_file_link(target, root)` must resolve; else `false`. | 2563-2565 |
| 3 | Force the pane open **without** going through `toggle_right_pane`: record `was_open`, capture `from = right_target(cx)`, set `panels[key].changes_open = true`, and if it was shut start `right_tween = WidthTween::new(from, right_target(cx))`. | 2567-2573 |
| 4 | `add_file_surface(link.path)`, return `true`. | 2574-2575 |

**`rename_file_surface(id, panel_key, old_path, new_path, cx)` — `shell.rs:2578-2596`**
Guarded: returns immediately unless `file_surface_paths[id] == old_path`. Then
rewrites `file_surface_paths[id]`, removes `(panel_key, old_path)` from
`file_surface_keys` and inserts `(panel_key, new_path) → id` (`or_insert`, so an
existing entry for the new path wins), then repaints. **The tab keeps its id and
its position; only its title changes.**

**`add_history_surface()` — `shell.rs:2598-2603`**
> "The dedicated History surface. Keeping it as its own tab preserves its
> graph/search state while Diff tabs retain their ordinary scope picker."

`Changes::for_history(state)` → `register_diff_surface`. It is a `Diff(id)` tab
whose `Changes` reports `is_history()`, which is what switches its chip icon to
`git-branch`.

**`add_commit_diff_surface(commit)` — `shell.rs:2605-2614`**
> "A History row click: the commit opens as its own **pinned** diff tab (user
> request)."

`Changes::for_commit(state, commit)` → `register_diff_surface`.

**`register_diff_surface(changes)` — `shell.rs:2616-2632`**
`diff_seq += 1` → id; subscribe for `ChangesEvent::OpenCommit(commit)` →
`add_commit_diff_surface(commit)` (so a History tab spawns pinned commit tabs);
store `diffs[id]` + `diff_subs[id]`; push `Diff(id)` onto `right_tabs[panel_key]`;
`set_right_active(Diff(id))`.

**`add_terminal_surface()` — `shell.rs:2634-2650`**
> "The picker's Terminal card / the `+` menu's Terminal row: every click opens a
> **fresh embedded terminal tab**."

Gets the shared `right_terminal_panel`, calls `set_open(true)` then
`open_tab_for_selected(cx)`. **Only if that returns a tab key** does it push
`Terminal(tab)` and `set_right_active`. One panel entity, N `Terminal(tab)`
surfaces — each pane tab addresses one terminal tab by key.

**`on_transcript_event` — `shell.rs:2652-2676`**
> "Spawn-chip events from the primary transcript **AND** from subagent-tab
> transcripts (nested spawns open their own tabs)."

`TranscriptEvent::OpenSubagent { chat_id, doc_id, title, frozen }` →
`add_subagent_surface(...)`.

**`add_subagent_surface(chat_id, doc_id, title, frozen, cx)` — `shell.rs:2678-2737`**

| Step | Rule | Line |
| --- | --- | --- |
| 1 | The chip lives in the conversation column, so **open the pane first if closed** (`toggle_right_pane`). | 2690-2694 |
| 2 | If a `subagent_tabs` entry already has this `doc_id` → `set_right_active(Subagent(id))` and return. **One tab per doc.** | 2695-2702 |
| 3 | `subagent_seq += 1` → id. Build `Transcript::for_doc(state, doc_id, follow = !frozen)` — "a **live** subagent follows its streaming end (main-transcript feel); a **frozen** one reads top-down." | 2703-2708 |
| 4 | Install `session_links(Some(active_chat))` on it and subscribe it to `on_transcript_event`, so a nested spawn opens its own tab. | 2709-2713 |
| 5 | `frozen` → `spawn_subagent_snapshot_fetch(chat_id, doc_id)`; otherwise `state.watch_subagent_doc(doc_id)`. | 2714-2720 |
| 6 | Insert `SubagentTab { doc_id, title, transcript, _fetch, _events }`, push `Subagent(id)`, `set_right_active(Subagent(id))`. | 2721-2736 |

The chip's **title** is the `title` carried by the spawn chip event.

**`spawn_subagent_snapshot_fetch(chat_id, doc_id, cx)` — `shell.rs:2739-2784`**
> "Fetch a finished subagent's **frozen transcript blob** (`{chat_id}/{doc_id}`);
> on **ANY** failure fall back to watching the doc — the blob upload is
> best-effort engine-side."

* No routable engine for `chat_id` → immediately `watch_subagent_doc` and return
  `None` (2748-2752).
* Strips the engine scope off both ids (`ScopedId::parse(...).raw_id`) and builds
  `blob_ref = "{raw_chat}/{raw_doc}"` (2753-2757).
* Calls `methods::FETCH_TOOL_BLOB` with `{ "blobRef": blob_ref }` and a **20 s**
  timeout (2760-2768).
* Parses `reply.text` as JSON `Vec<SessionMessageEntry>`; re-scopes the entries to
  the engine key; `Some(entries)` → `set_subagent_snapshot(doc_id, entries)`,
  `None` → `watch_subagent_doc(doc_id)` (2769-2782).

**`close_right_surface(surface, window, cx)` — `shell.rs:2786-2852`**
> "A surface tab's ✕. The active fallback happens naturally through
> `resolved_right_active` on the next frame."

| Step | Rule | Line |
| --- | --- | --- |
| 1 | Record `was_active = resolved_right_active(cx) == surface`; `key = panel_key(cx)`. | 2794-2795 |
| 2 | **Files / File(id) take the unsaved-changes path.** Look the entity up and call `prepare_close(cx)`: `Allow` → `complete_file_close(surface, key)`; `Pending` or `Blocked` → insert into `pending_file_closes` **and `set_right_active(surface)`** (the tab you tried to close is brought forward so you can see the prompt), then **return** — the tab is not removed. | 2796-2812 |
| 3 | Every other kind: `right_tabs[key].retain(|s| *s != surface)` first. | 2813-2815 |
| 4 | Per-kind teardown: **`Browser(id)`** → `browsers.remove(id).close(cx)`, drop its sub, and **if it was active, focus the composer** *(desktop-only)*; **`Diff(id)`** → `diffs.remove(id)` + `diff_subs.remove(id)` — "dropping the entity tears down its diff watch"; **`Terminal(tab)`** → `right_terminal_panel.close_tab_by_key(tab, window)`; **`Subagent(id)`** → `subagent_tabs.remove(id)` then `state.unwatch_subagent_doc(doc_id)` — "unwatch drops the watch task — that cancels the engine-side watch and unpins the subagent doc from the engine LRU"; **`Picker`** → nothing. | 2816-2845 |
| 5 | `panels[key].right_active = Picker` **if** it was this surface — the next frame's `resolved_right_active` then falls to the first remaining tab, or stays on `Picker` when the list is empty. | 2846-2850 |

**The unsaved-file close protocol**

* **`on_file_close_ready(surface, panel_key, cx)` — `shell.rs:2854-2868`**: if
  `surface` is in `pending_file_closes` → `complete_file_close`. Otherwise, if a
  `pending_exit` is in flight → `reveal_unsaved_file`; either way repaint.
* **`cancel_file_close(surface, cx)` — `shell.rs:2870-2874`**: drops the surface
  from `pending_file_closes` **and clears `pending_exit` entirely** — cancelling
  one file's close cancels the whole window-close/quit cascade.
* **`complete_file_close(surface, panel_key, cx)` — `shell.rs:2975-3004`**:
  removes the tab from `right_tabs[panel_key]`; for `Files` drops
  `files[panel_key]` + its sub; for `File(id)` drops `file_surfaces[id]`,
  `file_surface_paths[id]`, `file_surface_subs[id]` and every
  `file_surface_keys` entry pointing at `id`; **returns early for any other
  kind**; clears the pending entry; resets `right_active` to `Picker` if it was
  this surface.
* **`reveal_unsaved_file(cx)` — `shell.rs:2943-2966`**: collects every dirty
  surface as `(panel_key, RightSurface)` — the per-chat `Files` browsers plus the
  `File(id)` editors — sorts by `(key != current_panel_key, key)` so **a dirty
  file in the current chat wins**, then for the first one sets
  `panels[key].changes_open = true`, `panels[key].right_active = surface`, and
  `apply_nav(NavEntry::Chat(key))` — i.e. it **navigates to the chat that owns
  the unsaved file** and puts it on screen.
* **`all_file_edits_flushed(cx) -> bool` — `shell.rs:2968-2973`**: true when no
  `Files` browser and no `File` editor reports `has_unsaved_changes()`.

**`close_active_surface(window, cx) -> bool` — `shell.rs:2886-2895`**
> "The first rung of `⌘W` / Window > Close Window: when the right pane is open on
> a real surface (the file / diff / terminal / browser tab the user just opened),
> close **THAT** and leave the window alone. Returns true when the close was
> consumed by the pane. The pane's empty picker state and a closed pane both
> yield false, so the caller falls through to `prepare_window_close` and the
> window closes — the same cascade browsers use. The native traffic-light close
> deliberately skips this rung: it always closes the window."

**`closable_right_surface(cx) -> Option<RightSurface>` — `shell.rs:2897-2908`**

```
if !right_pane_open(cx)            → None
match resolved_right_active(cx) {
    RightSurface::Picker          → None
    surface                       → Some(surface)
}
```

So: **every kind is closable except `Picker`**, and only while the pane is open.
`Files` is closable like any other tab (it is single-instance, not
un-closable) — it simply routes through the unsaved-changes path first.
A closed pane and the new-session canvas both yield `None`.

**`prepare_window_close` / `prepare_quit` / `prepare_exit` / `PendingExit`**

`PendingExit` (`shell.rs:1010-1015`) is `CloseWindow | Quit | InstallUpdate(PathBuf)`.
`prepare_window_close` = `prepare_exit(CloseWindow)` (`:2876-2878`);
`prepare_quit` = `prepare_exit(Quit)` (`:2910-2912`); the staged-update apply path
uses `prepare_exit(InstallUpdate(staged))` (`:5152`).

**`prepare_exit(action, cx) -> bool` — `shell.rs:2914-2941`**

| Step | Rule | Line |
| --- | --- | --- |
| 1 | Gather every `Files` browser and every `File` editor. | 2915-2920 |
| 2 | If **none** has unsaved changes → clear `pending_exit`, return `true` (exit immediately). | 2921-2927 |
| 3 | Otherwise latch `pending_exit = Some(action)` and call `prepare_close(cx)` on **every** surface, ANDing the dispositions. | 2928-2933 |
| 4 | All `Allow` → clear `pending_exit`; otherwise `reveal_unsaved_file(cx)`. Repaint; return `all_ready`. | 2934-2940 |

**Web analogue: `beforeunload`.** The browser gives one synchronous hook and one
generic confirmation dialog — it cannot show the desktop's per-file prompt, and
it cannot navigate to the offending chat before asking. So:
* Register a `beforeunload` handler that calls `allFileEditsFlushed()`; if false,
  `event.preventDefault()` (the browser shows its own generic prompt).
* Still run `revealUnsavedFile()` **before** any in-app navigation that would
  discard edits (chat switch, route change), where a real prompt is possible.
* `PendingExit::InstallUpdate` and `Quit` are desktop-only; only the
  `CloseWindow` shape has a web analogue.
* The ✕/`Mod+W` cascade is **not** reproducible: `Mod+W` is browser-reserved
  (ticket 12 §2.9), so `close_active_surface`'s "surface before window" rung has
  no trigger on web. Keep `closableRightSurface` as an exported predicate anyway
  — the tab ✕ and middle-click already use `closeRightSurface`, and a future
  in-app close-tab affordance needs it.

**Drag reorder** (6732-6759, 6986-7012)

```
rel_x = pointer.x − strip.bounds.left − scroll.offset().x      // CONTENT coords
over  = terminal::panel::drop_index(rel_x, CHIP_SLOT(116), count)
```

`update_right_tab_drag_over(from, over)` (2112-2131) bumps `epoch` whenever the
hovered slot changes, which restarts the slide tween. **A drag whose `panel_key`
differs from the current chat's is ignored.**

Rendering under a drag:
* `ix != from` → wrapped in `relative` and animated with
  `with_animation(("right-tab-slide", ix | (epoch << 32)), TAB_SLIDE (150 ms,
  ease-out), |el, t| el.left(lerp(start, target, t)))` where
  `target = slide_offset(ix, from, over) · CHIP_SLOT` and
  `start = slide_offset(ix, from, prev_over) · CHIP_SLOT`.
* `ix == from` → an **invisible spacer** `width CHIP_W; height 24; flex-none`
  (the ghost carries the chip).

On drop: reorder to `right_tab_drag.over` (or `payload.from` if lost). Drag state
is healed at the top of the render if `!cx.has_active_drag()` (6694-6697).

> Today the strip uses **native HTML5 `draggable`** with the browser's drag image
> and a `transform` transition (gap R13). Replace with **pointer events** +
> a custom ghost element; the browser's drag image is visibly different and
> cannot be styled. `slideOffset` in `right-tab-strip.tsx:71-82` already computes
> the right ±`CHIP_SLOT` offsets — keep it, add the `epoch`-restart semantics and
> the invisible-spacer case for `ix === from`.

**`SurfaceTabGhost`** (`shell.rs:749-771`): `height 24; width 112; padding-x 8;
flex; align-items:center; radius 6; background theme.surface_raised; 1px solid
theme.border_strong; font-size ui_rems(11.5); colour theme.text; opacity 0.85`,
containing a truncated title.

**`SurfaceTabTooltip`** (`shell.rs:773-792`): `max-width 380; padding-x 9;
padding-y 6; radius 6; 1px solid theme.border; background theme.surface_overlay;
font-size **10.5px** (a raw px, NOT ui_rems); colour theme.text_muted`.
Show delay **350 ms**.

**The `+` button** (7013-7053, mounted only when `count > 0` — 7145-7147)

| Property | Value |
| --- | --- |
| id | `"right-surface-add"` |
| size | `24 × 24`, `flex-none`, centred |
| radius | `6` |
| background | `hover_blend("right-surface-add-fade", wash(0.0), wash(0.11))` |
| hit-testing | `.block_mouse_except_scroll()` |
| icon | `icons::PLUS` at `size 13`, colour `theme.text_muted` |
| click | press-was-open toggle: a mouse-down notes whether the menu was open, the subsequent click closes if it was, else opens |

**The `+` menu** (7054-7144) — `popover::anchored_menu_below_gap("right-plus-menu",
menu, closing, gap = 10)`; card `popover_card(theme).w(**168**)`; rows in a
`flex column; gap 2`. Each row is `popover::menu_row` with a `size 13` icon at
`theme.text_muted`:

| Row id | Icon | Label (verbatim) | Condition |
| --- | --- | --- | --- |
| `right-plus-files-row` | `folder-with-files` | `"Files"` | always |
| `right-plus-browser-row` | `globe` | `"Browser"` | always — **omit on web** |
| `right-plus-terminal-row` | `terminal` | `"Terminal"` | always |
| `right-plus-diff-row` | `list` | `"Diffs"` | `space_git_detected()` |
| `right-plus-history-row` | `git-branch` | `"History"` | `space_git_detected()` |

`popover::popover_card` (`popover.rs`, via §3.13): `1px solid hairline(0.10)`,
radius `CARD_RADIUS` (**12**), `shadow-lg`, `padding 4`, `overflow hidden`,
`font-size ui_rems(13.0)`, colour `theme.text`, background `theme.glass_overlay()`
when frosted else `theme.surface_overlay`, with a 44 px backdrop blur behind it.
`popover::menu_row` (`popover.rs:713-746`): `flex row; align-items:center; gap
10; padding-x 8; padding-y 6; radius 8; font-size ui_rems(13.0); cursor
pointer`. Active → background `card_selected_bg()`, colour `theme.text`.
Inactive → colour `hover_blend(key, theme.text.opacity(0.9), theme.text)`,
background `hover_blend(key, wash(0.0), card_selected_bg())`.

> **Ticket 09 owns the popover primitive** (the `open → closing → closed`
> lifecycle with `MENU_IN` 140 ms in / `MENU_OUT` 100 ms out). This ticket may
> use the existing `components/picker-popover.tsx` as a placeholder host and must
> leave a Comment saying so; it must **not** invent a second popover lifecycle.
> `close_right_plus` is step 7 of ticket 06's Escape ladder — register it there.

**Edge fades** (7148-7197) — two implementations:
* **Glass** themes: `edge_fade::edge_faded(FADE_WIDTH = 36, false, false,
  region).fade_left(fade_left).fade_right(fade_right)` — a per-pixel scope that
  fades glyphs **and** quads/images, so the chips' washes dissolve across the
  band.
* **Opaque** themes: painted gradient overlays — `absolute; top 0; bottom 0;
  width 36` on the relevant side, `linear-gradient(90deg, theme.surface 0%,
  theme.surface@0 100%)` on the left and `linear-gradient(270deg, …)` on the
  right.

**Web:** use a CSS `mask-image` on the scroller (glass-safe, unlike a painted
overlay) with the **quadratic** ramp the gpui shader uses
(`alpha = (d / band)²`, `edge_fade.rs`, §3.29):

```css
.right-tab-strip[data-fade-left][data-fade-right] {
  mask-image: linear-gradient(to right,
    rgba(0,0,0,0) 0px, rgba(0,0,0,0.0625) 9px, rgba(0,0,0,0.25) 18px,
    rgba(0,0,0,0.5625) 27px, rgba(0,0,0,1) 36px,
    rgba(0,0,0,1) calc(100% - 36px), rgba(0,0,0,0.5625) calc(100% - 27px),
    rgba(0,0,0,0.25) calc(100% - 18px), rgba(0,0,0,0.0625) calc(100% - 9px),
    rgba(0,0,0,0) 100%);
}
```
with `[data-fade-left]`-only and `[data-fade-right]`-only variants. Set the two
data attributes from a `scroll` listener using the 1 px dead-zone formula above.

---

### 2.4 `render_surface_picker` — the right pane's empty state

`shell.rs:6594-6675`. A compact vertical list; the old two-card grid clipped in
narrow panes and wasted short ones.

**Container**: `size_full; flex; align-items:center; justify-content:center;
padding 16`, holding `w-full; max-width 280; flex column; gap 8`.

**Row** (6600-6624)

| Property | Value |
| --- | --- |
| width / height | `100%` / `44` |
| padding-x | `14` |
| radius | `10` |
| border | `1px solid theme.border` |
| background | `Theme::ink(0.02)` |
| hover | background `Theme::ink(0.05)`, border `theme.border_strong` |
| display | `flex row; align-items:center; gap 10` |
| cursor | `pointer` |
| icon | `size 15`, `flex-none`, colour `theme.text_muted` |
| label | `font-size ui_rems(13.0); weight MEDIUM; colour theme.text` |

**Rows in order** (6638-6672)

| id | Icon | Label | Condition |
| --- | --- | --- | --- |
| `surface-card-files` | `folder-with-files` | `"Files"` | always |
| `surface-card-browser` | `globe` | `"Browser"` | always — **omit on web** |
| `surface-card-terminal` | `terminal` | `"Terminal"` | always |
| `surface-card-diffs` | `list` | `"Diffs"` | `space_git_detected()` |
| `surface-card-history` | `git-branch` | `"History"` | `space_git_detected()` |

The picker is what `resolved_right_active` falls back to when the tab list is
empty, and the tab list **starts empty** (gap R10). Opening the pane on a fresh
chat therefore lands on the picker, not on a fixed four-tab row.

---

### 2.5 Adding surfaces

Each picker row and each `+`-menu row calls one `add_*` path. The exact Rust for
every one of them — including the close paths and the unsaved-file protocol — is
transcribed in **§2.3.1**; this table is the picker-row → action map.

| Action | Behaviour |
| --- | --- |
| **Files** | **single instance** — `push_unique_right_surface` returns `false` if a `Files` tab already exists; activate the existing one instead of adding a second. Desktop test: `files_surface_is_single_instance_per_tab_list` (`shell.rs:8595`). |
| **File(path)** | **one tab per path.** The tab's title is `workspace_file_title(path)` = the basename, and it is **stable** — reordering, dirtying or reopening never changes it. Desktop test: `file_editors_are_distinct_surface_tabs_with_stable_titles` (8603). Opening an already-open path activates that tab. |
| **Diffs** | adds a `Diff(id)` tab. Multiple diff tabs coexist (N commit diffs). The tab's icon is `git-branch` when that `Changes` `is_history()`, else `list`; its title is the diff's scope label or the pinned commit subject. Reopening the pane onto a diff tab revalidates its watch (`toggle_right_pane`, 1959-1993). |
| **History** | a `Diff(id)` whose `Changes` reports `is_history()` — same surface family, different icon and title. Body is **ticket 27**. |
| **Terminal** | **single instance** in the pane: a `Terminal(tab)` surface whose body is the embedded terminal panel with `set_resize_suspended(tween_active)` and `select_tab_by_key(tab)`. The terminal's own *tabs* are the dock's concern (ticket 26), not the pane's. |
| **Subagent(id)** | added programmatically (from a transcript row), not from the picker. Body is `flex column` of a read-only transcript plus its own jump pill. |

`set_right_active(surface)` sets the per-chat `right_active` and, for a file
surface, focuses its editor (`focus_right_file_editor`). If the pane is closed,
adding a surface opens it.

---

### 2.6 `resize_handle` — the pane seams

`shell.rs:5708-5804` (the sidebar and the right pane share it; the terminal's
horizontal variant is ticket 26).

**Constants** (173-175, asserted at 8401-8406)

| Constant | Value |
| --- | --- |
| `PANE_RESIZE_HITBOX_HALF_WIDTH` | **10** (hitbox is 20 px wide) |
| `PANE_RESIZE_HITBOX_TOP` | `Theme::TITLEBAR_HEIGHT` = **38** |
| `TERMINAL_RESIZE_HITBOX_HEIGHT` | **10** |

Vertical pane hitboxes deliberately **yield the global titlebar** — they start
38 px down so the titlebar chrome stays clickable across an animated pane
boundary. Test: `pane_resize_hitboxes_yield_the_titlebar_chrome` (8401).

**Layout**

| Property | Value |
| --- | --- |
| position | `absolute; top 38; bottom 0` |
| width | `PANE_RESIZE_HITBOX_HALF_WIDTH · 2` = **20**, `flex-none` |
| hit-testing | `.occlude()` |
| cursor | `col-resize` |
| visual | a 1 px child at `left 10`, `top 0; bottom 0`, split into two `flex_1` halves: the top half is `linear-gradient(180deg, clear 0%, highlight 100%)`, the bottom half `linear-gradient(180deg, highlight 0%, clear 100%)` — a stronger centre highlight that fades into the panel's existing 1 px border toward both ends |

`highlight` (5726-5735):
```
active      = pane_resize_active   == Some(kind)
constrained = pane_resize_dragging == Some(kind) && !active
highlight   = constrained ? theme.border_strong.opacity(0)
            : active      ? theme.border_strong
            :               hover_blend(key, border_strong.opacity(0), border_strong)
clear       = highlight.opacity(0)
```

> **Add the constrained state** (gap P8): while dragging **at a clamped edge**
> the line goes to opacity 0 — the seam goes dark to say "you've hit the limit".
> `.pane-seam-line` currently only knows rest (0) and hover/drag (1).

**Interactions**

| Interaction | Effect |
| --- | --- |
| mouse-down (left) | `pane_resize_dragging = Some(kind)`, `pane_resize_active = Some(kind)` |
| drag | `on_drag(marker(), …)` with `stop_propagation` and the invisible `DragGhost` |
| mouse-up with `click_count == 2` | `reset(...)` → sidebar: `settings.sidebar_width = SIDEBAR_DEFAULT (256)`; right pane: `settings.right_pane_width = RIGHT_PANE_DEFAULT (520)`; both clear their edge bounce. Then save. |
| mouse-up / mouse-up-out | `finish_pane_resize(kind)`, force the hover fade to 0, `window.refresh()` |

**Mount points** (8001-8024): the handles float over the seams inside
**zero-width** boxes so the sidebar's right gutter stays exactly as wide as its
left one (a 5 px flex child read as lopsided). Both are offset
`left(−PANE_RESIZE_HITBOX_HALF_WIDTH)` so the 20 px target straddles both
adjacent panes. The right seam is `absolute; left 0; top 0` inside the right
column and painted **after** the page, so page input cannot occlude its inner
half.

The right handle is mounted **only when** `right_open && !panel_handoff &&
!right_pane_expanded && !tween_active(right_tween)` (7943-7947) — takeover
derives its width from the viewport, so a manual drag would fight the target.

> `app-shell.tsx:328` mounts it on `hasPane && pane.open && !pane.expanded` —
> **missing the `!tween_active` and `!panel_handoff` guards**, so the handle can
> appear mid-animation (gap P14). Add a `gliding` flag from `usePaneGlide`.

**`on_sidebar_drag`** (3086-3107):
```
sample = resize_drag_sample(pointer.x, SIDEBAR_MIN(224), SIDEBAR_MAX(400),
                            latched = sidebar_resize_edge, reduced_motion)
settings.sidebar_width      = sample.width
settings.sidebar_collapsed  = false           // dragging always expands
pane_resize_dragging        = Sidebar
sidebar_tween               = None            // live drag tracks the pointer
if sample.starts_bounce { sidebar_edge_bounce = ResizeEdgeBounce::new(edge) }
else if sample.edge.is_none() { sidebar_edge_bounce = None }
pane_resize_active          = sample.edge.is_none() ? Some(Sidebar) : None
sidebar_resize_edge         = sample.edge
```

**`on_right_pane_drag`** (3123-3163): `width = viewport − pointer.x`;
`max = right_pane_max_width(viewport, sidebar_target())`. If
`max >= RIGHT_PANE_MIN(360)` it uses `resize_drag_sample(width, 360, max,
latched, reduced)`; otherwise it **pins to `max` with no edge and no bounce**.
Then the same latch/bounce/active bookkeeping, plus clearing `right_tween`,
`right_takeover_content_tween` and `main_takeover_tween`.

**`resize_drag_sample`** (`motion.rs:266-286`):
```
edge  = requested <= min ? Min : requested >= max ? Max : None
width = clamp(requested, min, max)
starts_bounce = !reduced_motion && edge.is_some() && edge != latched_edge
```
The latch is what makes a **held** pointer produce exactly one nudge instead of
restarting the animation on every drag event.

**`resize_bounce_offset`** (`motion.rs:310-323`) — a rounded two-phase pulse over
220 ms with zero velocity at both joins:
```
magnitude = raw < 0.32 ? smoothstep(raw / 0.32)
                       : 1 − smoothstep((raw − 0.32) / 0.68)
magnitude *= RESIZE_EDGE_NUDGE (5)
offset = edge == Min ? −magnitude : +magnitude
smoothstep(t) = t²(3 − 2t)
```

`eval_resize_edge_bounce` (3769-3788) returns **0** under reduced motion or when
`enabled` is false, and flags `motion_active` while in flight.

**Web plumbing:** port `resize_drag_sample` and `resize_bounce_offset` as pure
functions in `state/layout.ts`; drive the 220 ms pulse from a `requestAnimation
Frame` loop in `PaneSeam` that writes a `--rb-pane-edge-offset` px value added to
the column's width. Under `prefers-reduced-motion: reduce` the offset is always 0
and `starts_bounce` is always false.

---

## 3. Pure logic to port

Copied from research §4. Signatures target
`web/packages/app/src/state/right-pane.ts` and `state/layout.ts`; tests go to
`web/packages/app/tests/right-pane.test.ts` and `tests/layout.test.ts`.

### 3.1 Per-chat panel flags (§4.11)

```
ChatPanels { terminal_open, changes_open, right_active }   // all default CLOSED
panel_key() = selected_chat.is_none()
                ? format!("space-canvas:{space}")
                : chat_id
resolved_right_active() = stored pick if still in the live tab list
                        : first remaining tab
                        : Picker
```

Desktop tests → web tests (`tests/right-pane.test.ts`):
- `session_panels_default_closed_per_chat` (`shell.rs:8530`)
- `session_panels_flags_are_chat_scoped` (8543)
- `session_panels_both_flags_coexist_per_chat` (8563)
- `session_panels_update_tracks_right_surfaces` (8582)
- `files_surface_is_single_instance_per_tab_list` (8595)
- `file_editors_are_distinct_surface_tabs_with_stable_titles` (8603)

### 3.2 Right surfaces (§4.12)

```
push_unique_right_surface(tabs, s) → false if already present, else push + true
workspace_file_title(path) = path.rsplit('/').next().unwrap_or(path)
right_surface_rows(...)    → [(surface, title, is_dirty, detail)], skipping
                             entries whose backing entity is gone
```

### 3.3 Pane width math (§4.3)

```
right_pane_max_width(viewport, sidebar)      = max(viewport − sidebar − 300, 0)
right_pane_takeover_width(viewport, sidebar) = max(viewport − sidebar, 0)
conversation_width(viewport, sidebar, right) = max(viewport − sidebar − right, 0)
stable_panel_content_width(target, Some((from,to))) = max(from, to)
stable_panel_content_width(target, None)            = target
right_panel_content_width(target, transition, Some(tw)) = tw
right_panel_content_width(target, transition, None)     = stable_panel_content_width(...)
```

Asserted values (8283-8297, 8416-8444):
`right_pane_max_width(1200,256) = 644`; `right_pane_max_width(800,256) = 244`;
`right_pane_takeover_width(1200,256) = 944`;
`right_panel_content_width(0, Some((520,0)), None) = 520`;
`right_panel_content_width(1064, Some((520,1064)), Some(760)) = 760`;
`conversation_width(1320,256,520) = 544`; `conversation_width(1320,256,1064) = 0`.

Desktop tests → web tests:
- `right_pane_ceiling_preserves_the_chat_floor` (8283)
- `right_pane_takeover_consumes_the_chat_column` (8293)
- `right_panel_content_keeps_the_larger_width_only_during_transition` (8416)
- `pane_resize_hitboxes_yield_the_titlebar_chrome` (8401)
- `right_pane_takeover_control_reverses_direction` (8396)

### 3.4 Resize drag sampling and edge bounce (§4.4)

```ts
type ResizeEdge = "min" | "max" | null;
interface ResizeSample { width: number; edge: ResizeEdge; startsBounce: boolean }
resizeDragSample(requested, min, max, latched, reducedMotion): ResizeSample
resizeBounceOffset(edge: ResizeEdge, elapsedMs: number): number   // 220ms, ±5px
```

Desktop tests → web tests (`tests/layout.test.ts`):
- `sidebar_drag_nudges_each_edge_once_until_rearmed` (8137)
- `sidebar_drag_stays_exact_in_range_and_reduced_motion_never_nudges` (8166)
- `right_pane_uses_the_shared_clamp_and_edge_latch` (8185)
- `sidebar_bounce_has_rounded_out_and_return_phases` (8208)

### 3.5 Tab drag geometry

```
rel_x = pointer.x − strip.left − scroll.offset().x
over  = drop_index(rel_x, CHIP_SLOT(116), count)
slide_offset(ix, from, over) ∈ {−1, 0, +1}, multiplied by CHIP_SLOT
```

No named desktop test in 01 §4; cover it with `tests/right-pane.test.ts` cases
`drop index picks the slot under the pointer` and
`siblings slide one slot toward the vacated index`, mirroring the existing
`slideOffset` in `right-tab-strip.tsx:71-82`.

---

## 4. Gaps this ticket closes

Copied verbatim from research §5, filtered to this ticket.

### From §5.1 Blockers

| # | Item | Kind | Desktop | Web | Fix |
| --- | --- | --- | --- | --- | --- |
| B1 | **The right pane renders nothing** | WRONG BEHAVIOUR | `render_right_pane` hosts Files / File editors / Diff / History / Terminal / Browser / Subagent / the picker (`shell.rs:6452-6549`) | `const SURFACES_DISABLED = true;` (`right-pane.tsx:39`, "TEMPORARY — bisecting the sidebar-toggle flash"). Every surface is short-circuited to `null` (`:82`). | Remove the flag and fix the flash properly. Until it is gone, **no pane-surface parity claim is true**. |
| B2 | Terminal PTY minted with no UI | WRONG BEHAVIOUR | the panel entity is created lazily *by the toggle* and rendered | `right-pane.tsx:58` opens a PTY when `pane.open && active === "terminal"`, but `TerminalDock` never mounts (B1) | falls out of B1 |
| B3 | **Right-pane state is not persisted at all** | MISSING | `SessionPanels` is per-chat **in-memory** (correct), but `settings.right_pane_width`, `sidebar_width`, `sidebar_collapsed`, `terminal_height` all persist to `ui-settings.json` (`shell.rs:3167-3180`) | `state/right-pane.ts` `#byChat` is entirely in-memory — the pane's **width** is lost on reload | persist `right_pane_width` (global, not per chat) alongside the sidebar; keep open/expanded/active/tabs in memory, matching the desktop |

### From §5.4 Panes, seams and resizing

| # | Item | Kind | Desktop | Web | Fix |
| --- | --- | --- | --- | --- | --- |
| P5 | `setWidth` clamp | MATCHES | when `max < RIGHT_PANE_MIN` the pane pins to `max` — the chat floor wins | `right-pane.ts:126` reproduces it | ✅ |
| P6 | Seam hitbox | MATCHES | 20 px wide, `top: TITLEBAR_HEIGHT`, centred on the seam (offset `−10`) | `.pane-seam { width: 20px; top: var(--rb-titlebar-height); left: calc(… − 10px) }` | ✅ |
| P7 | Seam visual | WRONG VALUE | **two half-height gradients** meeting at the vertical centre: top half `clear → highlight`, bottom half `highlight → clear`, where `highlight = theme.border_strong` (§3.23) | one `linear-gradient(to bottom, transparent, border-strong 50%, transparent)` — the same shape | ✅ in effect |
| P8 | Seam rest state | WRONG BEHAVIOUR | at rest the highlight is `hover_blend(key, border_strong@0, border_strong)`, i.e. **opacity 0 fading in over 150 ms**; while **dragging and constrained** it is forced to `border_strong@0` (the seam goes dark to say "you've hit the limit") | `.pane-seam-line { opacity: 0 }` → 1 on hover/drag; **no constrained state** | add: while dragging at a clamped edge, hide the line |
| P9 | **Edge-bounce** | MISSING | hitting min/max nudges the pane **5 px** past the limit over **220 ms** with a two-phase smoothstep (out for the first 32 %, back over the rest), latched so a held pointer bounces once (§3.23) | nothing — the width just stops | add; it is the only feedback that a limit was reached |
| P10 | Double-click reset | MATCHES | resets to `SIDEBAR_DEFAULT` / `RIGHT_PANE_DEFAULT` and saves | `onDoubleClick={onReset}` | ✅ |
| P11 | Drag un-collapses the sidebar | MATCHES | `on_sidebar_drag` sets `sidebar_collapsed = false` | `setWidth` does the same | ✅ |
| P12 | Transition suppression during drag | MATCHES in effect | the desktop clears the tween (`sidebar_tween = None`) so the width tracks the pointer | `data-rb-resizing` kills the CSS transitions | ✅ |
| P13 | Seam keyboard resize | MISSING on **both** | neither side supports it | `role="separator"` with no `tabIndex`, no `aria-valuenow/min/max`, no arrow keys | not a parity item, but an a11y gap worth recording |
| P14 | Right seam hidden in takeover | MATCHES | the handle is mounted only when `right_open && !panel_handoff && !right_pane_expanded && !tween_active` | `hasPane && pane.open && !pane.expanded` | ✅ — but the web is missing the `!tween_active`/`!panel_handoff` guards, so the handle can appear mid-animation |
| P15 | Right-pane container | MATCHES | clipped outer + **right-anchored inner at the larger endpoint's width**, so descendants keep their geometry through the 200 ms transition | `usePaneGlide` + `.right-pane-inner { position:absolute; right:0 }` reproduces it | ✅ |
| P16 | Right-pane background | WRONG VALUE | `theme.is_glass() ? theme.bg.opacity(0.4) : theme.bg` | `color-mix(in srgb, var(--rb-bg) 40%, transparent)` unconditionally | branch on the resolved surface treatment |
| P17 | Right-pane padding-top | MATCHES | `TITLEBAR_HEIGHT` (38) | `.right-pane-body { padding-top: var(--rb-titlebar-height) }` | ✅ |
| P18 | Takeover border | MATCHES | `border_l_1` omitted in takeover | `.right-pane-expanded .right-pane-inner { border-left: none }` | ✅ |
| P19 | `toggle()` preserves `expanded` | WRONG BEHAVIOUR | `toggle_right_pane` **always leaves takeover** when it closes (`shell.rs:1970-1975`) | `rightPaneStore.toggle` leaves `expanded` alone; only `close()` clears it — so closing from takeover and reopening lands **back in takeover** | clear `expanded` on close in `toggle()` |

### From §5.5 Right-pane tabs

| # | Item | Kind | Desktop | Web | Fix |
| --- | --- | --- | --- | --- | --- |
| R1 | `CHIP_W` / `CHIP_SLOT` | MATCHES | 112 / 116 | same | ✅ |
| R2 | Chip geometry | MATCHES | `height 24; padding-left 4; padding-right 8; radius 6; gap 3`; active `wash(0.10)`, hover `wash(0.06)` | `.right-tab` reproduces it | ✅ |
| R3 | Chip title font | WRONG VALUE | `ui_rems(11.5)` | `.right-tab { font-size: 12px }` | 11.5 |
| R4 | Leading slot | MATCHES | `18 × 18; radius 4`; icon ↔ ✕ opacity swap on **group** hover; close-slot hover `wash(0.12)` | `.right-tab-slot` + `.right-tab-close` reproduce it | ✅ |
| R5 | Icon sizes | WRONG VALUE | icon **12** (14 for file-type icons), close **12** | `Icon(…, 13)` and `Icon("close", 11)` | 12 / 12 |
| R6 | Inactive icon colour | MISSING | `theme.text_muted.opacity(0.7)` when inactive, `theme.text_muted` when active; file icons get `opacity 0.78` when inactive | one colour | add |
| R7 | **Dirty dot** | MISSING | a `6 px` `rounded-full` `text_muted` dot when the surface has unsaved changes (`shell.rs:6977-6985`), plus `", unsaved changes"` on the aria-label | absent | add |
| R8 | **Tooltip** | MISSING | `SurfaceTabTooltip` — `max-width 380; padding 6px 9px; radius 6; 1px solid border; background surface_overlay; font-size 10.5px; colour text_muted`, **350 ms** show delay, showing the file path / browser URL | none | add |
| R9 | The ✕ closes the pane, not the tab | WRONG BEHAVIOUR | `close_right_surface(surface)` removes **that tab** | `onClose = rightPaneStore.close(chatId)` closes the whole pane, while the aria-label says `"Close Changes"` | close the tab |
| R10 | Fixed tab set | WRONG BEHAVIOUR | tabs are **created on demand** from the picker / `+` menu and removed on close; the list starts **empty** and lands on the picker | `tabs` is always `["changes","files","terminal","preview"]` | make the tab list dynamic |
| R11 | **Surface picker** | MISSING | the empty state: a centred `max-width 280` column of `44 px` rows (`radius 10; 1px border; ink(0.02)`, hover `ink(0.05)` + `border_strong`; icon 15; label 13 MEDIUM) — Files / Browser / Terminal / (Diffs / History when git) (§3.22) | absent | add |
| R12 | **`+` button and its menu** | MISSING | a `24 × 24` `+` after the last chip (only when `count > 0`), opening a `168 px` menu with the same five rows (§3.21) | absent | add |
| R13 | Drag reorder animation | WRONG BEHAVIOUR | pointer drag with a `112 × 24` **ghost chip** (`surface_raised`, `1px border_strong`, `opacity 0.85`), siblings sliding `±CHIP_SLOT` over `TAB_SLIDE` (150 ms ease-out), the dragged slot left as an invisible spacer | native HTML5 `draggable` with a browser drag image, and a `transform` transition on `.right-tab` | use pointer events + a custom ghost; the browser's drag image is visibly different |
| R14 | Middle-click closes | MATCHES | `on_mouse_down(Middle) → close_right_surface` (`shell.rs:6866-6871`) | `onAuxClick` with `button === 1` | ✅ — this is **not** invented |
| R15 | Edge fades | MISSING | 36 px fades on whichever side hides tabs: an `edge_faded` scope on glass, painted `theme.surface` gradients on opaque | `overflow-x: auto` with hidden scrollbars and no fade | add |
| R16 | Tablist keyboard | MISSING | *(the desktop strip is mouse-driven; the tabs are `Role::Button` with aria-labels)* | Enter/Space only, no arrows | not a parity gap, but `role="tab"` without arrow keys is an a11y bug; also a `<button>` inside `role="tab"` is invalid ARIA |
| R17 | Surface kinds | WRONG VALUE | `Picker \| Files \| File(id) \| Browser(id) \| Diff(id) \| Terminal(id) \| Subagent(id)` | `"changes" \| "files" \| "terminal" \| "preview"` | web's `preview` ≈ desktop's `Browser`; `File`, `Subagent` and **multiple** `Diff`/`Terminal`/`Browser` instances are all missing |
| R18 | Surface titles | WRONG VALUE | titles are **contextual**: a diff's scope label or pinned commit subject; a file's basename; a terminal's tab title; a browser's page title; a subagent's name | fixed `"Changes"`/`"Files"`/`"Terminal"`/`"Preview"` | derive from the surface |
| R19 | Surface icons | MATCHES | `list` / `folder-with-files` / `terminal` / `globe` | `SURFACE_ICONS` uses exactly these | ✅ |

### From §5.6 (pane-adjacent routing)

| # | Item | Kind | Desktop | Web | Fix |
| --- | --- | --- | --- | --- | --- |
| N2 | `/chat/$id/changes` and `/files` as **routes** | INVENTED | Changes and Files are **pane surfaces**, never routes | separate pages, and `chatIdOf` (`app-shell.tsx:385`) matches only `^/chat/<id>/?$`, so on those routes the pane column, its tabs and the toggle all **disappear** | remove the routes; host both as pane surfaces |

---

## 5. Do not

**INVENTED — remove, do not re-add:**
- `const SURFACES_DISABLED = true` (`right-pane.tsx:39`) and its use at `:82`.
- The fixed four-tab union `RIGHT_SURFACES = ["changes","files","terminal",
  "preview"]` (`state/right-pane.ts:18`) and the `SURFACE_TITLES` /
  `SURFACE_ICONS` `Record` literals.
- `components/preview-panel.tsx` as a **standalone surface**. The desktop
  surfaces previews as the empty body of the **browser tab**, which is
  desktop-only; there is no `Preview` surface (00-index finding 5). **Ticket 04
  row 40 deletes it and asks this ticket to confirm the generalized tab model
  does not reintroduce it — confirmed: it does not.** If 04 has already landed,
  just verify the file and the `"preview"` surface kind are gone.
- The `/files` and `/chat/$chatId/changes` **routes** in `router.tsx` (gap N2).
  Both become pane surfaces. Deleting them also lets `chatIdOf` keep matching
  only `^/chat/<id>/?$` without the pane disappearing.
- Native HTML5 `draggable` on the chips (gap R13).
- `onClose` meaning "close the pane" (gap R9).

**Desktop-only — do not attempt:**
- The `Browser(u64)` surface, the `"Browser"` picker row, the `"Browser"` `+`-menu
  row, favicons in the leading slot, `browser::model::presentation`,
  `set_resize_inset`, and the `mod-t` / `mod-w` / `mod-[` / `mod-]` Browser-context
  chords. A web client cannot host arbitrary cross-origin pages in a pane.
- `.occlude()` / `.block_mouse_except_scroll()` hit-test primitives — the browser
  has no equivalent and needs none; the Windows-only `.occlude()` on the scroller
  exists solely for native caption hit-testing.
- `gpui::deferred(priority)` paint ordering — ticket 06 defines the web z-index
  ladder; use it.

**Owned by other tickets — do not build:**
- The **titlebar band** that hosts the strip, the 28 px toggle and expand
  buttons, the Escape ladder, the z-index ladder → **ticket 06**. Register
  `close_right_plus` as step 7 of that ladder; do not add a second Escape
  listener.
- The **popover lifecycle** (`open → closing → closed`, `MENU_IN` 140 ms /
  `MENU_OUT` 100 ms, `menu_row`, `popover_card`, arrow-key navigation) →
  **ticket 09**; the `+` menu must consume it, not re-implement it.
- **Changes pane body** (diff rendering, header controls, scope dropdown, ref
  selector, fold-all) → **ticket 22**. This ticket only builds the
  `surface_chrome::toolbar` row it sits in.
- **Files tree / search** → **ticket 24**; **Files preview / editor** →
  **ticket 25**; the dirty-flag source and the unsaved-changes guard live there.
- **Terminal** (the dock, its own tabs, scrollback, backoff, `Mod+J`) →
  **ticket 26**.
- **History pane** → **ticket 27**; it is a `Diff` surface whose `Changes`
  reports `is_history()`.
- **Subagent transcript body** and its jump pill instance → **ticket 19** (the
  jump-pill component itself is ticket 06's).
- `file_icons::icon` and the file-type icon manifest → **ticket 24**.
- `loaders::mini_glyph_spinner` exact geometry → **ticket 20**; use the existing
  `components/glyph-spinner.tsx` and note the substitution in the Comments.

---

## 6. Acceptance

- [ ] `SURFACES_DISABLED` no longer exists anywhere in the repo, and toggling the
      sidebar with the pane open produces no flash.
- [ ] `RightSurface` is the tagged union of §2.1 (no `Browser`); `RIGHT_SURFACES`
      and the two `Record` literals are gone.
- [ ] A fresh chat opens the pane onto the **surface picker** — a centred 280 px
      column of 44 px rows (radius 10, `1px var(--rb-border)`,
      `rgb(var(--rb-ink)/0.02)`, hover `0.05` + `--rb-border-strong`, 15 px icon,
      13 px/500 label) listing Files / Terminal / (Diffs / History when git).
- [ ] Picking a row adds that tab and activates it; **Files is single-instance**;
      opening two different files yields two tabs with **stable basename titles**;
      opening the same path twice activates the existing tab.
- [ ] The `+` button appears only when at least one tab exists, is 24 × 24 with a
      13 px glyph and a `rgb(var(--rb-wash)/0.11)` hover, and opens a **168 px**
      menu with the same rows and the press-was-open toggle.
- [ ] Chips: 112 × 24, radius 6, `padding-left 4 / padding-right 8`, `gap 3`,
      title at **11.5 px**, icon at **12** (file icons **14**, `opacity 0.78`
      when inactive), inactive icon colour `text_muted@70 %`, close glyph at
      **12**; active `wash(0.10)`, hover `wash(0.06)`.
- [ ] A dirty surface shows a 6 px `text_muted` dot and its aria-label ends with
      `", unsaved changes"`.
- [ ] Hovering a chip with a `detail` shows the tooltip after **350 ms**:
      `max-width 380; padding 6px 9px; radius 6; 1px solid var(--rb-border);
      background surface_overlay; font-size 10.5px; colour text_muted`.
- [ ] ✕ and middle-click both **close that tab**; the pane stays open and
      `resolvedActive` falls to the first remaining tab, then to the picker.
- [ ] Dragging a chip shows a custom 112 × 24 ghost (`surface_raised`,
      `1px var(--rb-border-strong)`, `opacity 0.85`), leaves an invisible spacer
      in its slot, slides siblings by ±116 px over `TAB_SLIDE` (150 ms ease-out),
      and drops at the `drop_index` under the pointer. A drag from another chat's
      strip is ignored.
- [ ] The strip fades 36 px with **quadratic** stops at whichever edge hides
      chips, gated on the 1 px dead-zone; no fade when nothing is hidden.
- [ ] `toggle()` clears `expanded` when it closes; reopening after a takeover
      close lands in normal mode.
- [ ] The right seam is mounted only when the pane is open, not expanded, and no
      glide is in flight; at a clamped edge while dragging the line is hidden;
      hitting min/max nudges **5 px** over **220 ms** with the two-phase
      smoothstep, once per held pointer, and never under reduced motion.
- [ ] `right_pane_width` persists globally through ticket 03's settings store;
      open / expanded / active / tabs stay in memory and default closed, keyed by
      chat id or `space-canvas:{space}` on the blank canvas.
- [ ] `.right-pane-inner`'s background branches on the surface treatment.
- [ ] A `Diff` surface renders the 38 px `surface_chrome::toolbar` row above its
      body, with `border-top`/`border-bottom` 1 px and an 8 px inset.
- [ ] `/files` and `/chat/$chatId/changes` are gone from `router.tsx`;
      `preview-panel.tsx` is deleted.
- [ ] Unit tests (`tests/right-pane.test.ts` unless noted):
      `session_panels_default_closed_per_chat`,
      `session_panels_flags_are_chat_scoped`,
      `session_panels_both_flags_coexist_per_chat`,
      `session_panels_update_tracks_right_surfaces`,
      `files_surface_is_single_instance_per_tab_list`,
      `file_editors_are_distinct_surface_tabs_with_stable_titles`;
      `right_pane_ceiling_preserves_the_chat_floor`,
      `right_pane_takeover_consumes_the_chat_column`,
      `right_panel_content_keeps_the_larger_width_only_during_transition`,
      `pane_resize_hitboxes_yield_the_titlebar_chrome`,
      `right_pane_takeover_control_reverses_direction`,
      `sidebar_drag_nudges_each_edge_once_until_rearmed`,
      `sidebar_drag_stays_exact_in_range_and_reduced_motion_never_nudges`,
      `right_pane_uses_the_shared_clamp_and_edge_latch`,
      `sidebar_bounce_has_rounded_out_and_return_phases` →
      `tests/layout.test.ts`.
- [ ] Screenshot pair, desktop vs web, states: (a) pane open, empty tab list —
      surface picker; (b) pane open with 5+ tabs, strip scrolled, both edge fades
      showing; (c) one tab hovered — ✕ swapped in, tooltip shown; (d) a dirty
      file tab; (e) mid drag-reorder with the ghost; (f) `+` menu open;
      (g) takeover (expanded) with no left border and no seam; (h) seam dragged
      to the min edge (line hidden, bounce settled).
- [ ] `pnpm -r build` green; `pnpm --filter @roboco/app test` green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)
