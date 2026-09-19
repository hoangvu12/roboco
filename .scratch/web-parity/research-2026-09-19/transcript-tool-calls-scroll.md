# Transcript: tool-call groups, icons, scroll behavior — 2026-09-19

Deep read-only research at `web-parity/wave-1` HEAD `bc3a3945`. Scope: the
transcript (chat message list) — tool-call groups, icons, scroll/stick
behavior. Symptoms S1–S5 come from the user's report; every claim below
carries a `file:line` verified by reading the file at this HEAD.

Prior art read first: `TICKET-TEMPLATE.md`;
`research-2026-09-17/round2-chat-stream.md`; tickets
`18-transcript-rows.md`, `19-tool-groups.md`, `20-rail-badges-loaders.md`,
`21-markdown-parity.md` (all Status: done, with landed Comments).

Shorthand: web files are under `web/packages/app/src/`; `app.css` =
`web/packages/app/src/styles/app.css`. Desktop = `crates/ui/src/`.

---

## S1 — no space between the tool icon and the label ("IconGlob web/…")

User: *"the tool call UI, currently the icon and the text dont have any space
between, for example 'Icon Glob web/...' (desktop) vs 'IconGlob web/...'
(web)"*.

### (a) Web mechanism trace

- One ordinary tool chip row is `ToolChipRow` → `<div class="tool-chip">`
  containing, in order: `ActivityRail` (a 48px-wide gutter column) and the
  card `div.tool-chip-card` (tool-group.tsx:404-431 for the detail-less
  chip, :447-486 for the expandable card).
- `ActivityRail` renders the tool glyph absolutely at
  `left: ACTIVITY_ICON_LEFT (32)` with `size ACTIVITY_ICON_SIZE (16)`
  (activity-rail.tsx:60-68) — the glyph occupies x 32→48, i.e. it ends
  flush at the gutter's right edge (gutter width 48, activity-rail.tsx:54
  + app.css:8743-8748).
- The card's inline style sets ONLY `height`, `marginTop`,
  `marginBottom` (+ the reveal lift) — **no `marginLeft`**:
  - detail-less card: tool-group.tsx:417-424
    (`marginTop/marginBottom: (baseRowHeight - CHIP_CARD_HEIGHT)/2`);
  - expandable card: tool-group.tsx:460-467 (same, no `marginLeft`).
  `.tool-chip-card` in CSS has no margin either (app.css:8534-8541).
- Result: the label column (`.tool-chip-head` → `.tool-chip-label`,
  tool-group.tsx:570, app.css:8586-8592) starts at x=48 — **0px gap**
  between the rail icon (ends at 48) and the text. The desktop reads
  "Glob web/…" with a visible break; the web reads "Glob web/…" glued.
- The constant exists and is imported but never used:
  `ACTIVITY_TEXT_GAP` is imported at tool-group.tsx:41 and appears
  nowhere else in the file (grep: only :41 and the SubagentChip's
  `marginLeft: rail ? 12 : undefined` at :633). The spawn chip DID keep
  its margin; the two ordinary chip shapes lost it.

### (b) Desktop reference (exact values)

| Item | Value | Source |
| --- | --- | --- |
| `ACTIVITY_GUTTER_WIDTH` | 48.0 | transcript.rs:110 |
| `ACTIVITY_TEXT_GAP` (card's left margin off the rail) | **8.0** | transcript.rs:111 |
| `ACTIVITY_TRUNK_X` | 12.5 | transcript.rs:112 |
| `ACTIVITY_BEND_RADIUS` | 6.0 | transcript.rs:113 |
| `ACTIVITY_BRANCH_END_X` | 28.0 | transcript.rs:114 |
| `ACTIVITY_ICON_LEFT` | 32.0 | transcript.rs:115 |
| `ACTIVITY_ICON_SIZE` | 16.0 | transcript.rs:116 |
| Rail row height (`TOOL_TREE_ROW_HEIGHT`) | 32 | transcript.rs:122 |
| Card height (`CHIP_CARD_HEIGHT`) | 30 | transcript.rs:101 |

- The gutter's own doc comment reserves "a 4px break before the icon, and
  an **8px icon-to-text gap**" (transcript.rs:108-109).
- `tool_chip` applies `.when(rail, |el| el.ml(px(ACTIVITY_TEXT_GAP)))` on
  the card (transcript.rs:7363); the expandable chip card applies
  `.when(collapses, |el| el.ml(px(ACTIVITY_TEXT_GAP)))`
  (transcript.rs:6233). So on the desktop the card — and therefore the
  label text — starts at x = 48 + 8 = 56, and the icon-to-label gap is
  exactly **8px**.
- Ordinary (rail) rows place NO icon inside the header row — the icon
  tile exists only for spawn cards (transcript.rs:6924-6940, per ticket
  19 §2.1.8); the rail glyph is the only icon. The web matches this
  (tool-group.tsx:565-569 renders the tile only when `!activity`).
- The spawn chip guide line uses `ml(12)` (transcript.rs:7409-7417 per
  ticket 19 §2.3) — the web kept that one (tool-group.tsx:633).

### (c) Root cause

The `ml(ACTIVITY_TEXT_GAP = 8)` from `tool_chip`/the expandable card
(transcript.rs:7363, :6233) was never ported: both web card variants omit
`marginLeft`, so the label column starts at the gutter's right edge and
the glyph touches the text. `ACTIVITY_TEXT_GAP` sits unused in the import
list (tool-group.tsx:41) — the port simply dropped the one line.

### (d) Gap rows

| item | kind | desktop value | web value | fix sketch |
| --- | --- | --- | --- | --- |
| Icon→label gap on rail chip rows | MISSING | card `ml(8)` (`ACTIVITY_TEXT_GAP`), label starts at x=56 (transcript.rs:111, :7363) | card `marginLeft` absent; label starts at x=48, 0px gap (tool-group.tsx:417-424, :460-467; app.css:8534-8541) | add `marginLeft: ACTIVITY_TEXT_GAP` (or CSS `margin-left: 8px` on `.tool-chip`'s card when the rail renders) to BOTH card variants in tool-group.tsx |
| Icon→label gap on expandable cards | MISSING | `ml(8)` when `collapses` (transcript.rs:6233) | same omission (tool-group.tsx:460-467) | same one-liner |
| Unused import | code smell | n/a | `ACTIVITY_TEXT_GAP` imported, never used (tool-group.tsx:41) | use it (above) or remove |

---

## S2 — transcript jumps; collapsed tool groups spontaneously ALL expand, repeatedly

User: *"the behaviour on the web, currently for some reason its jumping alot,
and like, sometime the old grouped tool calls get opened, like all of them,
and it keeps jumping back and forth, the desktop app doesnt do that"*.

### (a) Web mechanism trace

The expansion state store: `ToolGroupMotionStore` — per-surface instance
(transcript.tsx:319, `useMemo(() => new ToolGroupMotionStore(), [store])`),
holding `#reveals`, `#folds`, `#detailFolds`, `#counts` (tool-motion.ts:433-441).
Open/closed resolution per group (tool-group.tsx:119-127):

```
arrivalPending      = !reduced && starts.some(start => now - start < 480ms)   // future starts count
effectiveAutoOpen   = autoOpen || arrivalPending
open                = !collapses || (fold?.open ?? effectiveAutoOpen)
```

Reveal starts are assigned in `sync(rows, baseline)`
(tool-motion.ts:534-590): for every live tool-group row,
`oldCount = counts.get(row.id) ?? 0`; for `toolIx` in
`oldCount..tools.length` a FUTURE start `now + 90ms + arrivalIx·65ms` is
written (tool-motion.ts:571-576). Rows whose start is younger than
`TOOL_CONNECTOR_REVEAL_MS` (480) drive `arrivalPending` → the group
auto-OPENS even when `autoOpen` is false (tool-group.tsx:125-127), the
group body height animates from 0 with the reveal progress
(tool-group.tsx:194-200: `revealedHeight = 2 + Σ rowHeight·revealProgress`),
and when the pending window lapses the rendered-open flip seeds a 140ms
close tween via `noteRendered` (tool-group.tsx:220-222 →
tool-motion.ts:513-526).

**The re-trigger chain (the bug):**

1. `TranscriptStore.resubscribe()` — fired on a `TranscriptDesync`
   (transcript-store.ts:466-474, thrown by `applyTranscriptFrame` on
   anchor/part/count mismatches, lib/transcript.ts:2176-2210) — or on a
   connection-generation swap (transcript-store.ts:452-460, every
   `#established` re-subscribe after a WebSocket reconnect) — **empties
   the entries**: `#entries = EMPTY_ENTRIES; #loaded = false;
   #replay = "pending"` (transcript-store.ts:395-408, :452-460) and
   commits.
2. `rows` recomputes to `[]` (transcript.tsx:265-290) and the reveal-sync
   effect fires `toolMotion.sync([], false)` (transcript.tsx:322-329). With
   no live rows, the cleanup deletes **every reveal and every count**
   (tool-motion.ts:579-588).
3. The engine's doc stream always re-sends a full reset on re-subscribe
   (the store's own comment, transcript-store.ts:453-455); when it lands,
   rows are rebuilt with the SAME row ids (`{entry.id}#g{ix}`,
   lib/transcript.ts:1541-1552) and the effect runs
   `toolMotion.sync(rows, false)` — **baseline `false`**, because
   `revealBaselineRef.current` was latched `true` on the first populated
   frame and is never reset (transcript.tsx:321-328).
4. With the counts wiped, every group is `isNewGroup`
   (tool-motion.ts:558) and `oldCount = 0` (tool-motion.ts:557) — EVERY
   tool in EVERY old group gets a fresh future start → `arrivalPending`
   true → **all collapsed groups auto-expand** with the 90/65ms stagger,
   then collapse again when the 480ms window lapses. Every reconnect or
   desync repeats the cycle: "keeps jumping back and forth".

**The jump amplifier (why it "jumps a lot"):**

- While the replay runs, each group row's rendered height oscillates
  26px → full body (reveal progress multiplies row heights,
  tool-group.tsx:181-200) → measured heights change → the virtualizer's
  prefix sums move (transcript.tsx:657-674).
- The scroller compensates on every commit: while unpinned, the captured
  escape anchor is re-asserted with `stick.writePreserving(target)`
  (transcript.tsx:1030-1061); the pin re-engages when the reservation
  fills (`#stepOwnTurn` → `engagePin`, stick-controller.ts:611-619) and
  the spring glides to the bottom (stick-controller.ts:510-575) — the
  viewport shuttles between "hold anchor" and "chase tail".
- First-frame height estimates are wrong for OPEN groups:
  `estimateRowHeight` returns `TOOL_GROUP_HEADER_HEIGHT` (26) for any
  collapsible group regardless of open state (transcript.tsx:1378-1384),
  so the layout jumps again when the real (open) height measures.
- The scroller stays mounted through the empty window
  (`if (loaded && rows.length === 0 …) return null` — but `loaded` is
  false during resubscribe, transcript.tsx:1186-1188), so the surface
  never resets its stick state either.

### (b) Desktop reference (exact behavior)

| Item | Value | Source |
| --- | --- | --- |
| Reveal-baseline condition | `replay_baseline = veil_attach_pending && !entries_empty` — set ONLY on chat attach (`veil_attach_pending` at :3915/:3954, consumed at :4063) | transcript.rs:4063, :4147-4154 |
| Baseline effect | clears `tool_group_reveals`, strips `toggled_at`/`disclosure_at` from folds (keeps user pins) | transcript.rs:4064-4072 |
| Arrival counting | `old_count` read from the PREVIOUS LIVE ROWS (`previous_tool_counts` off `self.rows`), never from a wiped map; baseline frames use `tools.len()` | transcript.rs:4073-4097 |
| New-group stagger | first-row delay 90ms, per-arrival 65ms; starts are `now + delay` (future) | transcript.rs:135-136, :4106-4113 |
| `arrival_pending` | `!reduce_motion && starts.any(checked_duration_since(start) < 480ms)` | transcript.rs:5849-5857 |
| Auto-open resolution | `effective_auto_open = auto_open \|\| arrival_pending`; `open = !collapses \|\| fold.open.unwrap_or(effective_auto_open)` | transcript.rs:5858-5859 |
| rendered-open flip seeding | `fold.from = rendered_height; toggled_at = disclosure_at = now` only when the rendered open-state flips | transcript.rs:5862-5870 |
| Rows are cleared ONLY on chat switch | `select_chat` branch clears rows/folds/reveals/caches; a mid-session stream reset never observes empty rows | transcript.rs:3974-3989 |
| Desync analogue | the desktop re-derives rows from `state.transcript` on every state notification — the entry list is replaced atomically by the doc host, never emptied first | transcript.rs:4032-4057 |
| Pin/spring during stream | `handle_scroll` fires only from wheel/touch; content growth never re-enters it | transcript.rs:3117-3135 (comment), :3182-3197 |
| In-place row change | `diff_rows` equal-length change → `remeasure_items` (keeps heights + anchor); else `splice` | transcript.rs:4179-4218 |
| Live-follow while pinned | `should_anchor_live_stream(pinned, distance, streaming)` → `scroll_to_end` + spring reset | transcript.rs:4175-4176, :4251-4258 |

Desktop tests that pin exactly this behavior (all in transcript.rs
`mod tests`):

- `tool_groups_stay_closed_on_populated_chat_attach` (:7851) — repeated
  attach/replay of a settled chat must render the group CLOSED with
  `rendered_open == Some(false)`, `rendered_height == 0.0`,
  `header_started_at == None`, all `starts` `None`.
- `tool_groups_stay_closed_after_rapid_new_chat_navigation` (:7862).
- `tool_group_navigation_keeps_user_pins_and_new_arrivals` (:7894) —
  user pins survive replay; only genuinely NEW streamed arrivals animate.
- `jump_button_stays_available_when_scrolling_down_until_near_bottom`
  (:7748); `restick_is_direction_aware` (:8270); the spring suite
  (:8126-8268).

### (c) Root cause

Two web-only defects compose:

1. **The empty-rows window.** `TranscriptStore.resubscribe()`/generation
   swap clears the entries (transcript-store.ts:395-408, :452-460), so
   the surface observes `rows = []` and `ToolGroupMotionStore.sync([],
   false)` wipes the reveal/count maps (tool-motion.ts:579-588). The
   desktop never observes empty rows mid-session — its rows re-derive
   atomically from the doc state (transcript.rs:4032-4057) and the
   reveal counts come from the live rows (transcript.rs:4073-4095).
2. **The baseline never re-arms.** `revealBaselineRef`
   (transcript.tsx:321-328) is latched once per surface mount and never
   reset when `snapshot.replay` returns to `"pending"`
   (transcript-store.ts:405, :459), so the post-resubscribe reset frame
   is treated as NEW arrivals instead of replayed history — the direct
   inverse of the desktop's `veil_attach_pending` gate
   (transcript.rs:4063, :4147-4154).

Trigger surface: any `TranscriptDesync` (upsert anchor missing, append
part/length mismatch, count mismatch — lib/transcript.ts:2176-2210), any
WebSocket reconnect (watch re-subscription bumps the generation,
transcript-store.ts:452-460), and any engine restart. All of them walk
the same empty-window path; repeats produce the back-and-forth.

### (d) Gap rows

| item | kind | desktop value | web value | fix sketch |
| --- | --- | --- | --- | --- |
| Mid-session rows never empty | WRONG BEHAVIOR | rows re-derived atomically; empty only on chat switch (transcript.rs:3974-3989, :4032-4057) | resubscribe/generation swap sets entries to `[]` first (transcript-store.ts:395-408, :452-460) | keep the old entries through the resubscribe (the reset frame replaces wholesale via `preserveIdentity`, lib/transcript.ts:2149-2150) — or skip `toolMotion.sync` while `!loaded && rows.length === 0` |
| Replay baseline re-arms per attach | MISSING | `veil_attach_pending` set on attach, consumed by the first populated frame (transcript.rs:3915, :4063, :4147-4154) | `revealBaselineRef` latched once per surface, never reset (transcript.tsx:321-328) | reset `revealBaselineRef.current = false` when `snapshot.replay === "pending"` after it was set, so the next populated frame runs `sync(rows, true)` |
| Reveal counts survive stream resets | WRONG BEHAVIOR | `previous_tool_counts` from live rows (transcript.rs:4073-4080) | counts deleted whenever rows momentarily vanish (tool-motion.ts:579-588) | covered by the two rows above; optionally key the cleanup on row-id liveness across TWO consecutive syncs |
| Open-group height estimate | WRONG VALUE | analytic heights (rows 32, header 26, `detail_height` sums — no estimation) | `estimateRowHeight` returns 26 for every collapsible group even while auto-open (transcript.tsx:1378-1384) | estimate `26 + chipsHeight + Σ detailHeight` when the group will render open (fold/autoOpen), or measure eagerly on mount |
| Jump visibility while pinned | MISSING GATE | `show = jump_visibility(...) && !this.pinned` (transcript.rs:3198) | `#setJumpShown(jumpVisibility(...))` with no pinned gate (stick-controller.ts:480) | add `&& !this.#pinned` in the non-own-turn branch |

---

## S3 — "the folder icon, language icon dont seem to work on the web UI"

User quote. Interpretation: the polychrome file-type icons (folders in the
files tree; per-language file icons) fail to render.

### (a) Web mechanism trace

- Resolution is a faithful port of `file_icons.rs`: exact lowercased
  basename → longest compound extension → language hint → MIME hint →
  generic (lib/file-icons.ts:145-167); directories by exact name else the
  generic folder (lib/file-icons.ts:175-179); the `less`/`yml` aliases
  (lib/file-icons.ts:90-98); `wellBg` (lib/file-icons.ts:342-347). The
  manifest is generated verbatim from the desktop's
  `crates/ui/src/file-icons.json`
  (web/packages/icons/scripts/generate-file-icons.mjs:93-134) and the SVG
  bundle is copied into `web/packages/app/public/file-icons/**` (354
  icons + a baked `dark/` mirror — verified present: 249 `files/*.svg` +
  105 `folders/*.svg`, both light and dark).
- `FileIcon` renders `<img src={resolveFileIcon(...)} …>`
  (components/files/file-icon.tsx:32-48).
- **The bug: the src is a RELATIVE URL.** `ASSET_PREFIX = "file-icons/"`
  (lib/file-icons.ts:23) and `fileIconAssetPath` returns
  `"file-icons/" + ("dark/") + asset` (lib/file-icons.ts:135-138). There
  is no leading `/`.
- Chats navigate to `/chat/$chatId` (router.tsx:28;
  session-provider.tsx:171; chat-page.tsx:678). At a document URL
  `https://host/chat/abc`, the relative reference
  `file-icons/files/rust.svg` resolves against the path's directory
  (`/chat/`) → `https://host/chat/file-icons/files/rust.svg`.
- The engine's HTTP listener serves the embedded bundle only at exact
  root paths; the SPA fallback applies ONLY to extension-less paths
  (`!path.contains('.')`), so `/chat/file-icons/…` falls through the
  static lookup (listener.rs:154-173, :371-389) into the credential gate
  → 401/404. **Every `<img>` 404s → empty boxes where the folder and
  language icons should be.** At `/` (index) and `/settings` the relative
  path happens to resolve to the root and the icons load — which is why
  smoke captures at the index route looked fine (ticket 24's verification
  only confirmed the resolver strings: "icons resolve live
  (`dark/files/react-test.svg`)", issues/24-files-tree-search.md:750).
- Affected call sites (all render inside `/chat/$chatId`): the files tree
  rows (file-tree-panel.tsx:342, drag ghost :430, search rows :638), the
  composer mention popup (composer/mention-popup.tsx:173), the tool
  chips' file badge (tool-group.tsx:608) and stats rows
  (tool-group.tsx:737), the file breadcrumb (file-viewer.tsx:205), the
  markdown sole-file-link badge (markdown.tsx:500).
- Second, smaller finding: the Changes pane's file headers still use the
  invented placeholder glyph — `FileGlyph` renders the monochrome
  `document` control icon for every file and ignores the path
  (diff-view.tsx:572-581, "ticket 24's port … replaces this single seam
  when it lands; until then one generic document glyph stands in"). The
  desktop renders the real per-file icon there.

### (b) Desktop reference

| Item | Value | Source |
| --- | --- | --- |
| Asset namespace | `ASSET_PREFIX = "file-icons/"` registered with gpui's composite AssetSource — an ABSOLUTE virtual path, immune to any URL shape | file_icons.rs:18, :56-82 |
| Dark variant | `file-icons/dark/<asset>` produced by the 7-pair accent-lift table at load time | file_icons.rs:20-38, :64-71 |
| Rendering | `icon(identity, appearance) -> gpui::Img` — the polychrome artwork rasterizes with authored fills | file_icons.rs:197-205 |
| Resolution order | exact basename (case-insensitive) → longest compound extension → language hint → MIME hint → generic file/folder | file_icons.rs:246-268, :237-244 |
| Generic fallbacks | manifest `file` else `files/document.svg`; manifest `folder` else `folders/folder.svg` | file_icons.rs:270-276, :237-244 |
| Mapping table source | `crates/ui/src/file-icons.json` — 350 iconDefinitions, 106 languageIds (e.g. `typescript→ts`, `rust→rust`, `shellscript→shell`, `src→folder-orange-code`), verified identical to the web's generated manifest | file-icons.json; generate-file-icons.mjs:93-134 |
| Bundle floor | ≥350 embedded SVGs, every reference resolvable, every asset has viewBox/dimensions | file_icons.rs:459-512 |

Desktop tests: `exact_names_are_case_insensitive_and_ignore_parent_dots`
(:373), `compound_extensions_win_longest_first` (:393),
`folders_resolve_name_and_expansion_state` (:409 — `src` →
`folder-orange-code`, expansion ignored),
`appearance_independence_and_hints_are_supported` (:425 — language hint
`untitled`+Rust → `files/rust.svg`),
`symlinks_and_unknown_files_fall_back_cleanly` (:441),
`every_manifest_reference_is_embedded_and_every_asset_is_svg` (:460),
`complete_theme_bundle_is_registered` (:493).

### (c) Root cause

The web kept the desktop's `ASSET_PREFIX` STRING but the desktop's is a
gpui-absolute asset namespace (file_icons.rs:18, :59-73) while the web's
is a document-relative URL (lib/file-icons.ts:23, :135-138). On the
two-segment route `/chat/$chatId` (router.tsx:28) every file-icon image
resolves one directory short of the bundle root and 404s
(listener.rs:154-173). The resolver logic itself is correct — the icons
"don't work" because they never load.

### (d) Gap rows

| item | kind | desktop value | web value | fix sketch |
| --- | --- | --- | --- | --- |
| Icon asset URL | WRONG VALUE | absolute asset namespace `file-icons/…` resolved by the AssetSource (file_icons.rs:18, :59-73) | relative `file-icons/…` — breaks on `/chat/$chatId` (lib/file-icons.ts:23, :135-138; file-icon.tsx:37-47) | `ASSET_PREFIX = "/file-icons/"` (or `` `${import.meta.env.BASE_URL}file-icons/` ``) |
| Changes pane file glyph | INVENTED | per-file polychrome icon (`file_icons::icon`) on file headers | monochrome `document` placeholder, path ignored (diff-view.tsx:572-581) | replace `FileGlyph` with `FileIcon kind="file"` |
| Verification blind spot | process | — | ticket 24 verified resolver STRINGS at the index route (issues/24:750), never the loaded `<img>` on the chat route | acceptance step: at `/chat/<id>`, assert `img.files-row-icon` naturalWidth > 0 |

---

## S4 — scroll-to-bottom pill sits too low, "a bit overlapping the composer"

User: *"scroll to bottom need higher position, currently its a bit
overlapping the composer"*.

### (a) Web mechanism trace

- Visibility: `StickController.#setJumpShown` driven by
  `jumpVisibility(wasShown, distance)` — show beyond 320px, keep until
  2px (stick-spring.ts:27-37); the surface publishes it up
  (transcript.tsx:1009-1020) and the chat page renders
  `<JumpPillAnchor>` inside `#persistent-composer` when a chat is
  selected (chat-page.tsx:818, :846-861).
- Geometry: `.jump-pill-anchor { position: absolute; top: -36px; left: 0;
  right: 10px; display: flex; justify-content: center; }`
  (app.css:7446-7454) — a child of `.persistent-composer`
  (`position: relative`, app.css:2480-2485), which wraps `<Composer/>`
  (chat-page.tsx:773-819). The pill is `height: 30px`,
  `border-radius: 15px`, 1px border, `backdrop-filter: blur(16px)`,
  `box-shadow: var(--rb-shadow-popover)`, entrance
  `rb-dialog-in` 180ms (app.css:7462-7476, :7515-7524); inner row gap 6,
  `padding-left: 11px; padding-right: 13px`, 13px `↓` + 13px
  "Scroll to bottom" (app.css:7489-7512; transcript.tsx:2000-2011).
  The anchor rides the composer wrapper's dock transform
  (chat-page.tsx:507-524 writes it on the same element).
- Numbers vs desktop: **identical** — top −36 with a 30px pill leaves a
  6px gap above the composer card, exactly the desktop's intent (below).
  What differs is the shadow: the web pill uses the popover token
  `--rb-shadow-popover: 0 8px 30px rgb(0 0 0 / 0.35)` (app.css:4296) —
  an 8px downward offset with a 30px blur — which paints a heavy wash
  over the composer's top edge in the 6px gap and reads as "a bit
  overlapping". The desktop pill takes `shadow_md()`
  (shell.rs:6217 — a subtle md elevation) and composites as a separate
  frost scene layer (frosted(15, 16) OUTSIDE the entrance animation,
  shell.rs:6249-6254). The entrance also starts 2px lower
  (`rb-dialog-in` from `translate: 0 2px`, app.css:7515-7524 — the
  desktop's `DIALOG_IN` does the same 2px, so that part matches).

### (b) Desktop reference (exact values)

**Primary instance** (`render_jump_to_bottom`, shell.rs:6161-6179):
doc comment: *"horizontally centered over the transcript column and
floating six pixels above the composer"* (:6158-6160).

| Property | Value | Source |
| --- | --- | --- |
| anchor | `absolute; top(-36.0); left(0); right(10.0); flex; justify_center` | shell.rs:6166-6175 |
| mount | child of `#persistent-composer` (relative), sharing the composer's dock transform, painted after the composer | shell.rs:6105-6122, :6166-6170 |
| pill height | 30 | shell.rs:6213 |
| radius | `rounded_full` (frost radius 15) | shell.rs:6214, :6254 |
| border | 1px `theme.border` | shell.rs:6215-6216 |
| shadow | `shadow_md()` | shell.rs:6217 |
| background | glass → `theme.glass_overlay()`; opaque → `hover_blend(surface_raised, surface_raised_hover)` | shell.rs:6201-6205 |
| hover wash | inner full-height layer, glass only | shell.rs:6224-6235 |
| inner layout | `h_full; rounded_full; gap 6; pl 11; pr 13` | shell.rs:6227-6234 |
| glyph / label | 13px `theme.text_muted` "↓" / 13px `theme.text` "Scroll to bottom" | shell.rs:6236-6247 |
| entrance | `DIALOG_IN` 180ms EASE — opacity 0→1, top 2px→0 | shell.rs:6254 (ticket 06 §2.9 motion table) |
| show threshold | `SCROLL_BUTTON_THRESHOLD_PX` 320; hysteresis keep until `AT_BOTTOM_PX` 2 | transcript.rs:72, :74-83 |
| hidden while | `jump_button_shown()` = shown && !pinned (own-turn held also suppresses) | transcript.rs:3743-3745, :3198, :3174-3175 |
| subagent pane instance | `absolute; bottom(16); left(0); right(0); flex; justify_center`, same pill, keys `subagent-jump-to-bottom/-pill` | shell.rs:6519-6536 |

So the desktop's pill bottom edge sits 6px above `#composer-surface`'s
top — same pixels as the web. The perceived overlap is not a geometry
divergence; it is the shadow/frost weight (and any fix that moves the
pill higher than −36 would diverge from the desktop on purpose).

### (c) Root cause

Geometry parity holds (top −36 / h 30 / right 10 / centered on both).
The visible difference is the elevation treatment: the web pill reuses
the popover shadow token `0 8px 30px rgb(0 0 0 / 0.35)`
(app.css:4296, applied at :7472) where the desktop uses `shadow_md()`
(shell.rs:6217) — the web shadow's 8px offset + 30px blur extends ~38px
below the pill, over the composer's frosted top edge, in the 6px gap.
Secondarily, the web `#onScroll` omits the desktop's `&& !pinned` gate
(stick-controller.ts:480 vs transcript.rs:3198), which can flash the
pill during spring settle.

### (d) Gap rows

| item | kind | desktop value | web value | fix sketch |
| --- | --- | --- | --- | --- |
| Pill elevation | WRONG VALUE | `shadow_md()` (shell.rs:6217) — subtle md elevation | `--rb-shadow-popover: 0 8px 30px rgb(0 0 0 / 0.35)` (app.css:7472, token :4296) | calibrate a `--rb-shadow-md` token (or inline a small y/blur) for `.jump-pill`; verify gpui `shadow_md` numbers in the pinned zui fork |
| Pinned gate on visibility | MISSING | `&& !this.pinned` (transcript.rs:3198) | no gate (stick-controller.ts:480) | add the gate in the non-own-turn branch |
| Geometry (anchor/pill/entrance/texts) | MATCHES | top −36, h 30, right 10, gap 6, DIALOG_IN 180ms, "↓"/"Scroll to bottom" 13px (shell.rs:6166-6254) | identical (app.css:7446-7524; transcript.tsx:2000-2011) | none — if the product wants more clearance, change `top: -36px` deliberately on BOTH clients, not as a "parity fix" |
| Subagent pane pill | (ticket 07/19 wiring) | `bottom(16); left/right 0`, centered (shell.rs:6519-6536) | `JumpPill` is reusable but the subagent surface has no second instance wired | follow-up: mount it in the subagent pane host |

---

## S5 — no "big empty space" on send; the desktop's chat scroll/stick model

User: *"it dont seem to create a big new empty space whenever we send new
messages, do deep research into how the behaviour of the chat overall, what
it does"* — the desktop reserves a large blank region below a just-sent
prompt; the web does not appear to.

### (b-first) The DESKTOP model (what the web must reproduce)

**Send-time trigger.** Every send (not just the first) glides the prompt
to the viewport top and reserves the reply's space below it
(shell.rs:1287-1288). Wiring: the composer emits `ComposerEvent::Sent
{chat_id, message_id}` and the shell calls
`transcript.on_own_send(chat_id, message_id)` (shell.rs:1296-1303);
queued sends call `on_own_queued_send`, which registers inertly and
promotes only when the real bubble exists (transcript.rs:3356-3395,
`PendingQueuedTurns` :2302-2344, cap 256).

**`on_own_send` (transcript.rs:3318-3355).** Cancels the collapse-scroll
and pending user hold, discards the pending viewport, unpins, hides the
jump button, resets the spring (`spring_last_tick/settled_at/kick`,
`scroll_anim`), calls `materialize_scroll_anchor()` (pins the glued
offset to a concrete visible item so the pad reads as scrollable
distance for the glide, :3397-3433), installs `OwnTurnAnchor {
held: true, positioned: false, seen_prompt: prompt_ix.is_some() }`
(:3344-3350), sets `own_turn_kick`, and `remeasure_last_row()`.

**The reservation — THE "big empty space".**
`update_runway_minimum` runs before every list layout
(transcript.rs:3483-3513): while an anchor row exists it calls
`list.set_tail_reservation(Some((anchor_ix, px(own_send_inset(anchor_ix)
− OWN_SEND_SCROLL_SLACK_PX − expansion))))` — the LAST row gets a
minimum height such that the anchor sits `inset` below the viewport top
when scrolled to the end; `expansion` adds the anchor row's live
Show-more fold tween (:3490-3504). The space is plain scrollable
whitespace (never painted chrome), consumed by streaming content and
the working trailer in the same layout pass. Constants:

| Const | Value | Source |
| --- | --- | --- |
| `OWN_SEND_TOP_INSET_PX` | `TITLEBAR_HEIGHT + 10` = **48** (row 0 uses **0** — its own 64px gap carries the chrome; adding both parked a first prompt ~66px low, user report) | transcript.rs:205, :3435-3445 |
| `OWN_SEND_SCROLL_SLACK_PX` | 2.0 (keeps gpui out of its shorter-than-viewport regime; "below perception") | transcript.rs:213 |
| `OWN_SEND_GLIDE_RETAIN` | 0.85 per 60fps frame (~90% in ~230ms, ease-out) | transcript.rs:216 |
| `OWN_SEND_GLIDE_SNAP_PX` | 1.0 | transcript.rs:218 |
| `GLIDE_MAX_VIEWPORTS` | 2.5 (entry-glide teleport cap) | proto/motion.rs (re-export, transcript.rs:63-67) |
| last row bottom pad | `bottom_clearance + TRANSCRIPT_FADE_BAND(24) + 8` | transcript.rs:5368-5373 |
| working trailer mount | under the LAST row's content, above its clearance pad | transcript.rs:5374-5378 |

**`step_own_turn` (transcript.rs:3529-3734).** Per frame: refresh the
escape baseline (:3540); if the anchor row is missing, wait one
notification unless `seen_prompt` (then retire, :592-601 web analogue);
if `list.tail_reservation_filled()` — the reply's natural content has
filled the reservation — retire the runway and `engage_pin` if it was
held/pinned/at-bottom (:3563-3577); entry glide eases
`err·(1 − 0.85^frames)` toward `anchor.top − inset`, capped at 2.5
viewports, landing by item anchor `{item_ix: anchor_ix, offset: -inset}`
(:3655-3731), never gliding past the prompt
(`own_turn_glide_crossed`, :222-225); once `positioned`, the hold
re-asserts the prompt's absolute position after every layout —
ONE-SIDED (only `err > 0.5` or `err < -(slack+2)`), eased, snapping
within 1px (:3587-3653).

**Wheel/escape (handle_scroll, transcript.rs:3117-3206).** Synchronous
cancels; input releases the own-turn hold (the RESERVATION stays as
plain scrollable space); reaching the end preserves tail-follow;
re-sticking at a short turn's actual hold re-arms the runway
(:3144-3170); away-from-bottom breaks the pin
(`distance > prev + 1 && distance > 2`, :3182-3189); returning inside
the 70px band toward the bottom re-pins with a glide (:3190-3197).

**`jump_to_bottom` (transcript.rs:3748-3777)** — expanded prompt →
release + pin; live runway → re-arm the hold and glide back; else
`engage_pin` (:3782-3798: pin, hide pill, teleport to within 2.5
viewports, wake spring). `wake_spring` (:3802-3811) resets when past the
500ms settle grace; `spring_should_run` (:3815-3817).

**Row sync (transcript.rs:4179-4274).** `diff_rows` prefix/suffix;
equal-length in-place changes `remeasure_items` (keeps heights + the
scroll anchor — the live→complete flip), else `splice` (:4193-4218).
When the row count changes, the old last row is remeasured
(:4220-4224); a first echo after an empty list starts at the bottom edge
(`scroll_to {0, -viewport}`, :4225-4232). While pinned: live-following
(`should_anchor_live_stream`) → `scroll_to_end` + spring reset; first
fill / reduced motion → snap to end; glued → `scroll_by(-0.75)` so
layout holds position and the spring glides the growth (:4251-4273).
Chat switch restores `SavedViewport::{FollowTail, Anchored{own_turn}}`
after a populated replay only (:3960-4029, :2413-2440).

So the user-visible desktop behavior on send: the prompt glides to 48px
below the titlebar (or 64px for a chat's first row), the working
trailer + composer dock at the app bottom, and between them a blank
reservation ≈ viewport − 48 px that streaming content consumes from the
bottom — "a big new empty space".

### (a) Web model + where it diverges

The port EXISTS (ticket 18): the echo row installs the runway
(transcript.tsx:873-883 → `stick.onOwnSend`, stick-controller.ts:217-235);
the reservation floor is computed per render —
`reservationFloor = priorPositions[anchor] + viewport − inset −
priorPositions[last]`, the last row's height takes
`max(natural, floor)` and the excess rides the bottom spacer
(transcript.tsx:632-674, spacer math :1200-1205); the entry glide,
positioned one-sided hold, fill-retire, escape/restick and
jump-to-bottom re-arm all mirror the desktop (stick-controller.ts:582-704,
:329-372, :408-481). `lastRowPad` = `shellClearance + 24 + 8` at desktop
widths (transcript.tsx:577; the chat page publishes the measured stack,
chat-page.tsx:526-532).

Divergences that break or degrade the space:

1. **The resubscribe empty-window kills the runway (root cause, shared
   with S2).** On desync/reconnect the entries empty
   (transcript-store.ts:395-408, :452-460) → rows `[]` →
   `#stepOwnTurn`'s anchor disappears; because `seenPrompt` was already
   true it treats the disappearance as terminal and **retires the
   runway** (stick-controller.ts:592-601), then the reset frame
   re-reveals groups (S2) and the pin re-engages at the bottom. A
   reconnect during or right after a send = prompt parked, then the view
   snaps to the bottom and the reservation evaporates. The desktop never
   observes empty rows mid-session (transcript.rs:4032-4057) and its
   `OwnTurnAnchor` survives doc re-attachments.
2. **`OWN_SEND_SCROLL_SLACK_PX` and the fold-expansion term are omitted
   from the reservation** (documented deviations, ticket 18 Comments:
   "OWN_SEND_SCROLL_SLACK_PX (2px) is not added"; "Reservation expansion
   term (transcript.rs:3497-3504) omitted") — the web floor is
   `inset`-only (transcript.tsx:639-648).
3. **The floor reads the PREVIOUS layout's positions**
   (transcript.tsx:638, `priorPositions = positionsRef.current`) — an
   approximation of the desktop's pre-layout `update_runway_minimum`;
   ticket 18 documented ~20px parking drift on non-row-0 turns.
4. **Estimate drift makes the fill visible as jumps:** unmeasured rows
   estimate 30px (markdown) / 26px (tool groups)
   (transcript.tsx:1362-1389), so `reservationFilled`
   (transcript.tsx:688-697) and the tail positions converge in steps —
   the space "fills" in visible lurches instead of the desktop's
   analytic smoothness, compounding S2's jumping.
5. Web-only "ours" scroll discrimination and clamped-write rules
   (stick-controller.ts:420-429) paper over the drift but cannot remove
   it.

### (c) Root cause

The runway logic is present and faithful; the visible failure is the
S2 store-reset path destroying it (retire on the empty-rows window) plus
the standing estimate-drift deviations. There is no missing feature to
port — the fix is the S2 fix (never observe empty rows mid-session;
re-arm the replay baseline), optionally plus the slack/expansion terms.

### (d) Gap rows

| item | kind | desktop value | web value | fix sketch |
| --- | --- | --- | --- | --- |
| Runway survives stream resets | WRONG BEHAVIOR | rows never empty mid-session; anchor survives (transcript.rs:4032-4057, :3454-3481) | empty-rows window retires the runway (transcript-store.ts:395-408; stick-controller.ts:592-601) | S2 fix: keep entries through resubscribe; treat an empty `!loaded` window as transient in `#stepOwnTurn` |
| Reservation slack | MISSING | `inset − OWN_SEND_SCROLL_SLACK_PX(2) − expansion` (transcript.rs:3506-3509) | `inset` only (transcript.tsx:639-648) | subtract the slack (harmless on web) and the live fold expansion term |
| Fold-expansion term | MISSING | included pre-layout (transcript.rs:3490-3504) | omitted (ticket 18 deviation) | add the anchor row's fold tween height to the floor |
| Reservation from prior layout | APPROXIMATION | pre-layout `update_runway_minimum` with the height tree (transcript.rs:3483-3513) | prior-render positions, ~20px drift (transcript.tsx:638-648) | recompute the floor after measurement settles (finalize pass) |
| Fill detection smoothness | WRONG BEHAVIOR | analytic heights — `tail_reservation_filled` is exact | estimates 30/26 until measured (transcript.tsx:1362-1389) → stepped fill | better first-frame estimates (markdown by block type; tool groups by open state) |
| Echo start on empty list | MISSING | start the first echo at the bottom edge `{0, -viewport}` (transcript.rs:4225-4232) | not ported (the web's empty chat mounts the scroller fresh and snaps to end, transcript.tsx:933-977) | web-specific: covered by the mount-order path; verify a first send in an empty chat parks row 0 at 64 |

---

## Consolidated gap table

| # | item | kind | desktop value (file:line) | web value (file:line) | fix |
| --- | --- | --- | --- | --- | --- |
| 1 | Rail icon→label gap | MISSING | card `ml(8)` (transcript.rs:111, :7363, :6233) | no marginLeft (tool-group.tsx:417-424, :460-467) | `marginLeft: ACTIVITY_TEXT_GAP` on both card variants |
| 2 | Mid-session rows never empty | WRONG BEHAVIOR | atomic re-derive (transcript.rs:4032-4057) | resubscribe/generation empties first (transcript-store.ts:395-408, :452-460) | keep entries; let the reset replace wholesale |
| 3 | Replay baseline re-arms per attach | MISSING | `veil_attach_pending` (transcript.rs:4063, :4147-4154) | one-shot `revealBaselineRef` (transcript.tsx:321-328) | reset when replay returns to "pending" |
| 4 | Reveal counts survive resets | WRONG BEHAVIOR | counts from live rows (transcript.rs:4073-4095) | wiped by empty sync (tool-motion.ts:579-588) | covered by 2+3 |
| 5 | Open tool-group height estimate | WRONG VALUE | analytic (transcript.rs:96-136) | 26 regardless of open (transcript.tsx:1378-1384) | estimate the open height |
| 6 | File-icon asset URL | WRONG VALUE | absolute asset namespace (file_icons.rs:18, :59-73) | relative path breaks on `/chat/$chatId` (lib/file-icons.ts:23, :135-138) | `/file-icons/` prefix |
| 7 | Changes-pane file glyph | INVENTED | real per-file icon | `document` placeholder (diff-view.tsx:572-581) | swap in `FileIcon` |
| 8 | Jump pill shadow | WRONG VALUE | `shadow_md()` (shell.rs:6217) | popover token 0 8px 30px/0.35 (app.css:7472, :4296) | md-calibrated shadow |
| 9 | Jump visibility pinned gate | MISSING | `&& !pinned` (transcript.rs:3198) | none (stick-controller.ts:480) | add gate |
| 10 | Jump pill geometry | MATCHES | top −36 / h 30 / right 10 / 6px gap / DIALOG_IN 180ms (shell.rs:6166-6254) | identical (app.css:7446-7524) | none (change both clients together if at all) |
| 11 | Runway reservation slack + fold expansion | MISSING | inset − 2 − expansion (transcript.rs:3506-3509) | inset only (transcript.tsx:639-648) | add the terms |
| 12 | Runway survives resets | WRONG BEHAVIOR | anchor survives re-attach (transcript.rs:3454-3481) | retired on the empty window (stick-controller.ts:592-601) | S2 fix |
| 13 | Subagent-pane jump pill | NOT WIRED | second instance `bottom(16)` (shell.rs:6519-6536) | reusable `JumpPill`, no second mount | wire in the subagent pane host |

## Pure logic to port + desktop test names

- **Replay-baseline semantics (S2):** reset the reveal baseline whenever
  the store's replay returns to `pending`, and never run
  `ToolGroupMotionStore.sync` on a transient empty window. Port and name
  after: `tool_groups_stay_closed_on_populated_chat_attach`
  (transcript.rs:7851), `tool_groups_stay_closed_after_rapid_new_chat_navigation`
  (:7862), `tool_group_navigation_keeps_user_pins_and_new_arrivals`
  (:7894) — the web tests drive `ToolGroupMotionStore.sync` through a
  resubscribe-shaped sequence (populated → empty → reset) and assert
  closed groups, `renderedOpen == false`, no starts.
- **Store reset without the empty window (S2/S5):** a
  `transcript-store` test asserting a generation swap/desync keeps the
  previous entries until the reset frame lands (identity preserved by
  `preserveIdentity`, lib/transcript.ts:2214-2232).
- **Open-group estimate (S2/S5):** extend the virtualizer's
  first-frame estimate for `toolGroup` rows using the analytic heights
  (`TOOL_GROUP_HEADER_HEIGHT + chipsHeight + Σ detailHeight` when open).
- **Reservation terms (S5):** `OWN_SEND_SCROLL_SLACK_PX` and the
  fold-expansion term in `reservationFloor`; port
  `folding_releases_sent_turn_hold_without_removing_reservation`
  (transcript.rs:10392) shape as a web assertion on the floor after a
  toggle.
- **File-icon URL (S3):** unit-test `fileIconAssetPath` returns a
  root-absolute URL; a DOM-level acceptance assert at `/chat/<id>` that
  a tree row's `<img>` loads (naturalWidth > 0). The resolver tests
  already port the desktop's `file_icons.rs` suite (ticket 24,
  tests/file-icons.test.ts).
- Existing parity anchors to keep green while touching this area
  (already ported, do not rewrite): `jumpVisibility` hysteresis
  (`jump_button_stays_available_when_scrolling_down_until_near_bottom`,
  transcript.rs:7748), `shouldRestick` direction-awareness (:8270), the
  StickSpring suite (:8126-8268), `shouldBreakPin`, `shouldAnchorLiveStream`,
  `selectionScrollStep` (:8041).

## Desktop-only items NOT to port

- `ROBOCO_SCROLL_TRACE` / `ROBOCO_FRAME_STATS` / `ROBOCO_NO_RENDER_CACHE`
  and the `ENABLED` OnceLocks (rail.rs:224, transcript.rs) — dev
  instrumentation.
- `materialize_scroll_anchor` / `is_glued` / `scroll_by(-0.75)`
  (transcript.rs:3397-3433, :3819-3824, :4264-4271) — gpui glued-offset
  representation; the web writes concrete px and has its own
  clamped-write rules (stick-controller.ts:420-429).
- gpui list internals: `set_tail_reservation` / `tail_reservation_filled`
  / `remeasure_items` / `splice` (transcript.rs:3506, :3563, :4212-4214)
  — the web virtualizer implements the floor as the bottom spacer
  (transcript.tsx:1200-1205) and diffing as measured-height retention.
- `frost::frosted` scene layering, `ContentMask` shimmer strips,
  `gpui::deferred`/`anchored`, `motion::pulse_lease`, `RenderCache`,
  the markdown selection registry (tickets 19/21 standing decisions).
- gpui's `shadow_md` exact pixel recipe lives in the pinned zui fork —
  look it up there (Cargo.toml `[patch]` rev) before inventing numbers.
