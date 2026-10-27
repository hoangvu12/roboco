# 58 — First-run setup on desktop: welcome → appearance → agents on this device → first project (A27)

**Lane:** 4b Features: desktop UI. **Effort:** M.

**What to build:** A fresh install drops straight into an empty shell
today. Port upstream's onboarding (`crates/ui/src/onboarding.rs`,
`onboardingCompleted` in `UiSettings`, upstream `settings.rs:1181`)
without its cloud step:
1. **Welcome.**
2. **Appearance:** theme, wallpaper and effect (reuses Settings →
   Appearance widgets).
3. **Agents on this device:** detected CLIs with on/off (the Harnesses
   page's model, `SetHarnessEnabled`) and sign-in entry points.
4. **First project:** New project picker, or skip to a projectless chat.

Back / Skip / Continue with progress. Shown once. Existing installs (a
non-empty chat list or settings file) are marked completed silently.
Re-runnable from Settings → General ("Show welcome again").

**Upstream:** `cc7da5c2a` (#966) `crates/ui/src/onboarding.rs`,
`settings.rs:1181`.

**Roboco files:** new `crates/ui/src/onboarding.rs`,
`crates/ui/src/settings.rs`, `crates/ui/src/shell.rs` (gate),
`crates/ui/src/settings/general.rs`.

**Excluded:** "Sync your work" and "Connect GitHub" steps, and sign-in
to Zeron Cloud (cloud).

**Proto change:** no.

**Blocked by:** 01. Ticket 59 mirrors it on Android.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A27; `pass2-features-desktop.md` §1.

- [ ] A fresh data dir shows the flow; finishing or skipping sets the flag (UI test)
- [ ] Existing installs never see it
- [ ] The agents step toggles harnesses and starts a sign-in
- [ ] Commit records `cc7da5c2a` (onboarding slice, cloud steps excluded)

## Comments
