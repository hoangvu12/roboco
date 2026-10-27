# 46 — Engine-posted desktop notifications while the window is closed (#966 slice c) — PROTO

**Lane:** 4a Features: engine + agents. **Effort:** M.

**What to build:** With the engine in the background (44), done / needs
you / failed notifications must still fire when no window is open. Port
upstream's engine-side notifier:
- `crates/notify` + `engine/src/desktop_notify.rs` post the banner from
  the engine process, using the same three kinds and the user's
  Notifications settings.
- `SetViewportFocus`: a focused window tells the engine, which holds its
  banners back so they don't double up with the in-app chime and banner
  (ticket 04's UI path).
- A click opens the app on that chat (Windows: AppUserModelID
  activation, using the identity from 04; Linux: the desktop-entry
  action).

**Upstream:** `cc7da5c2a` (#966) slice c: `crates/notify` (new),
`crates/engine/src/desktop_notify.rs` (+744), `SetViewportFocus` RPC.

**Roboco files:** new `crates/notify` (`roboco-notify`),
`crates/engine/src/desktop_notify.rs`, `crates/rpc/src/lib.rs`,
`crates/ui/src/notify.rs`, `crates/ui/src/state.rs` (focus reporting).

**Excluded:** APNs/push (cloud), macOS-only code paths.

**Proto change:** yes (`SetViewportFocus`).

**Blocked by:** 44, 04.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §B unticketed #966 slices; `part-06.md` `cc7da5c2a` (c);
`pass2-features-desktop.md` §5.

- [ ] With the window closed, a finished turn posts a banner; a click opens the chat (Windows + Linux)
- [ ] With a focused window, the engine posts nothing and the UI handles it (test)
- [ ] Settings toggles respected engine-side
- [ ] Commit records the `cc7da5c2a` notify slice

## Comments
