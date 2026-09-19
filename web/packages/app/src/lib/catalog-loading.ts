import type { LoadableList } from "../state/picker-catalog";

/**
 * The chip's loading classification — ports of the desktop's
 * `catalog_loading` / `models_loading` / `ensure_harnesses` reload table
 * (pickers.rs:4207-4221, 1037-1041).
 *
 * Loading is **Idle/Loading-only, never Error**: `LoadableList.loaded` is
 * only set by a successful fetch, so `!loaded` alone spans the Error slot
 * too — the eternal-spinner bug. These predicates treat an errored slot
 * as settled: the chip then renders its real label (remembered label,
 * else the configured/raw model id, `model_label` at pickers.rs:4186-4206)
 * and the failure surfaces through the card's ErrorRow instead.
 */

/** `catalog_loading` (pickers.rs:4207): the slot is Idle or Loading. */
export function catalogLoading(slot: LoadableList<unknown>): boolean {
  return !slot.loaded && slot.error === null;
}

/**
 * `models_loading` (pickers.rs:4208-4213): the harness's model slot is
 * neither Ready nor Error — absent (never requested), Idle, or Loading.
 * An errored slot reads as settled, exactly like a loaded one.
 */
export function modelsLoading(slot: LoadableList<unknown> | undefined): boolean {
  if (slot === undefined) {
    return true;
  }
  return !slot.loaded && slot.error === null;
}

/**
 * `shouldReload` — `ensure_harnesses`'s Idle/Loading/Ready|Error+force
 * table (pickers.rs:1037-1041), the discipline every non-forced re-kick
 * routes through (the composer's per-commit cadence, a window-focus
 * re-arm, the status heal). Non-forced loads fire from Idle only — an
 * Error that could re-trigger from the render loop would flip back to
 * Loading before the retry row ever painted; Loading never re-fires (the
 * in-flight guard stands behind it); Ready and Error reload only when
 * forced (stale-while-revalidate, the retry row, the card-open force).
 *
 * A failed forced revalidation keeps its stale rows (`loaded` stays set),
 * so it lands on the same force-only row as a Ready slot.
 */
export function shouldReload(slot: LoadableList<unknown>, force: boolean): boolean {
  if (slot.loading) {
    return false;
  }
  if (catalogLoading(slot)) {
    return true;
  }
  return force;
}
