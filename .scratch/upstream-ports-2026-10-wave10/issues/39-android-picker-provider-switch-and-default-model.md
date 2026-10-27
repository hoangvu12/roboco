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

**Status:** ready-for-agent

**Source:** `pass2-features-android.md` KNOWN table (ticket 20 gaps);
`pass3-ios-1.md` G2.

- [ ] Other providers' tabs appear only with `provider_switch`; the notes show in the right states (JVM tests)
- [ ] A cross-provider pick resets effort/options and continues the chat on the new harness (flow test)
- [ ] A default-model chat shows and ticks the model name
- [ ] Commit records the `cc7da5c2a` slice and I2

## Comments
