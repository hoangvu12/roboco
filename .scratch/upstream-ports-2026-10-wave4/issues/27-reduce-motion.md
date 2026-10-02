# 27 — Reduce motion from the system; pause animations in background

**What to build:** gpui snaps every animation when `App::reduce_motion`
is set and our motion kit, loaders and hand-driven tweens already read
it, but nothing ever SETS it — the OS accessibility setting was ignored.
Add a Motion section in Appearance: Reduce motion follows the system by
default (Windows "Animation effects", the desktop portal's reduced-motion
key on Linux) or pins On/Off; the OS value is read at boot and again
whenever the main window regains focus (changing it means leaving the
app). Pause animations in background, off by default, holds animations
still while the main window is unfocused so spinners and shimmers stop
redrawing behind other apps. Streaming transcript text under reduced
motion drops the row's veil and marks it a baseline instead of
dissolving in when motion resumes. Only the app installs motion state —
windows without it skip the OS read (shell tests activate windows and the
portal D-Bus thread otherwise wakes deterministic test schedulers).

**Blocked by:** 26 — both rewrite `settings/appearance.rs` (shuffle
surfaces first, Motion section on top).

**Status:** ready-for-agent

**Upstream SHAs:** `54b49949` (#642) — 7 files: `ui/src/{lib,motion,
settings,shell,transcript}.rs`, `settings/appearance.rs`, `ui/Cargo.toml`
(portal dep on Linux). Our `motion.rs` already honors
`cx.reduce_motion()` — the port adds the setter paths + settings section.
Windows "Animation effects" is read via the OS SPI; Linux via the
freedesktop portal setting. **Web parity (deliverable):** the web client
already snaps to CSS `prefers-reduced-motion` (system-follow is native);
port the explicit pin — a Motion row in
`web/packages/app/src/routes/settings-appearance.tsx` persisted via
`lib/appearance-store.ts` that overrides the media query when pinned
On/Off.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
motion + settings + transcript veil tests (deterministic scheduler must
stay clean — the portal read is app-only).

- [ ] Reduce-motion: system-follow default with On/Off pin; read at boot
      and on focus regain
- [ ] Pause-in-background toggle (off by default)
- [ ] Streaming veil baseline under reduced motion
- [ ] Tests green (no D-Bus wake in shell tests)
- [ ] Web: Motion pin row in settings-appearance overriding
      prefers-reduced-motion when pinned
- [ ] Port commit records upstream SHA

## Comments
