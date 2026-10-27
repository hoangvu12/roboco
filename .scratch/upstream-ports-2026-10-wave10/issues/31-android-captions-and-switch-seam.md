# 31 — Android: "Message from" caption over the bubble, tap opens the sender; provider-switch seam (#1062, A15 Android, N1)

**Lane:** 2 Messages (Android files; runs on `wave10/messages`). **Effort:** M.

**What to build:**
1. **Captions (#1062).** Android bubbles show the raw
   `[Message from Roboco chat …]` routing header today. There's no
   parser in `crates/mobile` or `apps/android`. Use ticket 29's
   `proto::agent_messages` in `crates/mobile/src/layout/rows.rs`: a
   caption row above the bubble with the sender's harness mark, and the
   body without the header. A tap opens the sending chat: on a phone,
   pushed over this chat; on a tablet, in the main column. Add
   `brand:<harness>` transcript icons (Android already has
   `design/BrandMarks.kt` for every non-declined harness).
2. **N1: the provider-switch seam is invisible on Android.** The mobile
   layout drops `MessagePart::HarnessSwitch` in its `_ => {}` arm
   (`crates/mobile/src/layout/rows.rs:419-461`). Roboco's own
   failure-driven failover writes these seams too, so on the phone a
   chat silently changes agent. Add upstream's `switch_row`
   ("Switched from [logo] to [logo]", a chip row Android already
   paints), shaped like 29's desktop caption.

**Upstream:** `f65c5bc60` (#1062) mobile + Android parts
(`crates/mobile/src/layout/{mod,rows,tests}.rs`; upstream Android
`Widgets.kt`, `SessionScreen.kt`, `TabletShell.kt` are reference only,
not a base). `cc7da5c2a` (#966) `rows.rs:524,948` `switch_row`.

**Roboco files:** `crates/mobile/src/layout/{rows,mod,tests}.rs`,
`apps/android/app/src/main/java/sh/roboco/android/transcript/*`
(caption row + tap routing), `shell/` routing for phone vs tablet,
`design/BrandMarks.kt`.

**Excluded:** iOS; `crates/client/src/demo/*`. `crates/mobile/src/caption.rs`
is the voice caption veil, unrelated and dropped.

**Proto change:** no (UniFFI row-kind addition only. Run
`cargo xtask android-core` + app unit tests).

**Blocked by:** 29.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A15, §C; `pass2-features-android.md` N1;
`part-07.md` `f65c5bc60`; `pass3-ios-1.md` #1062.

- [ ] Layout tests: a caption row and a header-free body; a switch row between harnesses
- [ ] A tap on the caption opens the sender (phone push and tablet main column; instrumented or JVM test)
- [ ] A failover seam shows on Android
- [ ] Goldens for the caption and the switch row re-captured if any transcript golden moves
- [ ] Commit records `f65c5bc60` (mobile/Android parts) and the `cc7da5c2a` `switch_row` slice

## Comments
