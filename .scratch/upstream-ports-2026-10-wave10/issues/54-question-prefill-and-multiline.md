# 54 — Questions: prefilled and multi-line answers (#630 slice) — PROTO

**Lane:** 4a Features: engine + agents. **Effort:** M.

**What to build:** `UserInputQuestion` gains `prefill` and `multiline`
(upstream `proto/src/agent.rs:710-712`). Without them, Pi `editor`/`input`
dialogs open blank (`crates/harness/src/pi/normalize.rs:387` maps the
editor without its prefill) and multi-line answers get trimmed. Map:
- Pi `editor` → `multiline` + `prefill`; Pi `input` → `prefill`.
- Codex elicitation and ACP where the protocol carries a default.
  Wave-9 06 dropped these fields in `codex/elicitation.rs` "for Roboco's
  UserInputQuestion". Restore them.
- Desktop composer: pre-fill the answer, and allow multi-line
  (Shift+Enter newline) when `multiline`. The wizard draft guards exist
  (`composer.rs:8541`).
- Android question panel: same, prefill + multi-line field.

**Upstream:** `df0cd2980` (#630) question slice; the wave-4 #30 finding
#12 ("not adopted; wave-5 candidate"); the fields wave-9 06 dropped.

**Roboco files:** `crates/proto/src/agent.rs`,
`crates/harness/src/{pi/normalize,codex/elicitation,acp/mod}.rs`,
`crates/ui/src/composer.rs`, `crates/mobile/src/client_ffi/*`,
`apps/android/.../composer/QuestionCard.kt`.

**Excluded:** the Pi native-RPC driver (declined).

**Proto change:** yes (`prefill`, `multiline`, additive).

**Blocked by:** 53 (same question surfaces).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §B smaller partials (Pi prefill/multiline);
`part-05.md` `df0cd2980`.

- [ ] A Pi editor dialog opens with its text; a multi-line answer reaches Pi intact (pi fixture test)
- [ ] A Codex elicitation default pre-fills (codex test)
- [ ] Desktop and Android honour `multiline`
- [ ] Commit records `df0cd2980` (question slice)

## Comments
