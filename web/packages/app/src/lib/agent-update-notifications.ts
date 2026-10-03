import type { HarnessId, HarnessUpdateStatus } from "@roboco/proto";
import type { BannerTexts } from "./notifications";

/**
 * The agent-CLI update banner's decision layer — the pure half of the
 * desktop's `shell/harness_updates.rs` notification arm and
 * `shell.rs:2193-2250`: which update statuses key a notification wave, the
 * seen-set's reset rule, the aggregate banner's texts, and the preference
 * gate the store consults at post time. The watch itself (the shared
 * `agentUpdatesStore`) is the source; this module decides what it earns.
 */

/**
 * `harness_updates::notification_key` — an available-phase status keys one
 * banner wave: `{harness}:{version}`, or the reserved versionless key when
 * the provider answered without a version (the availability still deserves
 * its one ping). Null for every other phase — checks, installs, failures
 * and confirmations never notify.
 */
export function agentUpdateNotificationKey(status: HarnessUpdateStatus): string | null {
  if (status.phase !== "available") {
    return null;
  }
  return status.latestVersion == null
    ? versionlessAgentUpdateKey(status.harness)
    : `${status.harness}:${status.latestVersion}`;
}

/** `versionless_notification_key` — `{harness}:versionless`. */
export function versionlessAgentUpdateKey(harness: HarnessId): string {
  return `${harness}:versionless`;
}

/**
 * The seen-set behind the dedupe — the desktop's `harness_update_seen`
 * shell field, web-shaped. A versioned wave key deduplicates for the whole
 * viewport lifetime; a versionless one re-arms the moment its harness
 * reports a confirmed Current or Updated status (transient checks,
 * disconnections and cancellations never reset it).
 */
export class AgentUpdateWaves {
  readonly #seen = new Set<string>();

  /**
   * The wave a status list currently earns: apply the versionless reset
   * rule (a Current/Updated row re-arms its harness's versionless key),
   * then report the available-phase keys not yet seen.
   */
  unseenKeys(statuses: readonly HarnessUpdateStatus[]): string[] {
    for (const status of statuses) {
      if (status.phase === "current" || status.phase === "updated") {
        this.#seen.delete(versionlessAgentUpdateKey(status.harness));
      }
    }
    const keys: string[] = [];
    for (const status of statuses) {
      const key = agentUpdateNotificationKey(status);
      if (key !== null && !this.#seen.has(key)) {
        keys.push(key);
      }
    }
    return keys;
  }

  /** The debounced deliverer marks its wave seen (once). */
  markSeen(keys: readonly string[]): void {
    for (const key of keys) {
      this.#seen.add(key);
    }
  }
}

/**
 * The aggregate banner's texts (shell.rs:2234-2246, verbatim): discoveries
 * that finish independently debounce into ONE banner counting the wave.
 */
export function agentUpdateBannerTexts(count: number): BannerTexts {
  return {
    title: `${count} agent update${count === 1 ? "" : "s"} available`,
    body: count === 1 ? "A coding agent update is ready" : "Coding agent updates are ready",
  };
}

/** The gate the store consults at post time: the banner master, the
 *  agent-updates row, and the background-only focus rule. */
export function agentUpdateBannerEnabled(
  settings: {
    readonly notificationsEnabled: boolean;
    readonly notificationsBackgroundOnly: boolean;
    readonly agentUpdateNotifications: boolean;
  },
  appFocused: boolean,
): boolean {
  return (
    settings.notificationsEnabled &&
    settings.agentUpdateNotifications &&
    !(settings.notificationsBackgroundOnly && appFocused)
  );
}
