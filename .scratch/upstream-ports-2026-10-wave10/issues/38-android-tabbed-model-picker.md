# 38 — Android: tabbed model picker (wave-9 20)

**Lane:** 3 Android parity. **Effort:** M.

**What to build:** Carried from wave-9 ticket 20. It's a 1:1 port of the
phone's tabbed model picker over the engine-local model catalog:
- per-provider swipe pages with a brand-logo switcher;
- starred/favourites, and the viewed marker;
- search once a list is long ("Search <agent> models", "No matches");
- the current-model tint and loading bars.

Today the model chip opens a flat, harness-locked menu
(`composer/SessionChipMenus.kt:242`). This ticket builds the picker
surface. The provider-switch behaviour inside it is ticket 39.

**Upstream:** `cc7da5c2a` (#966) iOS picker slice:
`Session/ModelPickerViewController.swift:19-30,259-450,624-780`
(behaviour spec).

**Roboco files:** `apps/android/.../composer/{SessionChipMenus,…}.kt`,
a new picker composable, `crates/mobile-client` catalog reads, goldens.

**Excluded:** cloud view controllers (AgentAccounts/Billing/Onboarding/
CloudRepoPicker), `crates/client` catalog, wallpaper/toast hunks.

**Proto change:** no.

**Blocked by:** 01.

**Status:** ready-for-agent

**Source:** wave-9 `issues/20-android-model-picker.md`;
`pass2-features-android.md` KNOWN table.

- [ ] The picker opens from the model chip with per-harness tabs, favourites and search (JVM + golden)
- [ ] Picking a model of the current harness applies it
- [ ] Commit records `cc7da5c2a` (iOS picker slice); absorbs wave-9 20 with 39

## Comments
