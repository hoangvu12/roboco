import { useSyncExternalStore } from "react";
import { encodeScopedId } from "@roboco/engine-client";
import type { HarnessDescriptor, HarnessId, Model } from "@roboco/proto";
import { childChatTitle } from "../lib/explorer-sections";
import {
  catalogTargetDeviceId,
  dialogRows,
  loadDialogHarnesses,
  loadDialogModels,
  type DialogLoad,
} from "../lib/harness-choice";
import {
  forkConfirmOverrides,
  forkOfferedHarnesses,
  type ForkDialogSpec,
} from "../lib/fork-dialog";
import { mintId } from "../lib/id";
import { beginSideChatCreate, endSideChatCreate, forkSideChat } from "../lib/side-chat-actions";
import { rightPaneStore } from "./right-pane";
import { seedPendingSideChat } from "./side-chats";

/**
 * The fork dialog's state machine — the web peer of the desktop's
 * `Shell::fork_dialog` / `shell/fork_dialog.rs` (t3-orchestration-v2
 * tickets 21+22). Both web fork entry points open it instead of forking
 * immediately: the files panel's classic "Fork this chat" button and the
 * transcript's "Fork from here" hover action (the entry points pass a
 * `ForkDialogSpec`; see lib/fork-dialog.ts).
 *
 * The dialog defaults to the source's own harness and model, so confirming
 * without touching anything forks exactly as the classic button always
 * did (the confirm's override projection sends only what differs); picking
 * another harness makes the fork a cross-harness one, which the engine
 * serves with the budgeted history instead of a native session fork.
 *
 * Headless module state, mounted once by `components/fork-dialog.tsx`
 * (the add-space palette's `addSpaceStore` shape): `open()` mints the
 * spec, the loads ride a per-spec staleness guard (an in-flight catalog
 * landing on a closed or reopened dialog no-ops), and `confirm()` performs
 * the fork through the same `ForkSideChat` RPC + seed + right-pane surface
 * the classic button used — the one-at-a-time side-chat guard
 * (`side_chat_creating`) arms around it. A failed fork keeps the dialog
 * open with the reason inline (the classic flow surfaced it in the
 * sidebar's notice strip; a modal owns its own error line).
 */

/** The dialog's snapshot — immutable per commit, like the add-space flow. */
export interface ForkDialogState {
  readonly open: boolean;
  readonly spec: ForkDialogSpec | null;
  /**
   * The harness the fork will run on. Null until a default resolves — the
   * source's own config first, the first offered harness when the source
   * carries no config. Null at confirm time means "no override": the fork
   * keeps the source's harness and its native fork behaviour.
   */
  readonly harness: HarnessId | null;
  /** The model the fork will run on; null = the harness's default at run time. */
  readonly model: string | null;
  /** The source device's harness catalog, loaded once per dialog. */
  readonly harnesses: DialogLoad<HarnessDescriptor>;
  /** Model lists per harness, loaded once per dialog per harness. */
  readonly models: ReadonlyMap<HarnessId, DialogLoad<Model>>;
  /** True while the confirm's fork RPC is in flight. */
  readonly creating: boolean;
  /** The footer's error line — a failed fork, live until the next try. */
  readonly error: string | null;
}

const CLOSED: ForkDialogState = {
  open: false,
  spec: null,
  harness: null,
  model: null,
  harnesses: "idle",
  models: new Map(),
  creating: false,
  error: null,
};

export class ForkDialogStore {
  #state: ForkDialogState = CLOSED;
  readonly #listeners = new Set<() => void>();

  getSnapshot(): ForkDialogState {
    return this.#state;
  }

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }

  /**
   * Open the dialog for `spec` (the desktop's `open_fork_dialog`): the
   * selection starts as the source's own harness and model — spec story 61
   * — and the catalog load kicks immediately. A confirm already in flight
   * refuses the reopen (the desktop's `side_chat_creating` guard on open).
   */
  open(spec: ForkDialogSpec): void {
    if (this.#state.creating) {
      return;
    }
    this.#state = {
      open: true,
      spec,
      harness: spec.source.config?.harness ?? null,
      model: spec.source.config?.model ?? null,
      harnesses: "loading",
      models: new Map(),
      creating: false,
      error: null,
    };
    this.#commit();
    this.#ensureHarnesses(spec);
    const harness = this.#state.harness;
    if (harness !== null) {
      this.#ensureModels(spec, harness);
    }
  }

  /** Close without forking (Cancel, scrim, Escape). */
  close(): void {
    if (!this.#state.open) {
      return;
    }
    this.#state = { ...CLOSED };
    this.#commit();
  }

  /**
   * Pick the fork's harness (the desktop's `pick_fork_harness`): a pick of
   * another harness resets the model (the source's model id is meaningless
   * there; the new harness's own default applies at run time); a pick back
   * onto the source's harness restores it.
   */
  pickHarness(harness: HarnessId): void {
    const { spec, harness: current, model } = this.#state;
    if (spec === null || current === harness) {
      return;
    }
    const sourceHarness = spec.source.config?.harness ?? null;
    const sourceModel = spec.source.config?.model ?? null;
    this.#state = {
      ...this.#state,
      harness,
      model: sourceHarness === harness ? sourceModel : null,
    };
    this.#commit();
    this.#ensureModels(spec, harness);
  }

  /** Pick the fork's model on the selected harness. */
  pickModel(model: string): void {
    if (this.#state.spec === null || this.#state.model === model) {
      return;
    }
    this.#state = { ...this.#state, model };
    this.#commit();
  }

  /**
   * Confirm: hand the dialog's fork — source, parent, boundary and the
   * chosen harness/model — to the `ForkSideChat` RPC flow, then open the
   * copy as a right-pane side-chat surface under the pane the fork started
   * from. Only picks that differ from the source's own ride the wire, so
   * untouched defaults fork byte-for-byte like the classic button. A
   * failed fork keeps the dialog open with the reason inline; a successful
   * one closes it (the surface opening is the feedback).
   */
  confirm(): void {
    const spec = this.#state.spec;
    if (spec === null || this.#state.creating) {
      return;
    }
    const { harness, model } = this.#state;
    if (!beginSideChatCreate()) {
      return;
    }
    this.#state = { ...this.#state, creating: true, error: null };
    this.#commit();
    const overrides = forkConfirmOverrides(spec.source, harness, model);
    void forkSideChat(spec.client, {
      chatId: mintId(),
      sourceChatId: spec.source.id,
      parentChatId: spec.parentId,
      ...(spec.boundary !== null ? { boundary: spec.boundary } : {}),
      ...(overrides.harness !== null ? { harness: overrides.harness } : {}),
      ...(overrides.model !== null ? { model: overrides.model } : {}),
      targetDeviceId: spec.source.deviceId,
    })
      .then((chat) => {
        // The reply is the ENGINE's row (raw id); the surface and the fleet
        // rows speak the scoped form — and the seed (the desktop's
        // `pending_side_chat`) bridges the beat before the registry frame
        // lands the row. The pane the fork started from owns the surface
        // (the desktop's `panel_key`).
        const scoped = encodeScopedId(spec.engineBaseUrl, chat.id);
        seedPendingSideChat({ ...chat, id: scoped });
        rightPaneStore.addSideChatSurface(spec.paneChatId, {
          chatId: scoped,
          title: childChatTitle(chat),
        });
        endSideChatCreate();
        // The dialog may have been closed (and even reopened for another
        // fork) while this one was in flight: only THIS spec's dialog closes
        // — the same staleness guard the catalog loads use.
        if (this.#state.spec !== spec) {
          return;
        }
        this.#state = { ...CLOSED };
        this.#commit();
      })
      .catch((cause: unknown) => {
        endSideChatCreate();
        if (this.#state.spec !== spec) {
          return; // closed or reopened under us: the reason has no dialog
        }
        this.#state = {
          ...this.#state,
          creating: false,
          error: cause instanceof Error ? cause.message : String(cause),
        };
        this.#commit();
      });
  }

  /**
   * Load the harness catalog once per dialog (the desktop's
   * `ensure_fork_harnesses`): the fork runs on the SOURCE's device (the
   * RPC refuses a source hosted elsewhere), so its installed harnesses are
   * the ones that matter. Short-lived — no refresh-on-open, no retry;
   * reopening retries.
   */
  #ensureHarnesses(spec: ForkDialogSpec): void {
    const target = catalogTargetDeviceId(this.#ownDeviceId(spec), spec.source.deviceId);
    void loadDialogHarnesses(spec.client, target)
      .then((rows) => {
        if (this.#state.spec !== spec) {
          return; // closed or reopened under us
        }
        this.#state = { ...this.#state, harnesses: { rows } };
        // A source with no config keeps no default of its own: the first
        // offered harness becomes the pick once the catalog lands, and its
        // model list is next.
        const first = forkOfferedHarnesses(rows, this.#state.harness)[0]?.id ?? null;
        if (this.#state.harness === null && first !== null) {
          this.#state = { ...this.#state, harness: first };
        }
        this.#commit();
        const picked = this.#state.harness;
        if (picked !== null) {
          this.#ensureModels(spec, picked);
        }
      })
      .catch((cause: unknown) => {
        if (this.#state.spec !== spec) {
          return;
        }
        this.#state = {
          ...this.#state,
          harnesses: { error: cause instanceof Error ? cause.message : String(cause) },
        };
        this.#commit();
      });
  }

  /** Load one harness's model list, once per dialog per harness. */
  #ensureModels(spec: ForkDialogSpec, harness: HarnessId): void {
    const existing = this.#state.models.get(harness);
    if (existing !== undefined && existing !== "idle") {
      return;
    }
    const models = new Map(this.#state.models);
    models.set(harness, "loading");
    this.#state = { ...this.#state, models };
    this.#commit();
    const target = catalogTargetDeviceId(this.#ownDeviceId(spec), spec.source.deviceId);
    void loadDialogModels(spec.client, harness, target)
      .then((rows) => {
        if (this.#state.spec !== spec) {
          return;
        }
        const next = new Map(this.#state.models);
        next.set(harness, { rows });
        this.#state = { ...this.#state, models: next };
        this.#commit();
      })
      .catch((cause: unknown) => {
        if (this.#state.spec !== spec) {
          return;
        }
        const next = new Map(this.#state.models);
        next.set(harness, { error: cause instanceof Error ? cause.message : String(cause) });
        this.#state = { ...this.#state, models: next };
        this.#commit();
      });
  }

  /**
   * The connected engine's own device id, scoped — the catalog target's
   * comparison base (the desktop's `fork_catalog_target`). Null when the
   * engine never published its device, so the chat's device is the target.
   */
  #ownDeviceId(spec: ForkDialogSpec): string | null {
    const raw = spec.client.engineInfo?.deviceId ?? null;
    return raw === null ? null : encodeScopedId(spec.engineBaseUrl, raw);
  }

  #commit(): void {
    for (const listener of [...this.#listeners]) {
      listener();
    }
  }
}

/**
 * The fork dialog's module-level singleton — the add-space palette's
 * `addSpaceStore` shape. `open()` is the hook both entry points call.
 */
export const forkDialogStore = new ForkDialogStore();

const subscribe = (listener: () => void) => forkDialogStore.subscribe(listener);
const getSnapshot = () => forkDialogStore.getSnapshot();

/** The dialog's live state for the mounted layer. */
export function useForkDialog(): ForkDialogState {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}

/** The harnesses the open dialog offers (its offered-list projection). */
export function forkDialogOffered(state: ForkDialogState): readonly HarnessDescriptor[] {
  const spec = state.spec;
  if (spec === null) {
    return [];
  }
  return forkOfferedHarnesses(
    dialogRows(state.harnesses),
    state.harness ?? spec.source.config?.harness ?? null,
  );
}
