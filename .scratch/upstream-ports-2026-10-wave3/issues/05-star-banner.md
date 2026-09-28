# 05 — "Star on GitHub" banner that stays dismissed

**What to build:** A dismissible "Star on GitHub" banner in the sidebar; the
dismissal persists across restarts. **Rebrand: the URL and repo reference
point at `https://github.com/hoangvu12/roboco`** (not zeronsh).

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `d92d56a2` — `crates/ui/src/shell.rs` (93+), `crates/ui/src/settings.rs`
(dismissal persistence). Web parity: a dismissible banner component on web
(natural host: beside `update-strip.tsx` or the `sidebar-notice.tsx`
pattern), dismissal persisted in `state/ui-settings.ts`.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted ui
nextest for the banner + dismissal; web vitest for the new banner suite.

- [ ] Banner renders, dismiss persists (desktop)
- [ ] Rebranded to hoangvu12/roboco
- [ ] Web banner + persistence test
- [ ] Port commit records upstream SHA + rebrand note
