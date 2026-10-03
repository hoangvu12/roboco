import { useEffect, useSyncExternalStore } from "react";
import { useNavigate } from "@tanstack/react-router";
import type { EngineClient, WatchHandle } from "@roboco/engine-client";
import type { HarnessUpdateStatus } from "@roboco/proto";
import { useEngineSession } from "../state/session-provider";
import { methods } from "@roboco/engine-client";
import { uiSettings } from "../state/ui-settings";
import { AGENT_UPDATES_TARGET, postBanner } from "../lib/notifications";
import {
  AgentUpdateWaves,
  agentUpdateBannerEnabled,
  agentUpdateBannerTexts,
} from "../lib/agent-update-notifications";
import { agentName, showsUpdateNotice } from "../lib/harnesses";

/**
 * The agent-update strip — the web's UpdateStatus-style watch surface for
 * the desktop's Home agent-update card (`shell/harness_updates.rs`).
 *
 * The web sidebar has no Home island, so the compact summary lives here as
 * an advisory strip above the engine's own update strip: one line for the
 * first notice row, clicking opens Settings → Agents (the desktop's
 * AGENT_UPDATES_TARGET behavior) where the full grammar — policy controls,
 * Update/Cancel actions, per-provider rows — already lives. The standing
 * `WatchHarnessUpdates` stream is shared: one subscription per engine
 * client, replayed on attach, re-subscribed by the client after reconnects.
 */

/** `roboco_rpc::WATCH_HARNESS_UPDATES` — the engine's update-status stream. */
const WATCH_HARNESS_UPDATES = methods.WATCH_HARNESS_UPDATES;

/**
 * The strip's label — the desktop card's compact summary (title) for its
 * first notice row: "<agent> <version> available" / "N agent updates" /
 * the phase-tinted fallbacks.
 */
export function agentUpdateStripLabel(
  notices: readonly HarnessUpdateStatus[],
): { label: string; title: string } | null {
  const first = notices.at(0);
  if (first === undefined) {
    return null;
  }
  const name = agentName(first.harness);
  if (notices.length > 1) {
    const allUpdated = notices.every((row) => row.phase === "updated");
    const label = allUpdated
      ? `${notices.length} agents updated`
      : `${notices.length} agent updates`;
    return { label, title: label };
  }
  switch (first.phase) {
    case "available":
      return {
        label:
          first.latestVersion == null
            ? `${name} update available`
            : `${name} ${first.latestVersion} available`,
        title: "Update available",
      };
    case "waiting-for-idle":
      return { label: `${name} · waiting for idle`, title: "Waiting for the current run" };
    case "preparing":
      return { label: `Preparing ${name}…`, title: "Preparing update" };
    case "downloading":
      return { label: `Downloading ${name}…`, title: "Downloading update" };
    case "installing":
      return { label: `Updating ${name}…`, title: "Installing update" };
    case "verifying":
      return { label: `Verifying ${name}…`, title: "Verifying installation" };
    case "updated":
      return { label: `${name} updated`, title: "Agent updated" };
    case "failed":
      return { label: `${name} update check failed`, title: "Agent update check failed" };
    default:
      return null;
  }
}

/**
 * One engine's `WatchHarnessUpdates` stream, shared: the strip subscribes
 * wherever it mounts and the store re-attaches per client. The engine
 * replays the current status list on subscribe, so the strip appears the
 * moment a notice exists.
 *
 * The store is also a NOTIFICATION source (shell.rs:2193-2250, the web peer
 * of the desktop's `on_state_changed` block): an update wave — an
 * available-phase status whose wave key nobody has seen — debounces for a
 * second (discoveries finish independently), then posts ONE aggregate
 * banner through the existing notification path, gated by the master,
 * background-only, and agent-update preferences, deduped by the wave key
 * (versionless keys re-arm on a confirmed Current/Updated — the rules live
 * in `lib/agent-update-notifications`).
 */
class AgentUpdatesStore {
  #statuses: readonly HarnessUpdateStatus[] | null = null;
  #handle: WatchHandle | null = null;
  #bannerTimer: ReturnType<typeof setTimeout> | null = null;
  readonly #listeners = new Set<() => void>();

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }

  getSnapshot(): readonly HarnessUpdateStatus[] | null {
    return this.#statuses;
  }

  attach(client: EngineClient): void {
    if (this.#handle !== null) {
      return;
    }
    this.#handle = client.watch<HarnessUpdateStatus[]>(
      WATCH_HARNESS_UPDATES,
      {},
      {
        onItem: (statuses) => {
          this.#statuses = statuses;
          this.#considerBannerWave();
          for (const listener of this.#listeners) {
            listener();
          }
        },
        onEnd: () => {},
      },
    );
  }

  detach(): void {
    this.#handle?.cancel();
    this.#handle = null;
    if (this.#statuses !== null) {
      this.#statuses = null;
      for (const listener of this.#listeners) {
        listener();
      }
    }
  }

  /**
   * shell.rs:2204-2250: a fresh wave (unseen available keys) with no timer
   * running schedules the one-second aggregate delivery — discoveries that
   * finish independently collapse into ONE banner, and only the debounced
   * read decides what that banner counts. The wave keys are marked seen
   * here (not at detection), so a status landing during the debounce joins
   * the same banner instead of being silently consumed.
   */
  #considerBannerWave(): void {
    if (this.#bannerTimer !== null || this.#statuses === null) {
      return;
    }
    if (waves.unseenKeys(this.#statuses).length === 0) {
      return;
    }
    this.#bannerTimer = setTimeout(() => {
      this.#bannerTimer = null;
      const statuses = this.#statuses;
      if (statuses === null) {
        return;
      }
      const fresh = waves.unseenKeys(statuses);
      if (fresh.length === 0) {
        return;
      }
      waves.markSeen(fresh);
      if (!agentUpdateBannerEnabled(uiSettings.getSnapshot(), document.hasFocus())) {
        return;
      }
      const texts = agentUpdateBannerTexts(fresh.length);
      postBanner(texts.title, texts.body, AGENT_UPDATES_TARGET);
    }, BANNER_DEBOUNCE_MS);
  }
}

export const agentUpdatesStore = new AgentUpdatesStore();

/** The update waves this page load has already bannered (the desktop's
 *  `harness_update_seen` shell field: dedupe for the viewport lifetime,
 *  reset rules in `AgentUpdateWaves`). */
const waves = new AgentUpdateWaves();

/** The wave-debounce window (shell.rs:2211: one aggregate banner per wave). */
const BANNER_DEBOUNCE_MS = 1_000;

/** The strip's read of the engine's update lifecycle, as one value. */
function useAgentUpdates(): readonly HarnessUpdateStatus[] | null {
  const session = useEngineSession();
  useEffect(() => {
    if (session === null) {
      return;
    }
    agentUpdatesStore.attach(session.client);
    return () => agentUpdatesStore.detach();
  }, [session]);
  return useSyncExternalStore(
    (listener) => agentUpdatesStore.subscribe(listener),
    () => agentUpdatesStore.getSnapshot(),
  );
}

export function AgentUpdateStrip() {
  const statuses = useAgentUpdates();
  const navigate = useNavigate();
  if (statuses === null) {
    return null;
  }
  const notices = statuses.filter(showsUpdateNotice);
  const parsed = agentUpdateStripLabel(notices);
  if (parsed === null) {
    return null;
  }
  return (
    <button
      type="button"
      id="agent-update-strip"
      className="update-strip agent-update-strip"
      title="Open Settings → Agents"
      onClick={() => {
        void navigate({ to: "/settings/harnesses" });
      }}
    >
      <span className="update-strip-label">{parsed.label}</span>
    </button>
  );
}
