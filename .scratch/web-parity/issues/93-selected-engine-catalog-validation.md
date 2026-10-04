# 93 — Validate harness/model on selected engine

**What to build:** Port zeron `631a8e03` / `6e4f363`: before fresh send, harness/model/agent availability checked on **resolved target engine**, not only `useEngineSession()` connected state.

**Blocked by:** **86**, **90**

**Status:** ready-for-agent

**Zeron ref:** commits on PR #526 after `4928e1b2`

**Acceptance:** Pick engine B + model only offered on B; friendly error if catalog missing on owner.
