# 02 — Picker list truth: the refresh-error retry row and the selected-absent row

**What to build:** Two list-content gaps in the model picker, both verified:

1. **Refresh-error retry row.** Desktop renders a retry row *between the
   search row and the list* when rows exist but the refresh failed
   (pickers.rs:4056-4075). The web surfaces `modelSlotError` only when
   `rows.length === 0` (composer-pickers.tsx:1064-1082), and `modelsFor`
   ignores `slot.error` when `loaded` — so stale rows render with **no retry
   affordance**. Worse in the compact arm: `modelsFor` checks
   `slot.error !== null` and returns null (composer-pickers.tsx:1235-1243),
   so **stale rows vanish** on a refresh failure. `listWithError` already
   preserves rows+loaded (picker-catalog.ts:146-149), so the data path is
   ready.
2. **`selected_only` row.** When the chat's chosen model is absent from the
   fresh catalog, the desktop inserts a synthetic row at index 0 of the
   harness tab — unclickable, described "Selected in this chat; absent from
   the current model list" (pickers.rs:1996-2033), activation a no-op
   (:2085-2087). The web's `selectedModel` falls back to `models[0]`
   (composer-pickers.tsx:236-239) — painting the **wrong row** as selected.

**Blocked by:** None.

**Status:** ready-for-human

**Research:** `../research.md` §2 (W-item context);
`.scratch/web-parity-next/research.md` points here.

**Desktop reference (for lookups only):**
`crates/ui/src/pickers.rs:4056-4075` (retry row placement and shape — a
`menu_row` with the refresh icon and the error message, clicking re-forces
the load); `crates/ui/src/pickers.rs:1996-2033` and `:2085-2087`
(`selected_only` row construction and no-op activation).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/composer-pickers.tsx` | edit | insert the retry row between the search row's close (~:1059) and the list host (~:1060); drop the `slot.error !== null` arm from compact `modelsFor` (:1235-1243) and render the same row on the compact models page; guard `activateRow` (:699-706) and `pickModel` against the synthetic row; anchor `selectedModelIndex` at 0 when it renders |
| `web/packages/app/src/lib/model-rows.ts` | edit | new pure `selectedOnlyRow()` helper building the synthetic row |
| `web/packages/app/tests/model-rows.test.ts` | edit | pure tests for `selectedOnlyRow` |
| `web/packages/app/tests/composer-reasoning.test.ts` | edit | mounted tests for both behaviors |

## 1. The retry row

`{modelSlotError !== null && rows.length > 0 && (<ErrorRow message={modelSlotError} onRetry={onRetryModels} />)}`
placed between the search row and the list host — the same `ErrorRow`
component the empty case already uses (composer-pickers.tsx:1064-1082),
wired to the same retry callback. Keep it OUTSIDE the 216px list band so the
virtualizer is untouched (the card grows ~28px while it shows —
desktop-identical). Compact arm: after removing the error arm from
`modelsFor`, add the row at the top of the compact models page.

## 2. The selected-absent row

In `IdentityCard`, when `rail === "harness" && modelsList.loaded &&
draft.model` is absent from the rows, unshift the synthetic row:

```ts
{ harness: effectiveHarness, harnessName, model: {
  id: draft.model,
  label: rememberedLabelFor(draft.model) ?? draft.model,
  description: "Selected in this chat; absent from the current model list",
  reasoningLevels: [], options: [] } }
```

The existing attribution slot renders the description inline for free
(ModelRow, composer-pickers.tsx:1904-1911). `activateRow` and `pickModel`
no-op on it (desktop :2085-2087); `selectedModelIndex` anchors 0. Row key
uniqueness is safe (the id is absent from the catalog by construction).
Suppress the star button on the synthetic row.

## 3. Tests

- `model-rows.test.ts` (pure): `selectedOnlyRow()` builds the row with the
  remembered label fallback, the verbatim description, and empty
  reasoning/options.
- `composer-reasoning.test.ts` (mounted idiom, FakeClient): (a) models list
  fails after a successful load → rows persist + the retry row renders +
  Retry re-forces the fetch (add a fail-next-models hook to the fake);
  compact models page keeps its rows and shows the row; (b) a chat whose
  model is absent from the catalog → row 0 renders with the description,
  clicking it does not pick, the chip label equals the remembered label,
  row 0 is the anchored selected row.

## 4. Acceptance checklist

- [x] Retry row renders between search and list while stale rows persist
- [x] Compact models page no longer drops rows on refresh failure
- [x] `selected_only` row renders, is unclickable, anchors selection
- [x] Star suppressed on the synthetic row
- [x] Both test files green; full app suite green

## Comments

**Branch:** `ticket/mp-02-picker-list-truth` (base `f375fcb0`). Commit
`7bc77bbc` (implementation) + review-pass commit (renames
`selectedAbsent`→`selectedAbsentRow`, extracts the shared `useModelsFor`
hook — the two cards' `modelsFor` drift was exactly this ticket's bug —
and pins the `selected_model` no-fallback ripple). Verification (all from
`web/packages/app`): named seams `tests/model-rows.test.ts` +
`tests/composer-reasoning.test.ts` → 54/54; adjacent picker suites
(picker-catalog, catalog-loading, traits-summary, picker-card-phone,
model-picker-geometry, composer-draft, picker-search) → 99/99; full app
suite `pnpm exec vitest run` → **146 files, 2199 tests, all passed**;
`pnpm exec tsc --noEmit` clean. TDD red→green at both named seams (5
failing first, none pre-existing broken).

**Judgment calls:** the parent `selectedModel` no-fallback rewrite
(`pickers.rs:982-991` mirror — an absent explicit pick stays absent, only
a null `draft.model` follows `models[0]`) is wider than the file table's
bullets but required by §3(b)'s "chip label equals the remembered label";
pinned by the mounted suite (chip label + suffix "High", panel title, no
traits tray/effort slider — desktop `trait_ladder`/`setting_groups` with a
None model are empty, verified pickers.rs:1904-1948; no wrong-model
controls leak). `onPickModel` now takes the row so `pickModel` guards on
`row.selectedOnly` (desktop `activate_model_index` shape). The synthetic
row is query-gated like the desktop block (:2004-2015). Residual,
ticket-scoped: the desktop's compact models page for a LOCKED chat (rail
Harness) also shows the selected_only row — the ticket's file table scopes
the unshift to `IdentityCard`, so the web compact arm does not render it.

**2026-10-03 — residual CLOSED (maintainer: upstream does it, do it too):**
the compact arm now renders the synthetic row — the same `selectedAbsentRow`
memo under the harness rail (locked), unshifted at index 0 with a no-op
activation, pinned by the compact absent-pick test in
composer-reasoning.test.ts.
