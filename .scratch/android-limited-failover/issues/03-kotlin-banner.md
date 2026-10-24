# 03 — Kotlin: the Limited banner card, filler and picker

Status: done

Blocked by: 02

## What

`apps/android/app/src/main/java/sh/roboco/android/session/`:

1. `SessionChrome.limited: LimitedChrome?` (default null) — value type:
   the label pieces (`window`, `resetAtMs`, armed `fireAtMs`), the strip
   action availability, the picker rows, `switching`, `notice`,
   `retry` (mirrors the desktop `FailoverState` + `limited_strip_actions`).
2. `LimitedBanner.kt` — the `ChromeFiller` (registry slot after
   `USAGE_CHIP`, before `BANNER`): derives `LimitedChrome` from
   `ChromeInput.composer.limit`; loads candidates (TTL 30 s, re-load on
   every picker open, one retry pill after a failed load); performs the
   switch (`FailoverCore`), arm/cancel, and Resume-now (a send seam the
   screen wires, like `StatusBanner.onEditingTap`); outcome notice line.
   Pure derivations named after the desktop/web twins (`limitedLabel`,
   `limitedActions`, `failoverRows`, `outcomeNotice`, `resetCaption`).
3. `LimitedCore.kt` — the fakeable seam (`candidates`, `switch`, `arm`,
   `cancel`) + `CoreLimitedCore` over the FFI methods (IO dispatcher).
4. `LimitedBannerCard.kt` — the UI: amber glass card (dot + label, action
   pills, notice line) as a new bottom-stack slot between the pill row
   and the queue; the picker is a glass `Menu` anchored to the Switch
   pill (candidates as rows with usage/reset subtitles, "Recommended"
   first, signed-out rows informational; re-ranked on every open through
   the uncached deferred provider).
5. `BottomStackModel`/`SessionBottomStack`/`SessionScreen`: the new slot,
   `limitedShown`, `isStructural`; `RobocoApplication` registers the
   filler; the screen wires the send seam and the toaster.

## Definition of done

`./gradlew :app:compileDebugKotlin` and `:app:testDebugUnitTest` green
(new tests in ticket 04).

## Comments

- Implemented as specced: `SessionChrome.limited` (`LimitedChrome`),
  `LimitedBanner.kt` (filler + the pure derivations named after the
  desktop/web twins), `LimitedCore.kt` (seam + `CoreLimitedCore`),
  `LimitedBannerCard.kt` (the amber glass card + the glass-menu picker),
  the `Limited` bottom-stack slot (model/tag/metrics/container), the
  screen wiring (send seam through the composer binding's normal send)
  and the `RobocoApplication` registration (slot 25).
- No JDK/SDK in this environment — the Gradle compile runs in CI
  (`android.yml`); every referenced API was hand-verified against the
  sources (uiStyle/GlassSurface/GlassMenuState/MenuItem signatures, the
  uniffi record field names, the JNA-free test seams: `continuePrompt`
  and `logW` are injectable like `StatusBanner`'s).

## Comments (review round)

Two-axis code-review fixes (standards + spec):
- the Retry pill now re-asks for the candidates (the desktop's
  `retry_failover`) instead of being inert;
- `maybeLoad` gates the FIRST load on the switchable harness (the
  desktop's `switchable`), is `@Synchronized` (the fill lane vs main),
  and a failed load drops the stale list (the desktop clears
  `failover.loaded` on Err);
- a chat-id change resets the per-chat state (chat-scoping, defensive
  on a per-route filler);
- the Switch pill stays while a switch runs (the card no-ops the tap),
  matching the desktop's greyed rows;
- arm/cancel failures toast (the desktop's Mutate-error toast), never
  the failover notice line;
- signed-out picker rows attach no switch at all.
