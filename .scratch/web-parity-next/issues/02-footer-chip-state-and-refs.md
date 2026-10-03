# 02 — Footer git chips reset and self-populate on target switch

**What to build:** Three verified gaps in the composer footer's git/target
chips (the new-thread canvas selectors and the draft footer):

1. **Draft git state never invalidates on space/device switch.** Desktop
   (pickers.rs:700-737): an observer compares `selected_space`/
   `effective_device_id` against cached owners; on change it bumps a
   generation, cancels in-flight loads, clears the branch pick, resets the
   checkout kind, sets refs Idle, and resets catalogs. Web keeps
   `draftBranch`/`checkout`/`refs` in bare `useState` with no reset
   (new-thread-selectors.tsx:201-203; composer-footer.tsx:107-110) —
   switching projects leaves a stale "From {oldref}" label, stale worktree
   detection, and (a real bug) stale rows that **block the new repo's refs
   load** (the `rows.length > 0 && !force` guard, composer-footer.tsx:650-653).
2. **Worktree-reuse pick doesn't reset the checkout kind.** Desktop sets
   branch AND `checkout = Local` when a ref has an existing worktree
   (pickers.rs:1599-1604), so the chip reads "Current worktree". Web's
   `pickRef` records only the name (composer-footer.tsx:685-696) — the chip
   keeps reading "New worktree" and the ref chip shows "From {name}".
3. **Lazy refs leave the chips at "Select ref".** Desktop
   `ensure_refs(false)` runs every render but loads only from Idle or on
   space mismatch (pickers.rs:1524-1567, one LIST_REFS per space), and
   `selected_ref()` falls back to the repo's current branch
   (:2160-2167) so labels show real values pre-interaction, with the cursor
   anchored on the current row (:2144-2158). Web loads only on popover open
   (composer-footer.tsx:672-677) and anchors the cursor at row 0
   (:806-810).

**After this ticket:** switching the canvas target resets the git draft
(branch, checkout kind, refs, per-ref switch state); picking a worktree ref
flips the checkout kind; the ref chip eager-loads once per space and shows
the current branch with the cursor anchored on it.

**Blocked by:** None.

**Status:** ready-for-human

**Research:** `.scratch/web-parity-next/research.md` §2.

**Desktop reference (for lookups only):**
`crates/ui/src/pickers.rs:700-737` (invalidation), `:1524-1567`
(ensure_refs cadence), `:1599-1604` (worktree-reuse reset), `:2160-2183`
(selected_ref fallback + labels), `:2144-2158` (cursor anchor).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/composer/new-thread-selectors.tsx` | edit | reset effect keyed on `space?.id` + `target.targetDeviceId`; re-key `<RefChip key={space:device}>` to clear its internal state; pass `autoLoad` + current-branch to RefChip |
| `web/packages/app/src/components/composer-footer.tsx` | edit | mirror the reset for the draft footer keyed on `chat.spaceId`; `applyRefPick` reset; `autoLoad` prop on RefChip; cursor anchors on the current row; widen `onPick` to pass the row |
| `web/packages/app/src/lib/` (new `footer-git-draft.ts` or fold into composer-draft.ts) | new/edit | pure `applyRefPick(row, current)` — the pick_ref port |
| `web/packages/app/tests/` | new | the pure pick/reset tests |

## 1. Shape notes

- **No generation counter needed** — React re-keying replaces the desktop's
  cancel+Idle dance (an unmounted RefChip drops its in-flight promise's
  consumer).
- Eager load: one LIST_REFS per canvas/footer mount per space (guard
  `rows.length > 0 && !force`), gated on `space.gitDetected`
  (new-thread-selectors.tsx:205-207). The load-refactor also fixes the
  stale-rows-block-load bug above.
- The canvas git picks still stay chip-local (the send payload is the prior
  spec's recorded out-of-scope item) — this ticket only fixes label/state
  truth.

## 2. Tests

Pure `applyRefPick`: worktree row → branch set + checkout local; plain ref
in newWorktree mode → branch set, checkout unchanged; current branch →
name-only. Reset: a `useDraftGitState`-shaped hook (if extracted) or the
jsdom idiom of `tests/picker-card-phone.test.ts:1-60` — switch the space
key → branch/checkout/refs reset, RefChip remounts.

## 3. Acceptance checklist

- [x] Space/device switch resets branch, checkout kind, refs, switch state
- [x] Worktree-reuse pick flips the chip to "Current worktree"
- [x] Refs eager-load once per space; chips show the current branch; cursor
      anchors on it
- [x] Stale rows no longer block the new space's load
- [x] Tests + full app suite green

## Comments

**Implemented and reviewed** (branch `ticket/wpn-02-footer-chip-state-and-refs`, commit `61f74eae` → review fixes on top):

- New `web/packages/app/src/lib/footer-git-draft.ts` owns the draft git state (`DraftGitState`: branch, checkout kind, refs): `useDraftGitState(ownerKey)` resets the draft when the owner key changes (the pickers.rs:700-737 invalidation collapsed onto one key), pure `applyRefPick` is the pick_ref recording port (worktree row → branch + Local, :1599-1604; plain/current → branch only), `applyCheckoutPick` carries pick_checkout's drop rule (:1359-1373), and `effectiveRefName`/`effectiveRefWorktree` are the selected_ref/selected_ref_worktree fallbacks (:2160-2184). `CheckoutKind` moved here and re-exports from composer-footer.
- Both parents wire it: the canvas keys on `space.id + targetDeviceId` and the draft footer on `chat.spaceId` (the ticket's file table), each re-keying `<RefChip>` on the same string so the chip's rows/switching state fall with the reset and a late resolution of the old mount's in-flight load is dropped (the cancel, :721-722). `onPick` passes the row; `RefChip.autoLoad` runs one ListRefs per space (gated on `gitDetected`; Error still waits for the open's force, :1535-1542; the in-flight latch is the refs_task analog, :1531-1533).
- Verification: `tests/footer-git-draft.test.ts` 8/8 (pure pick/reset rules + the hook reset), `tests/composer-footer-git.test.ts` 9/9 and `tests/new-thread-git-selectors.test.ts` 3/3 (mounted:eager cadence, current-branch labels, worktree flip, reset through the no-project phase, stale-rows-don't-block, anchor + late-rows re-home, mid-flight switch race, StrictMode single-RPC); `pnpm exec tsc --noEmit` clean; full app suite `pnpm exec vitest run` → **151 files / 2250 tests green**. Every new spec-relevant test was red-verified against the pre-fix code.
- Two-axis code review passed. Review fixes landed on top of 61f74eae: the ref list marks only the draft pick `selected` (the anchor is the cursor — desktop pickers.rs:3633; the current row keeps the "current" tag and highlight, never aria-selected); the cursor re-homes to the anchor row when rows land under an open un-searched popover (:1578-1585) — the acceptance "cursor anchors on it" now holds in the open-before-load path too; the load guard's in-flight latch is a ref so StrictMode's double effects can't race a duplicate eager RPC; inaccurate desktop citations corrected (:1535-1542, :1298-1301) and `emptyDraftGitState` un-exported (internal only).
- Adjudicated, no action: footer keyed on `chat.spaceId` alone — the ticket's file table prescribes it, and the footer's device derives from its space (deviceId immutable at create), so the desktop's device-owner clause is subsumed; the canvas keeps the ticket's space+device key. Deliberate parity choices, documented in code: the switch-path pick records via the same pure `applyRefPick` after SwitchRef succeeds (desktop :1655-1662), and canvas git picks stay chip-local (send payload out of scope per the prior spec).
