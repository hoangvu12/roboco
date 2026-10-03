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

**Status:** ready-for-agent

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

- [ ] Space/device switch resets branch, checkout kind, refs, switch state
- [ ] Worktree-reuse pick flips the chip to "Current worktree"
- [ ] Refs eager-load once per space; chips show the current branch; cursor
      anchors on it
- [ ] Stale rows no longer block the new space's load
- [ ] Tests + full app suite green
