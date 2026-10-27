# 59 — First-run setup on Android (N4)

**Lane:** 4b Features (Android files; runs on `wave10/android`). **Effort:** M.

**What to build:** After pairing, Android drops straight into the shell
(`update/UpdateCenter.kt:73` mentions onboarding, but none exists). Port
the iPhone flow, minus cloud:
1. **"Make it yours":** colour scheme, new-chat wallpaper and effect,
   with the blurred wallpaper behind the pages (`Design/Wallpaper.swift`
   `soften`).
2. **"Your agents":** the Providers page inline (ticket 40), e.g.
   "Found Claude Code and Codex on <computer>. Turn on the ones you'd
   like to use."
3. **"Stay in the loop":** the notification permission ask with
   Finished / Needs you / Something went wrong. It replaces today's
   first-session ask (`notify/NotificationPermission.kt:112-126`) for new
   users.
4. **"You're all set":** a summary and **Start a session**.

Back / Skip / Continue with "2 of N" progress. Shown once per install
after the first successful pairing.

**Upstream:** `cc7da5c2a` (#966) iOS slice:
`apps/ios/Zeron/Cloud/OnboardingViewController.swift:3-11,263-313,360-412,413-440,440-490`
(behaviour spec).

**Roboco files:** `apps/android/.../onboarding/*` (new),
`shell/AppRouter.kt`, `settings/*` (reuse), goldens.

**Excluded:** "Sync your work", "Connect GitHub" (cloud); sign-in to Zeron.

**Proto change:** no.

**Blocked by:** 40 (providers), 58 (desktop flow wording/step order).

**Status:** ready-for-agent

**Source:** `pass2-features-android.md` N4.

- [ ] Fresh pair shows the flow once; skip works at every step (flow test)
- [ ] Step 2 toggles agents on the paired computer
- [ ] Notification ask happens in step 3, not again later
- [ ] Goldens for each page
- [ ] Commit records the `cc7da5c2a` iOS onboarding slice

## Comments
