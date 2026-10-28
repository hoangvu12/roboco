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

**Status:** done (2026-10-28, `wave10/android`) — Kotlin compiles in CI only

**Source:** wave-9 `issues/20-android-model-picker.md`;
`pass2-features-android.md` KNOWN table.

- [x] The picker opens from the model chip with per-harness tabs and search (JVM; no favourites upstream — see comments; no golden flow on CI)
- [x] Picking a model of the current harness applies it
- [x] Commit records `cc7da5c2a` (iOS picker slice); absorbs wave-9 20 with 39

## Comments

2026-10-28 (wave10/android, lane 3): carried `cc7da5c2a` (#966)
`Session/ModelPickerViewController.swift` by intent.

- `composer/ModelPicker.kt`: a `GlassSheet` (Medium/Large, grabber) with
  the left-aligned "Model" over "<agent> · <computer>" (one provider) or
  the computer, a glass close; with several providers the capsule
  switcher (brand mark + name, "Claude" for Claude Code, scrolls when
  long) over a `HorizontalPager` of pages, the chat's provider first and
  loaded first; rows straight on the sheet, the current one in the
  accent-soft plate with the accent check; static loading bars; a page
  notice; a search field past 12 models ("Search <agent> models", "No
  matches" / "No models available"); a pick lands (light haptic) and the
  sheet closes 220 ms later.
- `SessionChipMenus.modelPicker`: the session screen sets it, so the
  model chip opens the picker (built at tap time, no core call; pages
  load on IO through `listModels`, the static fallback on failure); a
  pick on the chat's own provider writes #664's `applyModelPick`. The
  effort chip keeps its menu. 39 adds the other providers' pages.
- Deviation: the ticket lists starred/favourites and a "viewed marker";
  upstream's picker (`cc7da5c2a`, unchanged on `upstream/main`) has
  neither, so neither is built. Loading bars are static (an infinite
  pulse would keep Compose tests from idling).
- Tests: `ModelPickerRulesTest` (dedupe, threshold/filter, empty lines,
  header, switcher title, first page, notices), `SessionChipMenusTest`
  (the chip opens the picker with no tap-time core call; the page load
  runs on the io lane; a pick writes the config; effort keeps its menu);
  instrumented `SessionFlowTest.modelPickerInSession` now waits for the
  sheet, picks "Mock 1" and sees it close.
