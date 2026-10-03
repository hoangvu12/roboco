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

- [x] Reduce-motion: system-follow default with On/Off pin; read at boot
      and on focus regain
- [x] Pause-in-background toggle (off by default)
- [x] Streaming veil baseline under reduced motion
- [ ] Tests green (deferred: written + ported, execution deferred to the
      wave-final batched pass per the verification-economy directive; also
      excludes the D-Bus-wake check, which needs the Linux test run)
- [x] Web: Motion pin row in settings-appearance overriding
      prefers-reduced-motion when pinned
- [x] Port commit records upstream SHA

## Comments

- Ported `54b49949` (#642) by intent across the rebrand: the MotionState
  global (preference/pause/system/active), `resolve`, the setter paths
  (immediate persistence), `window_activation_changed` (re-reads the OS on
  focus regain; skips entirely in windows without motion state so shell
  tests never wake the portal D-Bus thread), the shell's settings mirror
  (shell saves keep the motion choices), the Appearance Motion section,
  and the transcript's reduced-motion veil baseline.
- Exclusion: upstream's macOS `NSWorkspace`-based OS read is dropped
  (ticket + task directive: no macOS-only mechanics) —
  `refresh_system` exists only for Windows (SPI_GETCLIENTAREAANIMATION)
  and Linux (ashpd desktop portal, "settings" feature added); every other
  platform reads as no preference.
- `ui/Cargo.toml`: ashpd gains the "settings" feature (Linux);
  windows-sys gains "Win32_UI_WindowsAndMessaging" (the SPI read).
- Tests ported: motion.rs (resolve semantics + the focus/preference drive
  of the global flag), shell.rs (shell_saves_keep_motion_settings_chosen_
  in_appearance), transcript.rs (text_streamed_under_reduced_motion_does_
  not_fade_in_when_motion_resumes).
- Web parity: `ReduceMotion` ("system"|"on"|"off") persisted through the
  appearance store + `ui-settings` (`reduceMotion` field, healed); the
  central `lib/reduced-motion.ts` resolves the pin over the media query
  and every JS reader (media hook, artwork store, tool-motion clock,
  terminal dock, history/changes surfaces, chat page, queue rows) now
  consumes it; `theme.ts` installs the resolved flag as
  `data-reduced-motion` so the stylesheet's reduce blocks follow the OS
  under `:not([data-reduced-motion="off"])` and apply under
  `[data-reduced-motion="on"]`. The background-pause row is deliberately
  web-absent (a browser cannot observe app-window focus); the pin row is
  the ticket's named deliverable.
- Verification: `pnpm -r build` from web/ (clean); test execution deferred
  to the wave-final batched pass (user directive).
