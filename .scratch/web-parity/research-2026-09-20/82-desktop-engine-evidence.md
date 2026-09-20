# Ticket 82: desktop arrival and engine frame evidence

Read-only source investigation, 2026-09-21, worktree `roboco-wt/pr`, HEAD `94b7f643`. This note covers native and engine source, not the root agent's mounted web reproduction. No Rust test, application, server, or browser was run. All line references below are code evidence; none establishes which frames occurred in the reported live session.

## Desktop selection and first populated paint

1. `AppState::select_chat` clears the selected transcript, increments its revision, sets `transcript_replayed = false`, and drops the previous subscription (`crates/ui/src/state.rs:1748-1756`). It starts `spawn_transcript_watch` and notifies (`1775-1792`).
2. The watch first reads the native offline cache on a background executor, then calls `apply_transcript(entries)` when the chat still matches and replay has not landed (`state.rs:2174-2195`). The cache stores and loads raw `Vec<SessionMessageEntry>` without timestamps or status adjustment (`crates/ui/src/engine_cache.rs:47-61`). `apply_transcript` sets `transcript_replayed = true` (`state.rs:1011-1021`). Only after this cache step does the watch subscribe (`state.rs:2197-2211`). Native therefore also has a possible cache-seed-to-live-reset sequence; it must not be described as having no offline seed.
3. Transcript `sync` derives `Pending`, `Empty`, or `Populated` from `transcript_replayed` and entries, and compares selected chat identity (`crates/ui/src/transcript.rs:4091-4125`). Attach or pending replay arms `veil_attach_pending` (`4123-4125`). Attach restores remembered explicit group/detail pins as settled folds before row derivation (`4134-4187`), clears thought completion tracking (`4194-4198`), and resets the spring (`4238-4242`). With no saved unpinned viewport, it starts pinned at the tail (`4231-4236`).
4. Both native and web derive group automatic openness from **entry status and part position**, not merely the chat's Working indicator. Native `rows_for_entry` sets `auto_open = streaming && last_ix == last_part_ix` (`transcript.rs:1208`, `1257-1275`). A reasoning item is unresolved/live only while it is the last part of a streaming entry (`1332-1346`). Tool resolution alone does not remove tail-group automatic openness.
5. On the first nonempty frame, `replay_baseline` clears reveal records, prior fold clocks, thought completion history, and detail heights (`transcript.rs:4273-4293`). Existing tools count as already present, so no entrance timestamps are assigned (`4351-4373`). That frame consumes `veil_attach_pending` (`4411-4418`).
6. Native `render_tool_group` computes `open = !collapses || fold.open.unwrap_or(auto_open || arrival_pending)` (`transcript.rs:6239-6253`). The first render has no preceding `rendered_open`, so it records the actual open endpoint without seeding a fold tween (`6255-6267`). Thus an unpinned streaming tail group mounts open and settled. An explicit saved false pin correctly mounts it closed and settled.
7. The native list lands its first fill with item-anchored `scroll_to_end`, without a spring (`transcript.rs:4521-4533`). Saved user positions instead resolve to a concrete row offset (`3156-3183`). Later live-following commits anchor the end and reset the spring (`4440-4446`, `4521-4528`). This is the precise native initial-fill behavior to compare with browser measurement and dock timing.

## Limits of the native parity claim

The user-observed native behavior is consistent with an initial streaming-tail frame and the baseline above. Source does **not** prove that native always preserves that open endpoint across subsequent data changes.

- A live reset applies its entries and sets `transcript_replayed = true` (`state.rs:1026-1035`). It carries no separate replay epoch or seed/reset provenance into `Transcript::sync`.
- If a cache seed already consumed the first-populated baseline, a later same-chat nonempty reset remains `Populated`; it does not itself satisfy attach/pending conditions at `transcript.rs:4123`. Unlike web's explicit reset-baseline machinery, the native code can treat that correction as a regular content change.
- A later rendered group open-state flip seeds a tween from its recorded height (`transcript.rs:6257-6265`). Likewise, an actual previously-unresolved thought becoming resolved can seed a detail tween (`4330-4344`). Native is therefore not intrinsically immune to an outdated streaming seed corrected by a later frame. Capturing the native seed and frames would be required to claim identical inputs produce different outcomes.
- Both platforms intentionally permit a later part to remove a former group's tail position while the same assistant entry remains streaming. Mounting the last tool group open does not promise that it remains open after subsequent text or a separate spawn group arrives.

Existing native source tests support navigation/baseline intent: `tool_groups_stay_closed_on_populated_chat_attach` (`transcript.rs:8319`), `tool_groups_stay_closed_after_rapid_new_chat_navigation` (`8330`), `tool_group_navigation_keeps_user_pins_and_new_arrivals` (`8362`), and `restored_folds_do_not_replay_arrival_motion` (`8546`). These tests were read, not executed in this investigation.

## What a mid-run engine reset contains

`WatchDocMessages` opens the doc and passes `watch_messages()` into `doc_messages_stream` (`crates/engine/src/rpc.rs:951-960`). `watch_messages` subscribes before checking the dirty mirror and refreshes if needed (`crates/engine/src/doc_host.rs:276-289`). Publishing reads doc entries, joins continuations, and sends the immutable joined vector (`379-386`). Unwatched docs mark the mirror dirty and release its cached vector (`394-403`).

The RPC stream's first iteration sends `TranscriptFrame::reset(&current)` from its current watch snapshot; later iterations diff that snapshot against the next published one (`rpc.rs:803-840`). It does not reconstruct streaming state from registry/chat status or force an entry terminal merely because it is a reset. Continuation joining concatenates parts into the root entry but keeps the root entry's scalar status (`crates/doc/src/schema.rs:894-918`).

The persisted document can lag the current in-memory harness fold:

- `STREAM_COMMIT_MS` is 120 ms (`crates/doc/src/constants.rs:15`). The first dirty event arms a flush for that interval (`crates/engine/src/sessions.rs:2183-2186`); the flush writes the current folded segment (`1556-1565`).
- `sync_segment` skips an empty fold and lazily creates its writer on the first nonempty flush (`sessions.rs:1263-1279`). `SegmentWriter::begin` writes and commits an assistant entry with `status: streaming` and empty parts (`schema.rs:941-964`), then `sync` appends/updates parts and commits only if dirty (`1007-1069`). Those separate commits can coalesce before the watch is polled; source does not guarantee that every intermediate commit is individually delivered.
- `sync` does not rewrite entry status. `finish` syncs the last parts then writes the supplied terminal status (`schema.rs:1074-1079`). A tool finishing is not itself an entry-level terminal status change.
- A run described as still working need not have a streaming final entry at every instant. A steer boundary finishes the prior assistant segment Complete, clears its fold, selects the next entry id, and leaves chat status Working (`sessions.rs:2006-2035`); the next assistant entry appears lazily on nonempty output. This is an available explanation for a particular frame shape, not evidence that steering occurred in the report.

Consequently a seconds-old offline seed may still say Streaming and still have a tool/reasoning tail, while the reset legitimately carries appended parts or a terminal prior segment. The 120 ms cadence does not provide a freshness guarantee for an offline cache and cannot establish a 500 ms arrival timeout cause. Actual seed bytes, reset/delta payloads, arrival timestamps, and rendered group identities must decide that attribution.

## Confirmed comparisons and remaining evidence

Confirmed native contract: first populated replay is rendered at its real fold endpoint; a live tail group is open without replay entrances; explicit pins restore before row derivation; first list fill lands instantly at the tail. Confirmed common mechanism: group openness follows streaming status plus final-part position, and thought completion follows final-part position. Confirmed native/web difference relevant to analysis: native has only attach/pending/first-populated classification, whereas web has explicit accepted-reset provenance/epochs.

This note does not attribute the reported flicker to cache correction, engine coalescing, pin restoration, thought completion, arrival timeout, dock rendering, or resubscription. The parent investigation's mounted harness and, if necessary, live frame capture must establish the actual transition and whether the reset or a later delta owns it.
