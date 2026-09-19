# Nav island, model picker boot race, changes selector, sidebar-tween text — root-cause research (2026-09-20)

Branch `web-parity/wave-2`, HEAD `32876f8f`. Read-only code analysis; the only
instrument run was the existing vitest suites plus a THROWAWAY repro test
executed from the pre-approved temp dir (`%TEMP%\opencode\s2-first-connect.test.ts`,
driven through the app package's vitest so it imports the REAL
`EngineRegistry`/`EngineClient`/`PickerCatalog` source) — no repo file was
created or modified for it. No dev server, no browser.

Scope split with the sibling file: `performance-audit.md` (same directory)
owns the **perf** side of the sidebar tween (rAF loops, `backdrop-filter`
re-filtering, layout thrash). This file owns the **geometry/behavior** side
of S1/S4 and the full behavior side of S2/S3. Cross-references, no overlap.

Vocabulary: **chat**, **space**, **engine**, **harness** (never "provider").

---

## S1 — collapsed-sidebar small-nav icons sit left of the island's center

> "the small nav when the sidebar is closed on the new chat page, currently
> the icons are misaligned, like its a bit moving to the left where its
> supposed to be centered inside the div"

### (a) Web mechanism trace

The "small nav" is the titlebar's window-control cluster — sidebar toggle +
Back/Forward — and the "div" is the frosted island behind them (ticket 34).
The island state is exactly the user's state: canvas route, sidebar
collapsed, background resolving — `islandTarget()` at
`components/titlebar.tsx:90-102`, wired in `components/app-shell.tsx:501-506`
(`sidebar.collapsed` is one of its inputs).

The cluster (`components/titlebar.tsx:228-278`):

- `.titlebar-cluster` is `position: absolute; left: var(--rb-titlebar-pad)`
  (app.css:342-352) — **no internal padding**; the 10px "pad" is the `left`
  itself. Children in DOM order: the island wrapper (absolute), a 24px
  `WindowControl` (titlebar.tsx:253), the Back/Forward group (2 × 24px on a
  2px rhythm = 50px, titlebar.tsx:254-257), and the new-session `+` wrapper
  (titlebar.tsx:266-277).
- The `+` **stays mounted at alpha 0** — the fade is `opacity`/`visibility`
  only ("The `+` stays mounted", titlebar.tsx:258-265; `.titlebar-new-session`
  is `flex: none` with `visibility: hidden` at `data-alpha="0"`,
  app.css:517-526). `visibility: hidden` **preserves layout**: the wrapper
  keeps its 24px box plus the flex row's 8px group gap
  (`--rb-titlebar-group-gap: 8px`, app.css:243).
- The island wrapper is the cluster's first child with `left: 6px; right: 0`
  (`.titlebar-island`, app.css:405-412, mirroring `shell.rs:4027-4028`).
  CSS absolute offsets are measured from the containing block's **padding
  box**; the web cluster has no padding, so the island spans
  `[cluster_left + 6, cluster_right]`.

Numbers in the S1 state (canvas → `plusAlpha = 0` via
`titlebarNewSessionAlpha(isChatRoute, paired && paneChatId !== null)`,
app-shell.tsx:488; canvas has no chat → 0):

| quantity | value |
|---|---|
| cluster width | 24 + 8 + 50 + 8 + 24 = **114px** (the hidden `+` contributes 32px) |
| visible controls span (window coords) | [10, 92] — center **51** |
| island span | [16, 124] — width 108 — center **70** |

The icons' center is **19px left of the island's center**, and the pill's
right 32px is empty frosted glass over the invisible `+` slot. That is
exactly "a bit moving to the left where it's supposed to be centered inside
the div". (`--rb-titlebar-pad` = 10 = `TITLEBAR_CLUSTER_PAD`,
state/layout.ts:274; the 24/2/8/24 rhythm is `CLUSTER_BUTTONS_WIDTH`'s
formula, state/layout.ts:280.)

The vertical half is correct and is not implicated:
`titlebarIslandVerticalGeometry` (titlebar.tsx:110-117) ports
`titlebar_island_vertical_geometry` (shell.rs:829-835) — height 28→32,
centered on the padded row's center, `top` shifted by `TITLEBAR_TOP_PAD`
because the cluster's box starts 4px down (titlebar.tsx:246).

The `rowLeft`/`phone-rowLeft` hypothesis from the ticket brief does NOT
apply: the cluster is an absolute overlay (app.css:342-352) that never reads
`--rb-titlebar-row-left`; `sidebarForGeometry` (app-shell.tsx:132-133) only
feeds the identity row and the pane width math.

### (b) Desktop reference spec

`render_titlebar_cluster` (`crates/ui/src/shell.rs:3997-4106`), Windows arm
(spacer is `None` off macOS, shell.rs:3885-3888):

| region | geometry | source |
|---|---|---|
| cluster container | `absolute().top_0().left_0().h(TITLEBAR_HEIGHT=38)`, `pt(TITLEBAR_TOP_PAD=4)`, **`px(TITLEBAR_CLUSTER_PAD=10)`** | shell.rs:4025-4034 |
| island wrapper | `absolute().left(6).right_0()`, `top(island_top)`, `h(island_height)`, `opacity(island)`; frosted panel only while `island > 0.001` | shell.rs:4035-4054 |
| toggle button | `window_control_button` 24×24, 16px glyph | shell.rs:4065-4070, 7386+ |
| nav group | `div().ml(TITLEBAR_GROUP_GAP=8)` + `gap(TITLEBAR_CONTROL_GAP=2)`, two 24px `nav_history_button`s | shell.rs:4071-4092, 230-233 |
| **the `+`** | **`.children(show_plus.then(...))` — NOT RENDERED when `plus_alpha <= 0.01`** (`show_plus = plus_alpha > 0.01`, shell.rs:4005-4006) | shell.rs:4093-4104 |
| island vertical | `titlebar_island_vertical_geometry`: height `28 + 4·progress`, center `(38+4)·0.5` — "give them 4px of air" | shell.rs:829-835 |

Absolute-inset semantics: GPUI passes `Position::Absolute` + insets straight
to taffy (`style.rs:1318-1325`), and taffy 0.12.2 measures an absolute
child's `left`/`right` from the parent's **padding box**
(`taffy-0.12.2/src/compute/flexbox.rs:2336-2347`: `offset = start +
parent border`). So on the desktop, in the island state (no `+` in the
tree):

| quantity | value |
|---|---|
| cluster border box | [0, 102] (10 pad + 24 + 8 + 50 + 10 pad) |
| cluster padding box | [10, 92] |
| island span | `[10+6, 92]` = **[16, 92]** — width 76 — center **54** |
| glyph span | toggle glyph [16,32], forward glyph [74,88] → center **52** |

The pill hugs the controls with 4px/10px side margins; the glyphs sit 2px
left of the pill's center — imperceptible. The island is *tightly wrapped*
because the cluster's shrink-to-fit width has no hidden slot in it.

### (c) Root cause

The web deliberately keeps the `+` mounted for the cross-fade
(titlebar.tsx:258-265) where the desktop conditionally renders it
(`show_plus.then(...)`, shell.rs:4093-4104). `visibility: hidden` still
occupies the flex slot, so the cluster's shrink-to-fit width carries 32px
the desktop's never has, and the island's `right: 0` anchor (app.css:409)
rides the cluster's right edge — 32px past the last visible control. The
icons end up 19px left of the pill's center instead of the desktop's 2px.

Note the two predicates are mutually exclusive by construction: the island
requires "no selected chat" (`titlebar_island_target`, shell.rs:841-852 /
titlebar.tsx:90-102) and the `+` requires a selected chat
(`titlebar_new_session_alpha`, shell.rs:4110-4115 / layout.ts:348-350). The
island is never visible while the `+` fades, so hiding the slot's geometry
cannot regress the cross-fade the mount was chosen for.

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| `+` in the tree at alpha 0 | BEHAVIOR | unmounted (`show_plus.then`, shell.rs:4093-4104) | mounted, `visibility: hidden`, occupies 24px + 8px gap (titlebar.tsx:266-277; app.css:517-526) | keep the mounted fade but stop reserving the slot: `.titlebar-new-session[data-alpha="0"] { width: 0; margin-left: 0; }` with `width`/`margin` on the resize curve, so the cluster's shrink-to-fit matches the desktop's conditional child |
| Island right anchor | WRONG | padding-box right = last control's edge; island [16,92] w76 (shell.rs:4034-4039; taffy flexbox.rs:2336-2347) | cluster border-box right, incl. the hidden slot; island [16,124] w108 (app.css:405-412; titlebar.tsx:266-277) | alternative one-liner: `.titlebar-island { right: calc(32px * (1 - var(--island-alpha, 1))) }` — or flat `right: 32px` while `data-alpha="0"` (the island and the `+` are mutually exclusive) — either lands the pill at [16,92], the desktop's exact span |
| Icon center vs pill center | SYMPTOM | 2px right bias (glyph center 52, pill center 54) | 19px left bias (51 vs 70) | resolved by either row above |

---

## S2 — model picker dead on first engine connect until refresh

> "when i first connect to the engine, the model picker just dont work at
> all, until i hit refresh the web"

### (a) Web mechanism trace (end-to-end first-connect sequence)

Boot chain, in order:

1. `fleet.ts` module scope: `EngineStore` loads localStorage synchronously
   (engine-store.ts:189-218), then `syncRegistry()` (fleet.ts:54-55) →
   `EngineRegistry.sync` → `#spawn` per engine (registry.ts:161-187).
2. `#spawn` creates the `EngineClient` + `EngineWatchCache` and **defers
   `client.connect()` behind the async IndexedDB cache seed**
   (registry.ts:277-283: `void this.#seed(entry).…finally(() =>
   client.connect())`).
3. React mounts. `EngineSessionProvider`'s effect builds one
   `EngineSession` per stored engine — `createEngineSession` wraps the
   registry's client and constructs the `PickerCatalog`, whose constructor
   subscribes `client.onStatus` (session-provider.tsx:60-87;
   engine-session.ts:31-38; picker-catalog.ts:151-168).
4. Root layout flips the app out of the blank `"loading"` gate when
   `anythingLive = engines.some(e => e.state === "connected" || e.chats.loaded
   || e.spaces.loaded)` (root-layout.tsx:47-58) — the cache seed satisfies
   it, so with a previously-paired engine the app mounts **pre-dial**; with
   a fresh pair (no cache) the gate stays blank until `"connected"`.
5. `ConversationPage`'s effect fires the one non-forced
   `loadHarnesses()` on `[session, chatId]` (chat-page.tsx:136-141).
6. `EngineClient.call` throws
   `RpcError("transport", "Engine is offline; reconnecting")` whenever the
   socket is not established (client.ts:235-242) — a synchronous rejection,
   so the slot latches `Error` the same task it was armed.
7. Ticket 38's heal lattice, all present at HEAD:
   - `"connected"` status → `#retryOfflineSlots()` re-kicks every errored,
     row-less slot; any OTHER status → the offline-only arm
     (picker-catalog.ts:158-167, 438-452; `slotNeedsRetry` 102-109; the
     literal message match `isOfflineError` 90-94).
   - Per-commit Idle cadence: `shouldReload(harnesses, false)` gate
     (composer-pickers.tsx:245-249; catalog-loading.ts:46-54).
   - Window-focus re-arm of errored, row-less slots
     (composer-pickers.tsx:257-269).
   - Card-open force: `loadHarnesses({force: true})` + `prefetchModels(true)`
     (composer-pickers.tsx:230-235).

**Verification (new evidence).** I drove the real registry/client/catalog
through the three first-connect orderings in a throwaway vitest run
(temp-dir file, real source imports): (1) mount-load latches offline, then
`"connected"` fires → heals to `loaded` rows; (2) catalog created after
`"connected"` already fired → mount load succeeds on the spot; (3) dial 1
fails (engine off), reconnect dial 2 succeeds → heals. 3/3 pass. The
landed ticket-38 fix is **sound at the data layer** for the classic boot
race — the remaining hole is not the heal itself.

What the lattice still leaves open (all file:line at HEAD):

- **Dials 2..N are silent.** `#dial` emits `"connecting"` only for dial 1
  (client.ts:309-311: `if (dial === 1) { this.#emit(...) }`); re-dials emit
  nothing until they fail (`"reconnecting"`) or succeed (`"connected"`).
  The "re-arm on the next status change of any kind" language has far fewer
  events to consume than it implies.
- **The open-force does not re-fire for a card that is already open.** The
  effect's deps are `[catalog, opened]` (composer-pickers.tsx:235): a load
  that fails *while the card is open* swaps the body to the ErrorRow with
  no scheduled retry — the cadence is Error-gated by design
  (catalog-loading.ts:50-53), the focus re-arm needs a genuine window
  blur→focus, and clicking Retry is the only in-card escape.
- **An in-flight load swallows the open-force.** `loadHarnesses` returns
  early while `#harnessesInFlight` (picker-catalog.ts:231-233), and the
  web's unary call timeout is 30s (client.ts:81, 545-560). A first-connect
  `ListHarnesses` that goes out on an established socket but hangs (a
  cold engine still booting its RPC dispatch, a proxy-stalled first
  message) leaves the chip on the glyph spinner + the card on skeleton
  rows for the full 30s with **nothing the user can press** — the ticket's
  acceptance "skeletons never sit unscheduled" holds, but "scheduled"
  includes "waiting on a 30s timeout". Only the opencode *model* ladder
  retries (picker-catalog.ts:263-304); the harness slot has none.
- **A non-offline error latched while connected never auto-rearms.**
  `isOfflineError` matches the literal pre-dial message only
  (picker-catalog.ts:90-94), so a timeout (`"Engine request timed out:
  ListHarnesses"`) or a mid-call teardown (`"Engine connection closed"`)
  heals only via `"connected"` (no further event while the engine stays
  up), a real window focus, a chatId change, or a card re-open.

Why a refresh always fixes it: a reload re-runs the whole chain with a warm
engine — the dial establishes inside the seed window or the mount load
lands post-connect — and the error-blind chip cosmetics of the pre-38 bug
are gone, so the second visit looks healthy.

### (b) Desktop reference spec

The desktop has **no pre-dial picker at all** — that is the structural
difference. Its engine target exists from app boot, so `ensure_harnesses`
never faces an offline client:

| behavior | desktop | source |
|---|---|---|
| per-render kick | `ensure_harnesses(false)` + `prefetch_models(false)` **every pickers render** — Idle-only, idempotent | pickers.rs:4164-4168 |
| ensure table | `Idle → load; Loading → never; Ready\|Error → force` (note: **Loading blocks even a forced reload**) | pickers.rs:1037-1041 |
| forced reload on open | stale-while-revalidate — Ready rows stay while fresh lands | pickers.rs:1032-1036, 1050-1053 |
| chip loading gates | Idle/Loading only, never Error; errored catalog renders the remembered/raw label | pickers.rs:4207-4221, 4186-4206 |
| no-agents | `ready().is_some_and(offered empty)` — "false while loading or failed" | pickers.rs:825-833 |
| engine-side speed | `ListHarnesses` = `descriptors()` — synchronous, "never forces a lazy resolve … never leave ListHarnesses slow" | crates/engine/src/registry.rs:336-359, 412-418 |

So on the desktop, the states the web can latch (pre-dial error, 30s hang,
mid-call teardown) do not exist, and its conservative Error rule (heals on
the next open/Retry) never strands a user.

### (c) Root cause

Ticket 38 closed the *cosmetic* half (error ≠ loading) and the *data* half
(the status heal) — verified working. The surviving first-connect deadness
lives in the re-trigger lattice, not the state machine: the only always-
available heal is the card-open force, and it (i) does not re-fire while
the card stays open, (ii) is swallowed while a hung call holds
`#harnessesInFlight` for up to 30s, and (iii) is the *sole* heal for
non-offline errors latched while connected, because dial 2..N emit no
status events (client.ts:309-311) and the focus re-arm needs a real window
blur. A first connect whose initial `ListHarnesses` is slow or torn down
mid-call therefore parks the picker in spinner/skeleton or ErrorRow with
no scheduled recovery — a refresh is the only transition that rebuilds the
catalog, which is precisely the user's report.

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| Card-open force while the card is already open | MISSING | every open forces; render loop re-evaluates per frame (pickers.rs:4164-4168, 1032-1036) | effect deps `[catalog, opened]` — no refire on slot change (composer-pickers.tsx:230-235) | add `harnesses` to the open-effect deps and re-force when the slot lands `Error` while open (or schedule one non-forced retry when an open card's slot goes Idle→Error) |
| Open-force during an in-flight call | BEHAVIOR | `Loading → false` even forced (pickers.rs:1039) — but its calls cannot hang (engine-local, registry.rs:412-418) | swallowed by `#harnessesInFlight` for up to the 30s unary timeout (picker-catalog.ts:231-233; client.ts:81, 545-560) | let `{force:true}` supersede after a grace (epoch-bump the in-flight call), or cap the harness slot's hang with the opencode ladder's retry shape (picker-catalog.ts:263-304) |
| Re-dial status events | MISSING | n/a — no dial phase | only dial 1 emits `"connecting"`; re-dials are silent until failure (client.ts:309-311) | emit `"connecting"` on every dial (or arm the offline re-arm on the reconnect timer) so "any status change" has real events to consume |
| Non-offline error while connected | BEHAVIOR | errors are rare; heal = next open/Retry (pickers.rs:1040) | only the literal offline message re-arms (picker-catalog.ts:90-94, 438-452); timeout/closed errors wait for focus/open | classify `timeout`/`closed` as retryable-on-next-status like the offline arm, or give the chip-level focus re-arm a cheap interval peer |
| Verified working (no gap) | — | — | boot-race heal 3/3 in the real-code repro (temp `s2-first-connect.test.ts`) | keep the landed lattice; do not rebuild it |

---

## S3 — the Changes surface's scope control: 3 buttons vs the desktop's selector

> "the sidebar's diff, it has 3 buttons, working tree, branch changes,
> latest turn, but in the desktop app its a selector, not 3 buttons next
> to each other"

### (a) Web mechanism trace

The three buttons are the Changes toolbar's scope chips:
`routes/changes-page.tsx:132-187` — `ChangesToolbar` maps
`DIFF_SCOPE_CHIPS = ["workingTree", "branch", "turn"]`
(lib/diff.ts:541) into a `<nav class="changes-scope">` of
`.changes-scope-chip` buttons labeled "Working tree" / "Branch changes" /
"Latest turn" (DIFF_SCOPE_LABELS, lib/diff.ts:533-538), each calling
`changesSurfaceStore.setScope` (changes-page.tsx:137-147;
changes-surface.ts:162-181). Styling: `.changes-scope` is a wrapping flex
row (app.css:12229-12235); each chip is a bordered 12px toggle
`4px 10px`, radius `--rb-radius-control`, active = selected wash
(app.css:12237-12258). The toolbar itself is the surface host's 38px row
(app.css:12214-12219).

This is a **self-documented deviation**, not an accident: the file header
says "Scope chips replace the desktop's dropdown … the documented, accepted
web deviations (research §5)" (changes-page.tsx:37-42), the CSS repeats it
(app.css:12227-12228), and ticket 22's Comments locked it in ("Scope chips
and the native `<select>` base picker stay", 22-changes-pane.md:960). The
user report is the parity objection to that lock — the desktop ships a
dropdown.

### (b) Desktop reference spec

`Changes::render_header_controls` (`crates/ui/src/changes.rs:3637-3843`) —
the scope control is ONE trigger chip + a popover menu:

| region | geometry / behavior | source |
|---|---|---|
| scope trigger | `h(CONTROL_SIZE=24)`, `px(8)`, `flex_none`, row, `gap(6)`, `rounded(CONTROL_RADIUS=6)`, `cursor_pointer`, bg `hover_blend` `wash(0.05)` → `wash(0.14)` on hover, `.occlude()` | changes.rs:3699-3715; surface_chrome.rs:8-11 |
| trigger label | `scope.label()` — text 12px / line 14, `theme.text` | changes.rs:3733-3739; labels changes.rs:862-870 |
| trigger chevron | `ALT_ARROW_DOWN` 12px, `text_muted @ 0.7` | changes.rs:3740-3744 |
| open/close | click toggles the `scope_menu` `Popup<()>` (press-was-open toggle: mousedown notes, click closes-if-was-open else opens) | changes.rs:3717-3732; open/close helpers 2818-2841 |
| menu placement | `popover::anchored_menu_below_gap("changes-scope-menu", menu, closing, 10.0)` — below the trigger, **10px gap**, mounted relative to the trigger while open (exit animates) | changes.rs:3764-3775 |
| menu card | `popover_card(theme).w(180)`, `on_mouse_down_out` → close | changes.rs:3845-3849 |
| menu rows | one per `DiffScope::ALL = [WorkingTree, Branch, LatestTurn]` — `popover::menu_row(theme, selected, id)`, **2px column gap** ("adjacent washes read as one slab — user report"), label flex_1, click → `set_scope` + close | changes.rs:3850-3868 |
| options | Working tree / Branch changes / Latest turn — labels verbatim; History is a separate surface (kept out of ALL), Commit is born-pinned and never listed | changes.rs:836-882 |
| commit-pinned arm | fixed identity chip (mono short sha + subject) replaces the dropdown entirely | changes.rs:3639-3683 |

### (c) Root cause

Not a bug introduced by drift — a sanctioned-in-writing deviation from
ticket 22 (`22-changes-pane.md:898-911, 960`) that the user now rejects on
parity grounds: the web renders a 3-way segmented control where the desktop
renders a compact dropdown selector whose trigger shows only the CURRENT
scope. Behavior differences beyond looks: the chips consume ~3× the
toolbar's leading width (three labeled buttons vs one), and the branch base
picker consequently starts further right.

### (d) Gap rows + port spec

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| Scope control form | WRONG | one trigger + 180px popover menu, 3 `menu_row`s, 2px gaps, below-trigger 10px gap (changes.rs:3699-3744, 3764-3775, 3845-3871) | 3 side-by-side toggle chips in a wrapping `<nav>` (changes-page.tsx:136-148; app.css:12229-12258) | replace the chip row with a dropdown trigger opening a `PickerCard` |
| Trigger geometry | MISSING | h24, px8, gap6, r6, wash 0.05→0.14 hover, label 12/14 + 12px chevron `text_muted@0.7` (changes.rs:3699-3744) | n/a | a `header-chip`-shaped button: 24px tall, 8px inline pad, 12px label, `altArrowDown` icon 12px muted |
| State behind it | MATCHES | `DiffScope` enum, per-tab scope, base survives branch round-trips (changes.rs:836-882; changes-surface.ts mirrors) | `DiffScope` + `changesSurfaceStore.setScope` already 1:1 (changes-surface.ts:162-181; lib/diff.ts:526-541) | no store change — the selector is a pure control swap over `setScope` |

**Port spec.** Primitive: `PickerCard` (components/ui/PickerCard.tsx) with
`placement="anchorBelow"`, `gap={10}`, `width={180}`, `role="menu"` — the
same wrapper every picker/menu surface composes (its no-flip clamp-only
positioning, `overlaySource` keyboard registry, and escape contract come
along). Body: three `MenuRowNav` rows (components/ui/MenuRows.tsx:57) with
`selected={surface.scope === option}` — the `.menu-row` selected wash is
the `popover::menu_row` port — separated by the menu list's existing 2px
rhythm. Trigger: a new 24px-tall chip (12px label = `DIFF_SCOPE_LABELS[scope]`,
`altArrowDown` 12px at `text_muted@0.7`, hover wash 0.05→0.14) — the
`CommitDiffToolbar`'s fixed chip (changes-page.tsx:90-95) stays untouched
(the desktop's commit arm is a different control by design, changes.rs:3639-3683).
`RbSelect` (components/base/select.tsx) is the alternative — it already
encodes "small fixed option sets … plain dropdowns" — but `PickerCard` +
menu rows is the closer geometry port (card width, row wash, gap, 10px
offset) and needs no new parts. Phone arm comes free either way
(PickerCard already converts to ticket 49's bottom sheet,
PickerCard.tsx:108-125).

---

## S4 — closing the sidebar with a subagent tab open shrinks text mid-animation

> "the sidebar, currently if i open a subagent tab, and i try to close the
> sidebar, it will shrink the text as its animating, in the desktop app it
> doesnt do that"

### (a) Web mechanism trace

First, what CANNOT be the culprit — the tab chips themselves:
`.right-tab` is a fixed `width: 112px; flex: none` box (app.css:676-694,
`CHIP_W = 112` ported from `shell.rs:6707`), and `.right-tab-title` inside
it truncates against that fixed 112px (app.css:775-780). The strip's width
can change without any chip or chip label ever re-measuring — they clip,
they do not shrink. The perf sibling file covers the tween's cost; this
section is about which TEXT actually re-measures.

The sidebar toggle is a **split-clock animation** — one React commit writes
endpoint values, two independent CSS transitions animate them:

1. `sidebar.collapsed` flips → `sidebarTarget(sidebar)` = 0 →
   `--rb-sidebar-now` and `sidebarForGeometry` (app-shell.tsx:124-133) jump
   to their post-collapse endpoints in the same commit (app-shell.tsx:574-599).
2. `.sidebar`'s width then CSS-transitions on the 200ms resize curve
   (app.css:1256-1274) — the column glides while `.sidebar-inner` stays
   pinned at `--rb-sidebar-content` (app.css:1276-1293), so the sidebar's
   own text does not reflow (the desktop's cached-pane trick, ported
   correctly).
3. `--rb-titlebar-row-left` is computed from the **endpoint**
   `sidebarForGeometry` (app-shell.tsx:511-517 → layout.ts:372-386) and
   jumps instantly; `.titlebar`'s `padding-left` transitions to it
   (app.css:311, 334).
4. `--rb-pane-open` / `--rb-pane-band` are likewise endpoint-computed:
   `paneOpenWidth = resolvePaneWidth({...pane, open: true}, viewport,
   sidebarForGeometry)` (app-shell.tsx:218-221) and
   `titlebarPaneBandWidth({viewport, paneWidth, rowLeft, takeover})`
   (app-shell.tsx:590-595 → layout.ts:406-417). `.titlebar-pane-band`
   transitions its width (app.css:603-610) but `.titlebar-pane-band-inner`
   — `position: absolute; right: 0` with
   `width: max(0, calc(var(--rb-pane-open) - 34px))` — has **no transition**
   and jumps to the endpoint the moment the var does (app.css:617-634).
5. The pane column's inline width `pane.open ? openWidth : 0`
   (right-pane.tsx:143-153) transitions on the same curve (app.css:1109-1122),
   and `usePaneGlide` treats a sidebar-driven `openWidth` change as a
   retarget, not a glide (right-pane.tsx:227-245: "a drag (or a window
   resize): retarget with no glide and no hold").

Because the subagent tab puts the pane's tab band in the titlebar row, the
row's in-flow children become `identity → fill(flex:1) → band → toggle`
(titlebar.tsx:283-321). The fill is `flex: 1` (basis 0, app.css:544-546) —
it cannot absorb negative space. The **identity is the only shrinkable
child**: `.titlebar-identity` is `min-width: 0; overflow: hidden`
(app.css:534-541) with a shrinkable `.identity-title` (app.css:552-560)
and a high-shrink-factor `.identity-folder` (app.css:562-571).

Mid-tween the row's demand is
`identity + 8 + fill + 8 + band(t) + 28`, while its supply is
`viewport − padding-left(t) − 6`. Both animated quantities ride the SAME
curve, so the free space moves as `slack(t) = slack₀ + (ΔP − ΔB)·f(t)` —
ΔP = the padding-left endpoint delta, ΔB = the band's endpoint delta.
With a default unclamped pane (520px, RIGHT_PANE_DEFAULT) both deltas are
balanced (ΔB = 0, ΔP = sidebar+16 → 136) and nothing shrinks. But when the
pane is **width-clamped** — `pane.width > rightPaneMaxWidth(viewport,
sidebar)` (resolvePaneWidth, state/right-pane.ts:707-717; users who read
subagent transcripts drag the pane wide) — closing the sidebar loosens the
clamp by the full sidebar width: `paneOpenWidth` grows by
`min(sidebar, …)`, so ΔB ≈ the sidebar width while ΔP ≈
`sidebar + 16 − 136`. When ΔB > ΔP the transient deficit lands **entirely
on the identity**: its title and folder truncate live, frame by frame, and
snap back when the transitions settle — exactly "it will shrink the text
as its animating", and exactly why it only happens **with a pane tab open**
(the band is the trailing demand; with the pane closed the row is never
near its constraint). The pane's own content also re-lays-out once at the
commit (the inner's width jumps with `--rb-pane-open`, app.css:626-629) —
a single retarget, not a live shrink, but part of the same split-clock
defect.

### (b) Desktop reference spec

The desktop runs the whole geometry off **one animated scalar per frame** —
there is no split clock and no endpoint jump:

| mechanism | behavior | source |
|---|---|---|
| `sidebar_now()` | `eval_tween(sidebar_tween, sidebar_target)` + edge bounce — the per-frame animated sidebar width; `motion_active` re-requests frames while any tween lives | shell.rs:3814-3818, 3777-3791 |
| `toggle_sidebar` | arms ONLY `sidebar_tween` (no right/main tween) | shell.rs:1964-1974 |
| `right_target(cx)` | recomputed per frame **against `sidebar_now()`** — the pane's clamp loosens gradually as the sidebar glides | shell.rs:1946-1962 |
| `right_now(cx)` | `eval_tween(right_tween, right_target)` — with `right_tween = None` during a sidebar toggle it equals the per-frame `right_target` | shell.rs:3820-3826 |
| pane container | outer `w(eval_tween(tween, target))` + right-anchored inner at `right_panel_content_width` — clip-don't-squeeze, per frame | shell.rs:3850-3879 |
| titlebar row inset | `content_left = (sidebar_now + SPACE_LG).max(content_start + plus_inset)` — per frame | tabs.rs:179-186 |
| pane band | `animated_width = ((right_now − pr).min(avail) − 28).max(0)`, `avail = viewport − row_left − pr − gap_budget` — per frame, same `right_now` the column reads | tabs.rs:234-253 |
| tab chips | `CHIP_W = 112.0` fixed ("uniform widths"), strip is `overflow_x_scroll` — chips clip, never re-measure | shell.rs:6704-6744 |
| identity title | `min_w_0` + `truncate` in the row — its free space is invariant mid-tween because every term derives from the same `sidebar_now(t)` (the terms cancel) | tabs.rs:299-354 |

That last line is the whole story: on the desktop, `row_left(t)`, the
band's `animated_width(t)`, and the pane column all consume the same
`sidebar_now(t)`, so the titlebar row's free space is constant through the
tween — no child ever re-truncates, and the fixed 112px chips just slide
under the band's `overflow_hidden` clip.

### (c) Root cause

The web splits the desktop's single clock into three: a JS commit that
jumps every layout var (`--rb-titlebar-row-left`, `--rb-pane-open`,
`--rb-pane-band`, `--rb-pane-now`) to post-collapse endpoints
(app-shell.tsx:574-599), and separate CSS transitions that then animate
`.titlebar`'s padding-left, the band's width, and the pane column. When the
band's endpoint delta exceeds the row-inset delta (the clamped-pane case —
exactly the "subagent tab open, pane dragged wide" setup), the row's
transient deficit is taken out of the only shrinkable child, the titlebar
identity (`.titlebar-identity`, min-width 0), whose title and folder
truncate live during the 200ms and restore at settle. The band inner's
un-transitioned width (app.css:617-634) additionally re-lays the strip's
host once at the commit. The tab chips themselves are fixed-112 in both
clients and are not the text that shrinks.

### (d) Gap rows

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|
| Tween clock | WRONG | one scalar `sidebar_now(t)` drives row inset, band, and pane per frame (shell.rs:3814-3826, 1946-1962; tabs.rs:179-186, 234-253) | endpoint JS vars + 3 independent CSS transitions; inner has none (app-shell.tsx:574-599; app.css:305-335, 603-634) | port the discipline: a JS sidebar tween (the island/hero rAF pattern, titlebar.tsx:149-204) writing `--rb-titlebar-row-left`/`--rb-pane-band`/`--rb-pane-open` per frame from one eased sidebar value — the row's free space becomes invariant like the desktop's |
| Identity stability mid-tween | MISSING | free space invariant — title never re-truncates (tabs.rs:299-354; all inputs derive from `sidebar_now`) | identity is the row's only shrinkable child; truncates live when ΔB > ΔP (app.css:534-571, 544-546; app-shell.tsx:511-595) | cheaper than the full port: freeze the identity's box during the tween (capture its width at toggle start — a `data-sidebar-tweening` attr + inline width, or `flex: none` for the 200ms window) |
| Band inner width during the tween | PARTIAL | pane content held/re-tweened by the same frame clock (shell.rs:3850-3879) | jumps at the commit with `--rb-pane-open`, no transition (app.css:617-634; app-shell.tsx:582) | give the inner the larger endpoint across the tween (`stablePanelContentWidth` for the band too, layout.ts:69-74) or a width transition matching the band's |
| Tab chip width | MATCHES | `CHIP_W = 112` fixed (shell.rs:6705-6708) | 112px fixed, `flex: none` (app.css:676-694) | none — chips clip, never shrink, in both clients |

Cross-reference: the rAF/backdrop-filter cost of any JS-tween port is
`performance-audit.md` §S1(a) (the island loop and the blur surfaces are
the same machinery this fix would extend — read its cost table before
choosing the full port over the identity freeze).

---

## Consolidated gap table

| # | symptom | item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
|---|---|---|---|---|---|---|
| 1 | S1 | `+` rendered at alpha 0 | BEHAVIOR | unmounted via `show_plus.then` (shell.rs:4093-4104) | mounted, `visibility:hidden` occupies 24px + 8px gap (titlebar.tsx:266-277; app.css:517-526) | zero the slot's width/margin at alpha 0 (resize-curve transition), or island `right: 32px·(1−alpha)` |
| 2 | S1 | island span | WRONG | [16,92] w76, pill center 54 vs glyphs 52 (shell.rs:4025-4054; taffy flexbox.rs:2336-2347) | [16,124] w108, pill center 70 vs icons 51 (app.css:342-352, 405-412; titlebar.tsx:253-277) | row 1's fix lands [16,92] exactly |
| 3 | S2 | open-force refire for an open card | MISSING | every open forces; render loop re-evaluates (pickers.rs:4164-4168, 1032-1036) | deps `[catalog, opened]` (composer-pickers.tsx:230-235) | add `harnesses` to the deps; re-force when the slot errors while open |
| 4 | S2 | open-force vs in-flight call | BEHAVIOR | `Loading → false` (pickers.rs:1039) but calls can't hang (engine registry.rs:412-418) | swallowed up to 30s (picker-catalog.ts:231-233; client.ts:81, 545-560) | let force supersede after grace, or port the opencode ladder's retry to the harness slot (picker-catalog.ts:263-304) |
| 5 | S2 | re-dial status events | MISSING | n/a — no dial phase | dial 1 only emits `"connecting"` (client.ts:309-311) | emit per dial, or re-arm on the reconnect timer |
| 6 | S2 | non-offline error while connected | BEHAVIOR | heals via next open/Retry (pickers.rs:1040) | literal-message offline arm only (picker-catalog.ts:90-94, 438-452) | treat `timeout`/`closed` as retryable-on-status; or a focus/interval re-arm peer |
| 7 | S2 | boot-race heal itself | MATCHES | — | verified 3/3 with the real client/catalog (temp repro) | keep the landed lattice |
| 8 | S3 | scope control form | WRONG | trigger + 180px popover, 3 menu rows, 2px gaps, 10px below (changes.rs:3699-3744, 3764-3775, 3845-3871) | 3 toggle chips in a wrapping nav (changes-page.tsx:136-148; app.css:12229-12258) | `PickerCard` (anchorBelow, gap 10, width 180) + `MenuRowNav` rows over the unchanged `setScope` store |
| 9 | S3 | trigger geometry | MISSING | h24/px8/gap6/r6, wash 0.05→0.14, label 12/14 + 12px chevron (changes.rs:3699-3744) | n/a (chip row instead) | new 24px trigger chip, `altArrowDown` 12px `text_muted@0.7` |
| 10 | S3 | scope state machine | MATCHES | per-tab scope, base survives (changes.rs:836-882) | 1:1 already (changes-surface.ts:162-181; lib/diff.ts:526-541) | none — pure control swap |
| 11 | S4 | tween clock | WRONG | one `sidebar_now(t)` drives all geometry per frame (shell.rs:3814-3826, 1946-1962; tabs.rs:179-253) | endpoint JS vars + 3 independent CSS transitions (app-shell.tsx:574-599; app.css:305-335, 603-634) | JS sidebar tween writing the row/band/pane vars per frame, or freeze the identity for the 200ms |
| 12 | S4 | identity truncation mid-tween | MISSING | free space invariant (tabs.rs:299-354) | identity is the only shrinkable row child; truncates when ΔB > ΔP (app.css:534-571; app-shell.tsx:511-595) | freeze its box during the tween (inline width at toggle start) |
| 13 | S4 | band inner width | PARTIAL | held/re-tweened on the frame clock (shell.rs:3850-3879) | jumps with `--rb-pane-open`, un-transitioned (app.css:617-634) | larger-endpoint hold or a matching width transition |
| 14 | S4 | tab chip width | MATCHES | 112 fixed (shell.rs:6705-6708) | 112 fixed (app.css:676-694) | none — the shrinking text is the identity (row 12), not the chips |

## Fix-ordering note

S1 and S3 are self-contained control-surface fixes (one component + a few
CSS rules each; S3 additionally needs the ticket-22 deviation note in
changes-page.tsx:37-42 rewritten, since the user report overturns the
sanction). S4's cheap arm (identity freeze + band-inner hold) is two CSS
touches; its full arm (single-clock tween) overlaps the perf file's
recommendations and should be decided together with them. S2 needs no
rebuild — the three lattice holes (rows 3-6) are additive triggers on the
landed catalog, each testable with the existing FakeClient seams
(`emitStatus`, picker-catalog.test.ts:11-50) plus one new seam for a
hanging call.
