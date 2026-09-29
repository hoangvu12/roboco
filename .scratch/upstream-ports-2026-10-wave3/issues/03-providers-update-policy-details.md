# 03 — Providers: update policy in expanded details, stable chevron

**What to build:** The Providers (harnesses) settings page shows each
provider's update policy inside the expanded details row, and the expander
chevron stops moving when the row expands (stable chevron).

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `9d3cc8b2` — `crates/ui/src/settings/harnesses.rs`
(139+/149-). Upstream's page shape predates the settings redesign; carry the
intent into Roboco's wave-2 settings grammar (floating modal, glass
controls, contained dropdowns from tickets 22-24). Resolve by intent, never
by replacing the Roboco file. Web parity: extend
`web/packages/app/src/routes/settings-agents.tsx` expanded rows to match
(no new wire types expected — reuse existing descriptor fields).

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted ui
nextest for harnesses settings; `pnpm --filter @roboco/app exec tsc
--noEmit` + touched web vitest suites.

- [ ] Update policy visible in expanded details (desktop + web)
- [ ] Stable chevron
- [ ] UI + web tests green
- [ ] Port commit records upstream SHA

## Comments

**RESEQUENCED (blocked on #389, not independent):** implementer 03 stopped pre-code per the ticket's stop rule — upstream 9d3cb2b2 is a pure rearrangement of UI that 35a9139a (ticket 18) introduced six hours earlier (verified: 35a9139a is 9d3cb2b2's only harnesses.rs predecessor in the window; Roboco lacks the whole subsystem: proto HarnessUpdatePolicy/Status, WATCH/CHECK/APPLY/CANCEL/DISMISS/SET_POLICY RPCs, engine harness_updates.rs, expandable rows). **Folded into ticket 18** — its checklist now lands 9d3cb2b2's end-state. No code, no commit. Worktree removed.
