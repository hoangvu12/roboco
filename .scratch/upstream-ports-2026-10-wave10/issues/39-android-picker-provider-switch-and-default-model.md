# 39 — Android picker: switch provider between turns; show the default model's name (wave-9 20 gaps, I2)

**Lane:** 3 Android parity. **Effort:** S–M.

**What to build:**
1. **Provider switch in the picker** (three needs wave-9 20 didn't
   spell out):
   - Show other providers' tabs only when the host can switch. Expose
     `HostCapabilities.provider_switch` through the FFI. It's absent from
     `crates/mobile/src/client_ffi/session.rs`.
   - The notes: "You can switch provider when this turn ends.",
     "…needs a newer Roboco to switch provider.", and "Your next message
     continues this chat with X."
   - A pick that changes provider resets effort and options (upstream
     `Session/CoreSessionSource.swift:290-330`). Uses wave-9 10's engine
     provider switch (done in code).
2. **I2: a chat with no explicit model shows the agent name.** The chip
   says "Claude Code" and nothing is ticked. Name and tick the computer's
   default model instead: the built-in list first, replaced by the host's
   `listModels(...).first`, cached per `device|harness`. Files:
   `session/SessionChips.kt:114-122` (label), `composer/SessionChipMenus.kt:306-311`
   (tick), and a small cached default-model lookup (or
   `crates/mobile-client/src/workspace/view.rs:680`). This hits every chat
   started from Android without a model pick (`NewSessionCreate.kt:103`).

**Upstream:** `cc7da5c2a` (#966) iOS slice: `CoreSessionSource.swift:105,290-330,319,352-370`
(behaviour spec).

**Roboco files:** `crates/mobile/src/client_ffi/session.rs`,
`apps/android/.../{session,composer}/*`.

**Excluded:** iOS code.

**Proto change:** no wire type (FFI exposure of an existing capability).

**Blocked by:** 38.

**Status:** done (2026-10-28, `wave10/android`) — Kotlin compiles in CI only

**Source:** `pass2-features-android.md` KNOWN table (ticket 20 gaps);
`pass3-ios-1.md` G2.

- [x] Other providers' tabs appear only with `provider_switch`; the notes show in the right states (JVM tests)
- [~] A cross-provider pick resets effort/options and continues the chat on the new harness (JVM test of the write; no flow test — see comments)
- [x] A default-model chat shows and ticks the model name
- [x] Commit records the `cc7da5c2a` slice and I2

## Comments

2026-10-28 (wave10/android, lane 3): carried `cc7da5c2a` (#966)
`Session/CoreSessionSource.swift:60-85,100,290-370` by intent, plus I2.

- **Capability (small proto change, flagged):** Roboco's proto had no
  `provider-switch-v1` although the engine switches providers since
  wave-9 10. Added upstream's `capabilities::PROVIDER_SWITCH_V1` to
  `CURRENT` (an additive advertised string, no wire type) and
  regenerated `web/packages/proto/src/generated/constants.ts` with
  `cargo run -p wiregen` (one line; the web-removal PR deletes the file).
  `mobile-client` `HostCapabilities.provider_switch` → FFI
  `HostCapabilities.providerSwitch` (`#[uniffi(default = false)]`).
- Picker: other providers' pages only when the host advertises it and it
  can switch now (not mid-turn, no send on its way); the host's offered
  harnesses are read ahead on the fill (once per device, retried after an
  empty answer) so a page never waits; each carries "Your next message
  continues this chat with X."; the own page's note is "You can switch
  provider when this turn ends." mid-turn, or "This computer needs a
  newer Roboco to switch provider." on an older host. A pick on another
  page, re-checked at pick time, writes `applyProviderPick` (harness +
  model, effort and options reset, sandbox kept) — the engine's wave-9 10
  dispatch-time switch takes it from there.
- I2: `SessionChipMenus.fill` (slot 21, after the chips) names a model-less
  chat's chip after its default model — the built-in list's first at once,
  replaced by the host's `listModels().first` (cached per `device|harness`,
  one load per key, a changed answer refreshes) — and the picker ticks it.
- Tests: `SessionChipMenusTest` (switching host offers its other offered
  providers with footers and no note; a Codex pick writes harness + model
  with effort/options reset; mid-turn and older-host notes, no host read
  without the capability, a stale cross pick writes nothing; a model-less
  chat's chip reads "Fable 5.1" and the picker ticks it; the note and pick
  rules); `cargo test -p roboco-mobile-client --lib`
  (`capabilities_map_from_engine_info`), `-p roboco-mobile --lib`.
- Deviation: no instrumented cross-provider flow — the fixture engine
  runs only the mock harness, so there is no second offered provider to
  switch to.
