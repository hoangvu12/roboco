# 04 — Kotlin tests

Status: done

Blocked by: 03

## What

`apps/android/app/src/test/java/sh/roboco/android/session/`:

- `LimitedBannerTest.kt` — the pure derivations: label (window/reset/
  armed/unknown), the strip-actions table (armable, cancellable,
  switchable+offers, retry), rows (recommended marker, signed-out),
  outcome notices, reset captions. Plain JUnit, no Robolectric
  (`Fixtures.kt` builders construct the FFI records — extend them with
  `limit`/`scheduledResumeFireAtMs`).
- `LimitedCoreTest`-style model tests with a fake `FailoverCore`
  (`runTest`, one dispatcher): the TTL load, failed-load retry, the
  switch flow (notice on `Switched`, failure restore), arm/cancel.
- `BottomStackModelTest`: `limitedShown` + the structural rule.

## Definition of done

`./gradlew :app:testDebugUnitTest` green.

## Comments

- `LimitedBannerTest.kt`: the countdown rounding, the label, the
  actions table, the outcome notices, the picker rows/copy, plus the
  filler over a fake `LimitedCore` (TTL, failed-load retry, switch
  notice/guard, resume seam, arm/cancel). `BottomStackModelTest` gains
  the `limitedShown` structural cases. Fixtures: `composerState` grew
  `limit`/`scheduledResumeFireAtMs` params; the four other
  `ComposerState(` construction sites updated.
