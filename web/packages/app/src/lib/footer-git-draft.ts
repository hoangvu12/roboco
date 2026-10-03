import { useEffect, useState, type Dispatch, type SetStateAction } from "react";
import type { RepoRef } from "@roboco/proto";

/**
 * The composer footer's draft GIT state — the web peer of the picker
 * state the desktop's `pickers.rs` carries for the git chips
 * (`config.branch`, `config.checkout`, `self.refs`): the picked branch
 * for the next session, the checkout kind it will run under, and the
 * loaded ref rows (lifted so the checkout chip can read "Current
 * worktree" off the picked ref, `checkout_label`, pickers.rs:1280-1304).
 *
 * The chips that render it live in `components/composer-footer.tsx`;
 * this module owns the transitions so the draft footer and the
 * new-thread canvas share the exact `pickers.rs` port.
 */

/**
 * The checkout-kind pair shared by the footer's draft row and the
 * canvas's git selectors (`CheckoutKind`, pickers.rs:112-118 — Local is
 * the default).
 */
export type CheckoutKind = "local" | "newWorktree";

/** The draft git picks for one target (branch + checkout kind + ref rows). */
export interface DraftGitState {
  /** `config.branch` — the picked branch, or null (current branch takes over). */
  readonly branch: string | null;
  /** `config.checkout` — defaults to Local (`CheckoutKind::default()`). */
  readonly checkout: CheckoutKind;
  /** `self.refs` — the loaded ListRefs rows for the target's space. */
  readonly refs: readonly RepoRef[];
}

/** The invalidation baseline: no pick, Local checkout, no rows (pickers.rs:712-736). */
export function emptyDraftGitState(): DraftGitState {
  return { branch: null, checkout: "local", refs: [] };
}

/** The ref row is materialized as a linked worktree. */
function hasWorktree(row: RepoRef): boolean {
  return row.worktreePath !== null && row.worktreePath !== undefined;
}

/**
 * `pick_ref`'s recording half (pickers.rs:1588-1610): the branch the pick
 * sets, plus the one checkout-kind reset the desktop carries — reusing a
 * ref's existing worktree flips the kind to Local so the chip pair reads
 * "Current worktree" + the bare name (:1599-1604). A plain ref under
 * NewWorktree and the already-current ref record the branch only.
 *
 * The RPC half (Local + a plain non-current ref checks out the space
 * folder first) stays chip-side — `SwitchRef` runs before this records.
 */
export function applyRefPick(row: RepoRef, current: DraftGitState): DraftGitState {
  if (hasWorktree(row)) {
    return { ...current, branch: row.name, checkout: "local" };
  }
  return { ...current, branch: row.name };
}

/**
 * `pick_checkout` (pickers.rs:1359-1373): back to Local from NewWorktree
 * with a non-current plain ref picked drops the pick — the current branch
 * takes over (we don't checkout the main folder). Any other kind change
 * just records the kind.
 */
export function applyCheckoutPick(kind: CheckoutKind, current: DraftGitState): DraftGitState {
  const picked = current.branch;
  const pickedHasWorktree =
    picked !== null && current.refs.some((row) => row.name === picked && hasWorktree(row));
  const pickedIsCurrent =
    picked !== null && current.refs.some((row) => row.name === picked && row.current);
  if (
    kind === "local" &&
    current.checkout === "newWorktree" &&
    !pickedHasWorktree &&
    picked !== null &&
    !pickedIsCurrent
  ) {
    return { ...current, branch: null, checkout: kind };
  }
  return { ...current, checkout: kind };
}

/**
 * `useDraftGitState` — the draft git picks plus the desktop's
 * space/device invalidation (pickers.rs:700-737, the `space_owner`/
 * `device_owner` observer): when the owner key changes (the canvas's
 * space + target device, the draft footer's chat space), the pick, the
 * checkout kind, and the loaded refs all reset — the folder (and
 * possibly the device) changed under them.
 *
 * The owner key replaces the desktop's generation counter: the chips
 * re-key the popover on the same string, so a switch ALSO remounts the
 * ref chip's own state (rows, switching, switch error) and drops the
 * in-flight load's consumer.
 */
export function useDraftGitState(ownerKey: string): [DraftGitState, Dispatch<SetStateAction<DraftGitState>>] {
  const [state, setState] = useState<DraftGitState>(emptyDraftGitState);

  // The invalidation itself — a no-op on mount (the draft starts empty),
  // the full reset on every key change.
  useEffect(() => {
    setState(emptyDraftGitState());
  }, [ownerKey]);

  return [state, setState];
}

/**
 * The effective ref's name (`effective_ref_name`, pickers.rs:2175-2180):
 * the draft pick, else the space's current branch row — the value the
 * chips label themselves with before any interaction. The chat's stamped
 * branch (the footer's established-chat analogue) sits between them.
 */
function effectiveRefName(draft: DraftGitState, chatBranch: string | null): string | null {
  const currentRow = draft.refs.find((row) => row.current)?.name ?? null;
  return draft.branch ?? chatBranch ?? currentRow;
}

/**
 * `selected_ref_worktree` (pickers.rs:2182-2184): the effective ref's
 * existing worktree, if any — the checkout label's "Current worktree".
 */
export function effectiveRefWorktree(draft: DraftGitState, chatBranch: string | null): string | null {
  const name = effectiveRefName(draft, chatBranch);
  if (name === null) {
    return null;
  }
  return draft.refs.find((row) => row.name === name && hasWorktree(row))?.worktreePath ?? null;
}
