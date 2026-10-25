# 20 — Android: tabbed model picker (upstream #966 iOS slice, `cc7da5c2a`)

**What to build:** 1:1 iOS-parity port of the phone's tabbed model picker
(`ModelPickerViewController`, +847): the composer's model chip opens a
tabbed picker over the engine-local model catalog (starred/favorites,
per-harness brand-icon tabs, the viewed marker). Shares proto surface with
ticket 10 (HarnessId helpers) where the picker names harnesses.

**Excluded upstream hunks:** the Cloud view controllers
(AgentAccounts/Billing/Onboarding/CloudRepoPicker), `crates/client`
catalog, wallpaper/toast hunks unrelated to the picker.

**Blocked by:** none (10 helps but is not required).

**Status:** ready-for-agent

**Verification budget:** app unit tests; picker golden.
Source: `.scratch/upstream-drift/2026-10-25.md` § D-a.

- [ ] Tabbed picker ported 1:1 onto the engine-local catalog
- [ ] Commit records upstream SHA `cc7da5c2a` (#966, iOS picker slice)

## Comments
