# 07 — "Pause animations in background" gets a web-native row and mechanism

**What to build:** The desktop's Appearance page has a "Pause animations in
background" toggle (settings/appearance.rs:3877-3917, meta "Hold animations
still while Roboco isn't the focused window.") whose mechanism flips the
global gpui reduce-motion flag while the main window is inactive
(motion.rs `resolve`'s 4th arm: `reduced || (pause_in_background && !active)`).
The web **persists the field** (state/ui-settings.ts:445
`pauseAnimationsInBackground`, default false, healed :1012) but has zero
consumers and no row — and `lib/reduced-motion.ts:27-33` documents the
omission with a weak rationale ("a browser cannot observe app-window
focus" — `document.hasFocus()` can observe document focus).

**After this ticket:** the row exists in Settings → Appearance (after
Reduce motion), and with it on, the web's persistent animations hold still
while its document is unfocused, resuming mid-phase on refocus.

**Blocked by:** None.

**Status:** ready-for-agent

**Research:** `.scratch/web-parity-next/research.md` (settings item 1 — the
full mechanism research).

**Desktop reference (for look-ups only):**
`crates/ui/src/settings/appearance.rs:3877-3917` (the row),
`crates/ui/src/motion.rs` — `set_pause_in_background` (~:845), `resolve`
(~:741), `window_activation_changed` (~:838), `pulse_delta_every` (~:140).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/routes/settings-appearance.tsx` | edit | the row after Reduce motion (~:337-365): existing `RbSwitch`, title "Pause animations in background", copy adapted to "…while this tab isn't focused", `uiSettings.updateImmediate({ pauseAnimationsInBackground })` |
| `web/packages/app/src/lib/reduced-motion.ts` | edit | `effectiveReducedMotion() = resolveReducedMotion(...) \|\| (pauseAnimationsInBackground && !documentHasFocus())`; a focus/visibility monitor (window `focus`/`blur` + `visibilitychange`, reading `document.hasFocus()`, not event payloads — blur fires spuriously on URL-bar/devtools clicks) re-notifies existing subscribers and writes the root attr; update the stale doc comment |
| `web/packages/app/src/styles/app.css` | edit | one rule: `:root[data-animations-paused] .glyph-spinner-cell, … { animation-play-state: paused }` scoped to the infinite selectors (see below) |
| `web/packages/app/tests/ui-settings.test.ts` + a reduced-motion test | edit | heal case + resolver cases with injected focus state |

## 1. What pauses (the honest scope)

- **CSS infinite loops** (the only persistent animations):
  `rb-gspin` 750ms (`.glyph-spinner-cell`/`.mark-loader-cell`/`.roboco-loader-cell`,
  app.css:2102/:2212/:2239/:2284), `rb-roboco-pulse` 2400ms,
  `rb-skeleton-pulse` (:5694/:5708/:5715/:6491/:6576),
  `rb-att-sending-scrim` (:6523), `rb-tool-shimmer` 3400ms (:10303),
  `skeleton-pulse` 1.6s (:11849/:13245).
- **JS pumps** already gate on `effectiveReducedMotion()`
  (chat-page.tsx:1464, tool-motion.ts) — folding the focus arm into that
  one function inherits the whole JS surface for free, mid-flight snap
  semantics included ("a flip mid-flight makes the current frame the last").

Use `animation-play-state: paused` — NOT `animation: none` and NOT reusing
`data-reduced-motion="on"` (that snaps spinner cells to their dim rest
state, app.css:2157-2161 — a visible jump on refocus — and the
`@media (prefers-reduced-motion)` blocks wouldn't fire anyway). Paused
holds loops mid-phase and resumes — which matches the row copy better than
the desktop's snap-to-rest.

## 2. Explicitly out of scope

One-shot entrances/exits (menus/dialogs/history rows — pausing mid-flight
leaves them half-open); the streaming veil (`rb-veil`, app.css:10175 —
content-arrival); the caret blink (only runs while focused); the new-thread
background canvas effects (rAF-driven, needs its own audit); chat-list's
WAAPI resort glide (reads the raw media query, chat-list.tsx:158,
user-interaction only); the 15s `useNow` clock and queue renew interval
(not animations).

## 3. Tests

- `ui-settings.test.ts`: `storedWith({ pauseAnimationsInBackground: true })`
  stays true; junk/missing → false (the `githubStarBannerDismissed` block
  pattern, :213-218).
- The resolver: with the setting on and injected `document.hasFocus()`
  false → effective true; focused → unchanged; setting off → never.

## 4. Acceptance checklist

- [ ] Row renders after Reduce motion, writes the setting immediately
- [ ] Unfocused document pauses the infinite loops; refocus resumes mid-phase
- [ ] reduced-motion.ts doc comment updated (decision reversal recorded)
- [ ] Out-of-scope list respected
- [ ] Tests + full app suite green
