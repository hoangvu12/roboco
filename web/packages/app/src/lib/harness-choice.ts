import { methods } from "@roboco/engine-client";
import type { HarnessDescriptor, HarnessId, Model } from "@roboco/proto";

/**
 * Harness/model choice for the dialog-shaped picks — the web peer of the
 * desktop's two orchestration-v2 dialogs (`shell/fork_dialog.rs`, tickets
 * 21+22, and `shell/continue_on_harness.rs`, ticket 25): each loads the
 * SOURCE DEVICE's harness catalog once per dialog and one model list per
 * picked harness, with a stale-response guard keyed to the dialog instance.
 *
 * This module is the half both dialogs share — the loadable shape, the
 * catalog/model RPCs, and the routing rule for which device's catalog
 * matters. The per-flow pure helpers live beside their owners
 * (`lib/fork-dialog.ts`, `lib/limited.ts`), exactly like the desktop keeps
 * `fork_offered_harnesses` and `continue_offered_harnesses` apart.
 */

/**
 * One dialog slot's load state — the desktop dialogs' `Loadable<Vec<_>>`
 * as a tagged union: idle until kicked, loading while in flight, the rows
 * or the error once it lands.
 */
export type DialogLoad<T> = "idle" | "loading" | { readonly rows: readonly T[] } | { readonly error: string };

/** The rows a load currently shows (empty while idle/loading/errored). */
export function dialogRows<T>(load: DialogLoad<T>): readonly T[] {
  return typeof load === "object" && "rows" in load ? load.rows : [];
}

/** The error a load latched, when it did. */
export function dialogError(load: DialogLoad<unknown>): string | null {
  return typeof load === "object" && "error" in load ? load.error : null;
}

/** True while a slot's first load is still in flight (no rows yet). */
export function dialogLoading(load: DialogLoad<unknown>): boolean {
  return load === "loading";
}

/**
 * The catalog load's `targetDeviceId` (pickers' `fork_catalog_target` /
 * `continue_catalog_target`): the harness catalogs must come from the
 * device that RUNS the chat's agents — its CLIs are the ones that matter —
 * so a chat hosted on another device names it; a locally-hosted chat rides
 * nothing (the connected engine serves it directly). Both arguments are
 * the fleet's SCOPED device ids; null own means the engine never published
 * its device, so the chat's device is the honest target.
 */
export function catalogTargetDeviceId(
  ownScopedDeviceId: string | null,
  chatDeviceId: string | null,
): string | null {
  if (chatDeviceId === null) {
    return null;
  }
  return ownScopedDeviceId === null || ownScopedDeviceId !== chatDeviceId ? chatDeviceId : null;
}

/**
 * The caller shape the loads need — `EngineClient` satisfies it, and the
 * tests drive a fake (the `lib/limited.ts` wrapper idiom).
 */
export interface HarnessChoiceCaller {
  call<T>(method: string, params?: unknown): Promise<T>;
}

/**
 * One dialog's harness catalog: `ListHarnesses` against the chat's host
 * device. Short-lived by design — loaded once per dialog, no refresh or
 * retry (reopening the dialog retries), mirroring the desktop's
 * `ensure_fork_harnesses` / `ensure_continue_harnesses`.
 */
export async function loadDialogHarnesses(
  caller: HarnessChoiceCaller,
  targetDeviceId: string | null,
): Promise<HarnessDescriptor[]> {
  const rows = await caller.call<HarnessDescriptor[]>(
    methods.LIST_HARNESSES,
    targetDeviceId === null ? {} : { targetDeviceId },
  );
  return Array.isArray(rows) ? rows : [];
}

/**
 * One harness's model list: `ListModels` against the same device, loaded
 * once per dialog per harness (`ensure_fork_models` /
 * `ensure_continue_models`).
 */
export async function loadDialogModels(
  caller: HarnessChoiceCaller,
  harness: HarnessId,
  targetDeviceId: string | null,
): Promise<Model[]> {
  const rows = await caller.call<Model[]>(methods.LIST_MODELS, {
    harness,
    ...(targetDeviceId !== null ? { targetDeviceId } : {}),
  });
  return Array.isArray(rows) ? rows : [];
}

