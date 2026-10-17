import type { EngineClient } from "@roboco/engine-client";
import type { Chat, HarnessDescriptor, HarnessId } from "@roboco/proto";
import { offeredHarnesses } from "./model-rows";

/**
 * The fork dialog's pure model — the web peer of the desktop
 * `crates/ui/src/shell/fork_dialog.rs`'s free functions (tickets 21+22):
 * the offered harness set, the confirm-time override projection, and the
 * spec every entry point hands the dialog (state/fork-dialog.ts owns the
 * state machine; components/fork-dialog.tsx renders it).
 *
 * Both fork entry points — the files panel's classic "Fork this chat"
 * button and the transcript's "Fork from here" hover action — open the
 * dialog with a spec; confirming performs the fork with only the picks
 * that differ from the source's own, so untouched defaults fork exactly
 * as the classic button always did.
 */

/**
 * The harnesses the fork dialog offers (fork_dialog.rs
 * `fork_offered_harnesses`): the composer's offered set — installed and
 * enabled on the listing device — plus the `fallback` harness (the
 * source's own, or the current pick) whenever the catalog carries it: a
 * chat can outlive its harness's enablement, and the default must stay
 * pickable so "fork as-is" is always one click.
 */
export function forkOfferedHarnesses(
  catalog: readonly HarnessDescriptor[],
  fallback: HarnessId | null,
): HarnessDescriptor[] {
  const offered = offeredHarnesses(catalog);
  if (fallback === null || offered.some((descriptor) => descriptor.id === fallback)) {
    return offered;
  }
  const row = catalog.find((descriptor) => descriptor.id === fallback);
  return row === undefined ? offered : [...offered, row];
}

/**
 * The overrides a dialog confirm sends (fork_dialog.rs
 * `confirm_overrides`, ticket 22): only the picks that differ from the
 * source's own. A confirm of the untouched defaults is therefore
 * byte-for-byte the classic fork request; a model pick rides along
 * whenever the harness does (a different harness needs the model spelled
 * out, even an id that happens to match). Blank strings are absent picks.
 */
export function forkConfirmOverrides(
  source: Chat,
  harness: HarnessId | null,
  model: string | null,
): { readonly harness: HarnessId | null; readonly model: string | null } {
  const sourceHarness = source.config?.harness ?? null;
  const sourceModel = source.config?.model ?? null;
  const overrideHarness = harness !== null && harness !== sourceHarness ? harness : null;
  const trimmed = model === null ? "" : model.trim();
  const overrideModel =
    trimmed.length > 0 && (overrideHarness !== null || sourceModel !== trimmed) ? trimmed : null;
  return { harness: overrideHarness, model: overrideModel };
}

/**
 * What a fork dialog spec carries — everything the state machine and the
 * confirm need, resolved once at the entry point:
 *
 * - `source` — the chat being forked (the fleet row the RPC copies);
 * - `parentId` — where the copy hangs: the source, or the source's parent
 *   when a side chat forks itself (the copy lists as a sibling, the
 *   desktop's `ForkFromEntry` rule);
 * - `boundary` — ticket 21's chosen assistant entry id (the copy stops at
 *   it, inclusive), null for the classic latest-reply fork;
 * - `paneChatId` — the right-pane owner the fork's surface opens in;
 * - `client` / `engineBaseUrl` — the source's engine client and its
 *   baseUrl (the reply row is the ENGINE's raw id; the surface and the
 *   fleet rows speak the scoped form).
 */
export interface ForkDialogSpec {
  readonly source: Chat;
  readonly parentId: string;
  readonly boundary: string | null;
  readonly paneChatId: string;
  readonly client: EngineClient;
  readonly engineBaseUrl: string;
}

/**
 * Build a spec at a fork entry point — the one home of the sibling rule
 * (the desktop's `ForkFromEntry` handler): the copy hangs under the
 * source, or under the source's PARENT when a side chat forks itself, so
 * the copy lists as a sibling. `paneChatId` is the right-pane owner the
 * fork's surface opens in; `client`/`engineBaseUrl` are the source's
 * engine session (the reply row is the ENGINE's raw id; the surface and
 * the fleet rows speak the scoped form). Pure.
 */
export function forkSpecFor(
  source: Chat,
  paneChatId: string,
  boundary: string | null,
  client: EngineClient,
  engineBaseUrl: string,
): ForkDialogSpec {
  return {
    source,
    parentId: source.parentChatId ?? source.id,
    boundary,
    paneChatId,
    client,
    engineBaseUrl,
  };
}
