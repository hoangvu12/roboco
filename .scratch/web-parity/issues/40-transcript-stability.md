# 40 — Transcript stability: collapsed groups stay closed, the transcript never jumps, the send runway survives resets

**What to build:** Today any engine reconnect, transcript desync, or resubscribe
empties the transcript for a frame: collapsed tool groups all spontaneously
re-open with the 90/65ms arrival stagger and then collapse again, the viewport
shuttles between "hold anchor" and "chase tail", and a just-sent prompt's
reserved space evaporates so the view snaps to the bottom. After this ticket
the web matches the desktop's contract: rows are never observed empty
mid-session (the reset frame replaces them atomically), the reveal baseline
re-arms on every replay so replayed history never re-animates, an open group's
first-frame height is its analytic height, and the own-send runway — glide →
positioned hold → fill retirement — plus the reservation's slack and
fold-expansion terms survive desyncs, reconnects, and engine restarts. Sending
a message leaves the big empty space below the prompt, exactly like the
desktop.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/transcript-tool-calls-scroll.md` S2 (a)–(d),
S5 (b-first, a, c, d), "Pure logic to port + desktop test names",
"Desktop-only items NOT to port"; consolidated gap rows 2, 3, 4, 5, 11, 12.

**Desktop reference (for lookups only):** `crates/ui/src/transcript.rs` —
`on_own_send` (:3323-3355), `update_runway_minimum` (:3485-3513),
`step_own_turn` (:3529-3734), `handle_scroll` (:3117-3206), `jump_to_bottom`
(:3748-3777), `engage_pin` (:3782-3798), `wake_spring` (:3802-3811),
`materialize_scroll_anchor` (:3400-3433), row sync / `diff_rows` application
(:4179-4274), `select_chat` (:3974-3989), atomic row re-derivation
(:4032-4057), replay baseline `veil_attach_pending` (:3915, :3954, :4063-4072,
:4147-4154), arrival counting (:4073-4113), own-send constants (:205-218),
`own_turn_glide_crossed` (:222-225), queued sends `on_own_queued_send`
(:3356-3395), `PendingQueuedTurns` (:2302-2344), `jump_button_shown`
(:3743-3745); `crates/ui/src/shell.rs` send wiring (:1287-1303); tests at
transcript.rs:7748, :7851, :7862, :7894, :8126-8268, :8270, :10392.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/state/transcript-store.ts` | edit | `TranscriptStore.resubscribe` (:395-408) and `#onItem`'s generation swap (:452-460) — keep entries and `loaded` through both paths while `replay` returns to `"pending"` |
| `web/packages/app/src/components/transcript.tsx` | edit | the `revealBaselineRef` effect (:321-329), `estimateRowHeight`'s `toolGroup` branch (:1378-1384), `reservationFloor` (:639-648) + its post-measurement finalize recompute, the per-commit compensation effect (:1030-1061) |
| `web/packages/app/src/lib/tool-motion.ts` | edit | `ToolGroupMotionStore.sync` (:534-590) — the transient-empty guard over the reveal/count cleanup (:579-588) |
| `web/packages/app/src/components/stick-controller.ts` | edit | `#stepOwnTurn`'s missing-anchor branch (:592-601) — a transient empty window waits, never retires the runway |
| `web/packages/app/tests/transcript-model.test.ts` | edit | new cases in §6 |
| `web/packages/app/tests/pending-send.test.ts` | edit | the store-reset case (§3.2) |

---

## 1. Context a fresh session needs

- The transcript is a chat's scrolling message list (vocabulary: **chat**, not
  session/thread; the credential is the only "Session"). It is virtualized at
  block granularity by `components/transcript.tsx`: a manual prefix-sum
  virtualizer (`positions`/`rowHeights` at transcript.tsx:657-674) over a
  `ResizeObserver` height map, with first-frame `estimateRowHeight`
  (transcript.tsx:1362-1389) standing in until a row measures.
- Scroll behavior lives in `components/stick-controller.ts::StickController`
  over `lib/stick-spring.ts::StickSpring` (a faithful port — ticket 18; do not
  rewrite the spring). The own-send runway is already wired: every new pending
  echo row installs it (transcript.tsx:873-883 → `stick.onOwnSend`,
  stick-controller.ts:217-235), the reservation floor is computed per render
  (transcript.tsx:632-674), rides the BOTTOM SPACER as plain scrollable space
  (transcript.tsx:1200-1205), and fill retirement is detected at
  transcript.tsx:688-697.
- Tool-group reveal state lives in one `ToolGroupMotionStore` per transcript
  surface (transcript.tsx:319; the desktop keeps these fields on the
  Transcript entity). `sync(rows, baseline)` (tool-motion.ts:534-590) assigns
  FUTURE reveal starts `now + 90ms + arrivalIx·65ms` (tool-motion.ts:570-576);
  the open/closed resolution in `ToolGroupRow` is
  `arrivalPending → effectiveAutoOpen → open` (tool-group.tsx:119-127).
- The store: `state/transcript-store.ts::TranscriptStore` watches
  `WATCH_DOC_MESSAGES`. `applyTranscriptFrame` (lib/transcript.ts:2145-2212)
  throws `TranscriptDesync` on anchor/part/count mismatches; the catch calls
  `resubscribe()` (transcript-store.ts:466-474). A WebSocket reconnect bumps
  the watch generation (transcript-store.ts:452-460). BOTH paths currently set
  `#entries = EMPTY_ENTRIES; #loaded = false; #replay = "pending"`
  (transcript-store.ts:395-408, :452-460) — the empty-rows window this ticket
  removes.
- The engine's doc stream always re-sends a full reset on re-subscribe (the
  store's own comment, transcript-store.ts:453-455), and the reset replaces
  entries wholesale while preserving identity (`preserveIdentity`,
  lib/transcript.ts:2149-2150) — so keeping the old entries through the
  resubscribe is safe: the reset frame lands as an atomic swap.
- This ticket is behavior-only: no new components, no new CSS classes, no new
  strings. It changes when sync/step code RUNS, not the landed rules inside
  them (tickets 18/19 own those specs).
- Ticket 18's landed Comments document two deviations this ticket now closes:
  "`OWN_SEND_SCROLL_SLACK_PX` (2px) is not added" and "Reservation expansion
  term (transcript.rs:3497-3504) omitted", plus a ~20px parking drift from
  reading the PREVIOUS layout's positions (transcript.tsx:638). This ticket
  supersedes those deviations — implement the terms.
- Reduced motion: the desktop checks `motion::reduced_motion(cx)`; the web
  honors `@media (prefers-reduced-motion: reduce)` the same way
  (transcript.tsx:560-563 already threads a `MediaQueryList` into the
  controller).
- No `--rb-*` tokens are involved (no visual changes); the acceptance "no new
  literal hex/px" rule still applies to any constants you add — they are
  numeric geometry already named in `lib/transcript.ts` / `lib/tool-motion.ts`.

---

## 2. Spec

### 2.0 The desktop contract (what the web must reproduce)

Copied from research S2(b) and S5 — the behavior table the whole ticket hangs
off:

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

### 2.1 Store reset without the empty window

The web's `TranscriptStore` must never publish an empty, unloaded snapshot
mid-session. Rows may only be observed empty on a genuine chat switch (fresh
mount) or an authoritative empty replay.

**States**

| State | Condition | What the store does (target) |
| --- | --- | --- |
| Desync resubscribe | `applyTranscriptFrame` throws `TranscriptDesync` (lib/transcript.ts:2176-2210) → `resubscribe()` (transcript-store.ts:466-474) | cancel + re-subscribe the watch, KEEP `#entries` and `#loaded`, set `#replay = "pending"`, commit |
| Generation swap | a reconnect delivers a frame with `generation > #generation` (transcript-store.ts:452-460) | adopt the generation, KEEP `#entries` and `#loaded`, set `#replay = "pending"` |
| Reset frame lands | the new stream's first frame is a reset | `applyTranscriptFrame`'s reset branch replaces the list atomically via `preserveIdentity` (lib/transcript.ts:2149-2150, :2214-2232) → `#replay = "populated"` |
| Chat switch | surface unmount/remount with a new doc id | unchanged — the only place rows start empty |
| Authoritative empty | a reset frame with zero entries | `#replay = "empty"`; rows are genuinely empty; the scroller may unmount (transcript.tsx:1186-1188) |

The stale-stream guard (transcript-store.ts:448-450: frames from a generation
older than `#generation` are dropped) stays exactly as is.

**Why it is safe.** The engine re-sends a full reset on re-subscribe
(transcript-store.ts:453-455), and `preserveIdentity` reuses entry object
identities for deep-equal entries — so the row cache (transcript.tsx:277-287),
the parse state, and the measured-height map all survive the swap, and the
reset cannot double-render.

### 2.2 Replay baseline re-arms per replay

The web's `revealBaselineRef` (transcript.tsx:321-329) is latched once per
surface mount and never reset — the direct inverse of the desktop's
per-attach `veil_attach_pending` gate.

**States**

| State | Condition | What the surface does (target) |
| --- | --- | --- |
| Baseline armed | first frame with `snapshot.replay === "populated"` | `revealBaselineRef.current = true`; `toolMotion.sync(rows, true)` (clears reveals, strips fold tween clocks, keeps user pins — tool-motion.ts:536-545) |
| Baseline re-arms | `snapshot.replay` returns to `"pending"` while the ref is already `true` (the resubscribe/generation swap) | `revealBaselineRef.current = false` |
| Next populated frame | the reset frame lands (`replay === "populated"`) | runs `sync(rows, true)` again — replayed history never re-animates |

**Interactions**

- The sync effect (transcript.tsx:322-329) keeps its shape; only the re-arm
  branch is added. A frame where rows are empty AND the store is not loaded
  (now only possible on a fresh mount) still runs `sync([], false)` harmlessly.

### 2.3 Reveal counts and the open-group first-frame estimate

**Reveal counts.** With §2.1 rows never empty mid-session, the cleanup that
deletes reveals and counts for rows absent from `rows` (tool-motion.ts:579-588)
can only fire for genuinely removed rows. As a safety net, `sync` must treat
`rows.length === 0` as transient when the store is replaying (not
authoritative-empty): skip the live-set cleanup entirely for that call.
Desktop rule (verbatim): "old_count read from the PREVIOUS LIVE ROWS
(`previous_tool_counts` off `self.rows`), never from a wiped map; baseline
frames use `tools.len()`" (transcript.rs:4073-4097).

**Open/closed resolution stays exactly ticket 19's** (already faithful):

```
arrivalPending      = !reduced && starts.some(start => now - start < 480ms)   // future starts count
effectiveAutoOpen   = autoOpen || arrivalPending
open                = !collapses || (fold?.open ?? effectiveAutoOpen)
```
(tool-group.tsx:119-127; desktop transcript.rs:5849-5859.)

**Open-group height estimate.** `estimateRowHeight`'s `toolGroup` branch
(transcript.tsx:1378-1384) returns `TOOL_GROUP_HEADER_HEIGHT` (26) for every
collapsible group regardless of open state, so an auto-opened or
user-pinned-open group lurches 26 → full body on mount. Target — from the
research fix sketch: estimate the OPEN height when the group will render open
(`fold?.open === true` or `autoOpen`):

```
estimate = TOOL_GROUP_HEADER_HEIGHT
         + chipsHeight(tools.length)        // CHIPS_TOP_PAD(2) + Σ row heights
         + Σ detailHeight(invocation/detail per open chip)
```

using the analytic helpers ticket 19 already exports
(`chipsHeight`, `detailHeight` in lib/transcript.ts). A closed group still
estimates 26; a spawn-only group still estimates `chipsHeight(n)`
(transcript.tsx:1382-1383).

### 2.4 The own-send runway — THE "big empty space"

The port exists (ticket 18); this section is the desktop model copied
verbatim from research S5(b-first) — implement it 1:1, closing the
divergences listed in §4. On the visible behavior: "the prompt glides to 48px
below the titlebar (or 64px for a chat's first row), the working trailer +
composer dock at the app bottom, and between them a blank reservation ≈
viewport − 48 px that streaming content consumes from the bottom".

**Send-time trigger.** Every send (not just the first) glides the prompt to
the viewport top and reserves the reply's space below it (shell.rs:1287-1288).
Wiring: the composer emits `ComposerEvent::Sent {chat_id, message_id}` and the
shell calls `transcript.on_own_send(chat_id, message_id)` (shell.rs:1296-1303);
queued sends call `on_own_queued_send`, which registers inertly and promotes
only when the real bubble exists (transcript.rs:3356-3395,
`PendingQueuedTurns` :2302-2344, cap 256). Web equivalent (already landed):
each new pending echo row calls `stick.onOwnSend(docId, row.id)`
(transcript.tsx:873-883) — unchanged by this ticket.

**`on_own_send` (transcript.rs:3318-3355).** Cancels the collapse-scroll and
pending user hold, discards the pending viewport, unpins, hides the jump
button, resets the spring (`spring_last_tick/settled_at/kick`,
`scroll_anim`), calls `materialize_scroll_anchor()` (pins the glued offset to
a concrete visible item so the pad reads as scrollable distance for the glide,
:3397-3433), installs `OwnTurnAnchor { held: true, positioned: false,
seen_prompt: prompt_ix.is_some() }` (:3344-3350), sets `own_turn_kick`, and
`remeasure_last_row()`.

**The reservation.** `update_runway_minimum` runs before every list layout
(transcript.rs:3483-3513): while an anchor row exists it calls
`list.set_tail_reservation(Some((anchor_ix, px(own_send_inset(anchor_ix)
− OWN_SEND_SCROLL_SLACK_PX − expansion))))` — the LAST row gets a minimum
height such that the anchor sits `inset` below the viewport top when scrolled
to the end; `expansion` adds the anchor row's live Show-more fold tween
(:3490-3504). The space is plain scrollable whitespace (never painted
chrome), consumed by streaming content and the working trailer in the same
layout pass. Constants:

| Const | Value | Source |
| --- | --- | --- |
| `OWN_SEND_TOP_INSET_PX` | `TITLEBAR_HEIGHT + 10` = **48** (row 0 uses **0** — its own 64px gap carries the chrome; adding both parked a first prompt ~66px low, user report) | transcript.rs:205, :3435-3445 |
| `OWN_SEND_SCROLL_SLACK_PX` | 2.0 (keeps gpui out of its shorter-than-viewport regime; "below perception") | transcript.rs:213 |
| `OWN_SEND_GLIDE_RETAIN` | 0.85 per 60fps frame (~90% in ~230ms, ease-out) | transcript.rs:216 |
| `OWN_SEND_GLIDE_SNAP_PX` | 1.0 | transcript.rs:218 |
| `GLIDE_MAX_VIEWPORTS` | 2.5 (entry-glide teleport cap) | proto/motion.rs (re-export, transcript.rs:63-67) |
| last row bottom pad | `bottom_clearance + TRANSCRIPT_FADE_BAND(24) + 8` | transcript.rs:5368-5373 |
| working trailer mount | under the LAST row's content, above its clearance pad | transcript.rs:5374-5378 |

Web target: `reservationFloor` (transcript.tsx:639-648) subtracts
`OWN_SEND_SCROLL_SLACK_PX` and the anchor row's live fold-tween height (the
expansion term) in addition to the inset — closing ticket 18's two documented
deviations.

**`step_own_turn` (transcript.rs:3529-3734).** Per frame: refresh the escape
baseline (:3540); if the anchor row is missing, wait one notification unless
`seen_prompt` (then retire, :592-601 web analogue); if
`list.tail_reservation_filled()` — the reply's natural content has filled the
reservation — retire the runway and `engage_pin` if it was held/pinned/at-bottom
(:3563-3577); entry glide eases `err·(1 − 0.85^frames)` toward
`anchor.top − inset`, capped at 2.5 viewports, landing by item anchor
`{item_ix: anchor_ix, offset: -inset}` (:3655-3731), never gliding past the
prompt (`own_turn_glide_crossed`, :222-225); once `positioned`, the hold
re-asserts the prompt's absolute position after every layout — ONE-SIDED (only
`err > 0.5` or `err < -(slack+2)`), eased, snapping within 1px (:3587-3653).

Web target: the missing-anchor branch in `#stepOwnTurn`
(stick-controller.ts:592-601) currently treats `anchor === null &&
ownTurn.seenPrompt` as terminal and RETIRES the runway. With §2.1 rows never
empty this can no longer fire from a resubscribe; make it robust anyway by
distinguishing the transient case — when the geometry callback reports no
anchor because the row list is momentarily replaying (`!loaded`/replay
pending), WAIT (schedule the next frame, keep the runway) exactly like the
desktop's "wait one notification" rule; only a prompt that is absent from a
POPULATED frame is terminal (failed echo or removed entry).

**Wheel/escape (handle_scroll, transcript.rs:3117-3206).** Synchronous
cancels; input releases the own-turn hold (the RESERVATION stays as plain
scrollable space); reaching the end preserves tail-follow; re-sticking at a
short turn's actual hold re-arms the runway (:3144-3170); away-from-bottom
breaks the pin (`distance > prev + 1 && distance > 2`, :3182-3189); returning
inside the 70px band toward the bottom re-pins with a glide (:3190-3197).
The web's `#onScroll` already ports this (stick-controller.ts:408-459,
"ours" clamped-write discriminators at :420-429) — unchanged, listed so the
implementer does not touch it.

**`jump_to_bottom` (transcript.rs:3748-3777)** — expanded prompt → release +
pin; live runway → re-arm the hold and glide back; else `engage_pin`
(:3782-3798: pin, hide pill, teleport to within 2.5 viewports, wake spring).
`wake_spring` (:3802-3811) resets when past the 500ms settle grace;
`spring_should_run` (:3815-3817). Already ported (stick-controller.ts
`jumpToBottom`); unchanged.

**Row sync (transcript.rs:4179-4274).** `diff_rows` prefix/suffix;
equal-length in-place changes `remeasure_items` (keeps heights + the scroll
anchor — the live→complete flip), else `splice` (:4193-4218). When the row
count changes, the old last row is remeasured (:4220-4224); a first echo after
an empty list starts at the bottom edge (`scroll_to {0, -viewport}`,
:4225-4232). While pinned: live-following (`should_anchor_live_stream`) →
`scroll_to_end` + spring reset; first fill / reduced motion → snap to end;
glued → `scroll_by(-0.75)` so layout holds position and the spring glides the
growth (:4251-4273). Chat switch restores `SavedViewport::{FollowTail,
Anchored{own_turn}}` after a populated replay only (:3960-4029, :2413-2440).
Web equivalents already exist (measured-height retention, the
`lastTotalRef` hold-open at transcript.tsx:675-684, viewport restore at
:885-977); only the §4 divergences change.

### 2.5 The per-commit anchor compensation vs. the pin

The jump amplifier (research S2(a), verbatim): "While the replay runs, each
group row's rendered height oscillates 26px → full body (reveal progress
multiplies row heights, tool-group.tsx:181-200) → measured heights change →
the virtualizer's prefix sums move (transcript.tsx:657-674). The scroller
compensates on every commit: while unpinned, the captured escape anchor is
re-asserted with `stick.writePreserving(target)` (transcript.tsx:1030-1061);
the pin re-engages when the reservation fills (`#stepOwnTurn` → `engagePin`,
stick-controller.ts:611-619) and the spring glides to the bottom
(stick-controller.ts:510-575) — the viewport shuttles between 'hold anchor'
and 'chase tail'."

With §2.1–§2.3 the oscillation stops at the source (no replay re-reveal, no
26px mis-estimate). The compensation effect itself stays — it is the
desktop's escape-anchor semantics — but must not fight an active runway:
the existing guards (skip while `ownTurnHeld` or a collapse scroll owns the
viewport, transcript.tsx:1044-1046) are the contract; do not remove them.

---

## 3. Pure logic to port

### 3.1 Replay-baseline semantics (S2)

From the research's port list, verbatim: "reset the reveal baseline whenever
the store's replay returns to `pending`, and never run
`ToolGroupMotionStore.sync` on a transient empty window. Port and name
after: `tool_groups_stay_closed_on_populated_chat_attach`
(transcript.rs:7851), `tool_groups_stay_closed_after_rapid_new_chat_navigation`
(:7862), `tool_group_navigation_keeps_user_pins_and_new_arrivals` (:7894) —
the web tests drive `ToolGroupMotionStore.sync` through a
resubscribe-shaped sequence (populated → empty → reset) and assert closed
groups, `renderedOpen == false`, no starts."

### 3.2 Store reset without the empty window (S2/S5)

Verbatim: "a `transcript-store` test asserting a generation swap/desync keeps
the previous entries until the reset frame lands (identity preserved by
`preserveIdentity`, lib/transcript.ts:2214-2232)." The fake watch client in
`tests/pending-send.test.ts` (:153-198) is the harness for it — drive
populated → swap generation → assert the snapshot still has entries and
`replay === "pending"` → deliver the reset → assert populated + identity
preserved.

### 3.3 Open-group estimate (S2/S5)

Verbatim: "extend the virtualizer's first-frame estimate for `toolGroup` rows
using the analytic heights (`TOOL_GROUP_HEADER_HEIGHT + chipsHeight +
Σ detailHeight` when open)." Unit-test the estimator directly
(`estimateRowHeight` is module-private in transcript.tsx — export it or test
through a seam) with closed / auto-open / user-pinned-open / spawn-only
cases.

### 3.4 Reservation terms (S5)

Verbatim: "`OWN_SEND_SCROLL_SLACK_PX` and the fold-expansion term in
`reservationFloor`; port `folding_releases_sent_turn_hold_without_removing_reservation`
(transcript.rs:10392) shape as a web assertion on the floor after a toggle."
The web test toggles the anchor row's user fold and asserts the computed
floor still binds (the expansion term absorbs the tween height instead of
collapsing the reservation).

### 3.5 Runway survival rule

The desktop's step rule (research S5 verbatim): "if the anchor row is
missing, wait one notification unless `seen_prompt` (then retire, :592-601 web
analogue)". Port that wait into `#stepOwnTurn`'s missing-anchor branch
(stick-controller.ts:592-601): a transient empty/replaying window schedules
the next frame; retirement requires the prompt to be absent from a populated
frame.

### 3.6 Existing parity anchors (keep green, do not rewrite)

Verbatim from the research: "jumpVisibility hysteresis
(`jump_button_stays_available_when_scrolling_down_until_near_bottom`,
transcript.rs:7748), `shouldRestick` direction-awareness (:8270), the
StickSpring suite (:8126-8268), `shouldBreakPin`, `shouldAnchorLiveStream`,
`selectionScrollStep` (:8041)." These already have web tests
(`stick-spring.test.ts`, `transcript-model.test.ts`) — they must stay green.

---

## 4. Gaps this ticket closes

Copied verbatim from research S2(d) (rows 1–4; the fifth S2(d) row — the
jump-visibility pinned gate — is ticket 41's) and S5(d) (all rows):

| item | kind | desktop value | web value | fix sketch |
| --- | --- | --- | --- | --- |
| Mid-session rows never empty | WRONG BEHAVIOR | rows re-derived atomically; empty only on chat switch (transcript.rs:3974-3989, :4032-4057) | resubscribe/generation swap sets entries to `[]` first (transcript-store.ts:395-408, :452-460) | keep the old entries through the resubscribe (the reset frame replaces wholesale via `preserveIdentity`, lib/transcript.ts:2149-2150) — or skip `toolMotion.sync` while `!loaded && rows.length === 0` |
| Replay baseline re-arms per attach | MISSING | `veil_attach_pending` set on attach, consumed by the first populated frame (transcript.rs:3915, :4063, :4147-4154) | `revealBaselineRef` latched once per surface, never reset (transcript.tsx:321-328) | reset `revealBaselineRef.current = false` when `snapshot.replay === "pending"` after it was set, so the next populated frame runs `sync(rows, true)` |
| Reveal counts survive stream resets | WRONG BEHAVIOR | `previous_tool_counts` from live rows (transcript.rs:4073-4080) | counts deleted whenever rows momentarily vanish (tool-motion.ts:579-588) | covered by the two rows above; optionally key the cleanup on row-id liveness across TWO consecutive syncs |
| Open-group height estimate | WRONG VALUE | analytic heights (rows 32, header 26, `detail_height` sums — no estimation) | `estimateRowHeight` returns 26 for every collapsible group even while auto-open (transcript.tsx:1378-1384) | estimate `26 + chipsHeight + Σ detailHeight` when the group will render open (fold/autoOpen), or measure eagerly on mount |
| Runway survives stream resets | WRONG BEHAVIOR | rows never empty mid-session; anchor survives (transcript.rs:4032-4057, :3454-3481) | empty-rows window retires the runway (transcript-store.ts:395-408; stick-controller.ts:592-601) | S2 fix: keep entries through resubscribe; treat an empty `!loaded` window as transient in `#stepOwnTurn` |
| Reservation slack | MISSING | `inset − OWN_SEND_SCROLL_SLACK_PX(2) − expansion` (transcript.rs:3506-3509) | `inset` only (transcript.tsx:639-648) | subtract the slack (harmless on web) and the live fold expansion term |
| Fold-expansion term | MISSING | included pre-layout (transcript.rs:3490-3504) | omitted (ticket 18 deviation) | add the anchor row's fold tween height to the floor |
| Reservation from prior layout | APPROXIMATION | pre-layout `update_runway_minimum` with the height tree (transcript.rs:3483-3513) | prior-render positions, ~20px drift (transcript.tsx:638-648) | recompute the floor after measurement settles (finalize pass) |
| Fill detection smoothness | WRONG BEHAVIOR | analytic heights — `tail_reservation_filled` is exact | estimates 30/26 until measured (transcript.tsx:1362-1389) → stepped fill | better first-frame estimates (markdown by block type; tool groups by open state) |
| Echo start on empty list | MISSING | start the first echo at the bottom edge `{0, -viewport}` (transcript.rs:4225-4232) | not ported (the web's empty chat mounts the scroller fresh and snaps to end, transcript.tsx:933-977) | web-specific: covered by the mount-order path; verify a first send in an empty chat parks row 0 at 64 |

(Consolidated gap-table rows 2, 3, 4, 5, 11, 12; the S5(d) prior-layout,
fill-smoothness and echo-start rows are S5-only and listed above.)

---

## 5. Do not

- **This ticket owns the stick/anchor/reservation model.** Ticket 41
  (transcript row polish) owns ONLY the tool-chip icon→label margin and the
  jump pill's shadow + visibility gate; ticket 42 owns the icon family. Do not
  duplicate their work, and do not "fix" the jump pill here.
- **Ticket 39 (send path)** owns the composer → send → echo wiring. The runway
  consumes the echo rows that wiring already produces
  (transcript.tsx:873-883); do not rebuild the send machinery or the
  `PendingQueuedTurns` registration (18 landed it tested).
- **Ticket 19 (tool groups, Status: done)** landed the group/chip/fold/reveal
  spec — open/closed resolution, `FoldState`, the 90/65ms stagger, the 140ms
  fold, blob fetches. Do not re-litigate or rewrite those rules; this ticket
  only changes WHEN `sync` runs (baseline + transient empty window) and what
  the estimator returns.
- **Ticket 18's deviations are superseded, not preserved**: the slack and
  fold-expansion terms are now required (§2.4), and the prior-layout floor
  gets a finalize recompute. Do not keep the old "omit" comments.
- **Desktop-only items — do not port** (verbatim from the research):
  `ROBOCO_SCROLL_TRACE` / `ROBOCO_FRAME_STATS` / `ROBOCO_NO_RENDER_CACHE`
  and the `ENABLED` OnceLocks (rail.rs:224, transcript.rs) — dev
  instrumentation; `materialize_scroll_anchor` / `is_glued` /
  `scroll_by(-0.75)` (transcript.rs:3397-3433, :3819-3824, :4264-4271) —
  gpui glued-offset representation, the web writes concrete px and has its own
  clamped-write rules (stick-controller.ts:420-429); gpui list internals
  `set_tail_reservation` / `tail_reservation_filled` / `remeasure_items` /
  `splice` (transcript.rs:3506, :3563, :4212-4214) — the web virtualizer
  implements the floor as the bottom spacer (transcript.tsx:1200-1205) and
  diffing as measured-height retention; `frost::frosted` scene layering,
  `ContentMask` shimmer strips, `gpui::deferred`/`anchored`,
  `motion::pulse_lease`, `RenderCache`, the markdown selection registry
  (tickets 19/21 standing decisions).
- Do not wipe reveal counts on a transient empty window even as a fallback —
  at most key the cleanup on row-id liveness across TWO consecutive syncs
  (§2.3).
- Do not paint the reservation: it is plain scrollable whitespace carried by
  the bottom spacer (transcript.tsx:1200-1205), never chrome.
- Do not change the scroller-presence rule (transcript.tsx:1186-1188) or the
  `lastTotalRef` hold-open (transcript.tsx:675-684) — they are the web's
  analogues of the desktop's logical offsets and stay.

---

## 6. Acceptance

- [ ] Paste a transcript with ≥3 tool groups, collapse 2 of them, then force a
      resubscribe (engine reconnect or a tripped desync) — both groups STAY
      collapsed (renderedOpen false, no reveal starts), no scroll jump on the
      reset frame, no auto-open/close oscillation.
- [ ] Same sequence while a run is streaming: only genuinely NEW streamed
      arrivals stagger in; replayed rows never animate; user pins survive
      replay (a group the user EXPANDED stays expanded).
- [ ] Send a message → the prompt glides to 48px below the titlebar (64px for
      the chat's first row) and a big empty space ≈ viewport − 48px opens
      below it, consumed from the bottom by streaming content — like the
      desktop.
- [ ] A reconnect during or right after a send keeps the runway: the
      reservation does not evaporate and the view does not snap to the bottom
      (the old retire-on-empty path, stick-controller.ts:592-601).
- [ ] An auto-open group's first frame estimates its open height
      (26 + chipsHeight + Σ detailHeight): opening a long group does not
      visibly reflow the rows below it.
- [ ] Unit tests in `web/packages/app/tests/transcript-model.test.ts`:
      `tool_groups_stay_closed_on_populated_chat_attach`;
      `tool_groups_stay_closed_after_rapid_new_chat_navigation`;
      `tool_group_navigation_keeps_user_pins_and_new_arrivals` (drive
      `ToolGroupMotionStore.sync` through populated → empty → reset; assert
      closed groups, `renderedOpen == false`, no starts); the open-group
      estimator cases; the reservation floor after a fold toggle
      (`folding_releases_sent_turn_hold_without_removing_reservation` shape).
- [ ] Unit test in `web/packages/app/tests/pending-send.test.ts`: a
      generation swap / desync keeps the previous entries until the reset
      frame lands (identity preserved by `preserveIdentity`), `replay`
      returns to `"pending"` then `"populated"`.
- [ ] Existing parity anchors stay green: `jumpVisibility` hysteresis,
      `shouldRestick`, the StickSpring suite, `shouldBreakPin`,
      `shouldAnchorLiveStream`, `selectionScrollStep` (stick-spring.test.ts,
      transcript-model.test.ts).
- [ ] A first send in an empty chat parks row 0 at 64px (echo-start
      verification).
- [ ] Screenshot pair, desktop vs web, states: (a) a settled chat with two
      collapsed groups immediately after an engine restart (no re-reveal);
      (b) a send just dispatched — prompt parked 48px under the titlebar with
      the blank reservation below it; (c) the same send mid-stream, the space
      half consumed; (d) a wheel-escape during the runway with the released
      reservation still scrollable.
- [ ] `pnpm -r build` green; `pnpm --filter @roboco/app test` green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)
