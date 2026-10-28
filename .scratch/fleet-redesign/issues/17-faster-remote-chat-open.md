# 17 — Opening a chat on another machine: fewer waits

**Why:** each request to another machine costs one round trip, about 160 ms over the user's relayed Tailcat link (ticket 16), and downloads run at about 1.2 MB/s. Opening a remote chat feels slow ("a bit slow when I open other devices' chats", 2026-10-28). The desktop already reuses one open connection per engine. Transcripts ride it as a `WatchDocMessages` subscription (`crates/ui/src/state.rs:3459-3463`, `crates/engine-client/src/registry.rs:449-456`). So the cost is **how many requests wait on each other** and **how many bytes come before the first paint**.

**What to build:**

1. **Measure first.**
   - Put a delay proxy in front of a loopback engine: 160 ms round trip, about 1.2 MB/s. The mobile tests' `stall_proxy` is a starting point.
   - Record every request the desktop and the phone make when a remote chat is selected: method, start, end, and what it waited on. Cover the transcript, file/git status, previews, harness and model catalogs, side chats, and so on.
   - Time to first message on screen, and to the full transcript, for a small chat and a big one (several MB).
   - Write the numbers in Comments.
2. **Then fix what the numbers show.** Likely candidates:
   - Show the cached transcript at once. The desktop keeps opened transcripts (remote-access docs, § Using several engines), and so does the phone, so render the cache and apply the reset or delta when it lands.
   - Send independent requests at the same time instead of one after another.
   - Check that `openingTail` really delivers the newest messages first and that the rest streams in behind them, without blocking input.
   - Defer anything that isn't visible (catalogs, previews) until after the first paint.
   - Don't re-fetch what an earlier open of the same chat already has (transcript resume digests).
3. **Re-measure with the same proxy, and add a regression test.** On the 160 ms proxy, opening a cached remote chat shows messages in at most one round trip plus render time. An uncached small chat needs at most about 2–3 round trips.

**Blocked by:** —

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] Before/after table (desktop and phone, small and big chat) in Comments
- [ ] A delay-proxy test that fails if chat open goes back to sequential round trips
- [ ] No behaviour change on a fast or local engine (existing UI and mobile suites green)

## Comments
