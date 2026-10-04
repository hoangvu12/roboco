import type { IconName } from "@roboco/icons";
import type { Device } from "@roboco/proto";
import { encodeScopedId, parseScopedId, type EngineRegistrySnapshot } from "@roboco/engine-client";
import { DEVICE_ONLINE_WINDOW_SECS } from "./view";

/**
 * Devices-page pure logic — the web peer of `crates/ui/src/settings/devices.rs`'s
 * helpers: the 70s presence window, the corner presence dot, the compact
 * last-seen wording, platform labels, and the click-to-copy short id. The
 * sidebar's `deviceOnline` (`lib/view.ts`, `state.rs::device_online`) is a
 * DIFFERENT function with different None semantics (unknown rows read
 * online there, offline here) — both exist on the desktop too; do not merge.
 * This one is named `lastSeenOnline` (the settings-page semantic: the raw
 * last-seen window over a device row) so the two same-named desktop
 * `device_online` functions never collide at a web call site again.
 */

export { DEVICE_ONLINE_WINDOW_SECS };

/**
 * One host device row per supervised engine — the composer/footer engine picker
 * lists these, not every paired browser client (zeron PR #526 / `5cd23bd7`).
 */
export function fleetDeviceRows(
  registry: EngineRegistrySnapshot,
  projected: readonly Device[],
): readonly Device[] {
  return registry.engines.flatMap((engine) => {
    const hostRaw = engine.info?.deviceId;
    if (hostRaw === null || hostRaw === undefined) {
      return [];
    }
    const ownId = encodeScopedId(engine.key, hostRaw);
    const own = projected.find((device) => device.id === ownId);
    return own === undefined ? [] : [own];
  });
}

/** Drop mirrored/synced project rows that are not owned by their engine host. */
export function fleetHostDeviceIds(registry: EngineRegistrySnapshot): ReadonlySet<string> {
  const ids = new Set<string>();
  for (const engine of registry.engines) {
    const hostRaw = engine.info?.deviceId;
    if (hostRaw !== null && hostRaw !== undefined) {
      ids.add(encodeScopedId(engine.key, hostRaw));
    }
  }
  return ids;
}

/**
 * The engine connection state behind a device row's presence dot
 * (`EngineConnectionState`, verbatim). `null` — the row is not backed by a
 * connection this client holds; the last-seen window decides.
 */
export type EngineConnection = "connected" | "reconnecting" | "off";

/** `PresenceDot` (devices.rs:35-43). */
export type PresenceDot = "connected" | "reconnecting" | "off";

/**
 * `device_online` (devices.rs:27-30), web-named `lastSeenOnline` for the
 * settings-page semantic so it never collides with `lib/view.ts`'s
 * engine-state-aware `deviceOnline` (`state.rs::device_online`): last-seen
 * within the window, future timestamps (clock skew) counting as online; a
 * null/absent last-seen reads offline. `lastSeenAt` is the wire's RFC 3339
 * string.
 */
export function lastSeenOnline(lastSeenAt: string | null | undefined, now: number): boolean {
  if (lastSeenAt === null || lastSeenAt === undefined) {
    return false;
  }
  const at = Date.parse(lastSeenAt);
  if (!Number.isFinite(at)) {
    return false;
  }
  return now - at <= DEVICE_ONLINE_WINDOW_SECS * 1000;
}

/**
 * `presence_dot` (devices.rs:45-52): engine-backed rows report the owning
 * engine's connection verbatim; rows with no engine key fall back to the
 * last-seen window.
 */
export function presenceDot(connection: EngineConnection | null, online: boolean): PresenceDot {
  if (connection !== null) {
    return connection;
  }
  return online ? "connected" : "off";
}

/**
 * `format_last_seen` (devices.rs:56-70), moved here from
 * `lib/remote-access.ts` so the Devices rows and the Remote-access
 * paired-session rows share one wording. `at`/`now` are epoch millis
 * (`PairedSession.lastSeen` is millis).
 */
export function formatLastSeen(at: number | null, now: number): string {
  if (at === null) {
    return "never seen";
  }
  const seconds = Math.floor((now - at) / 1000);
  if (seconds < 60) {
    return "just now";
  }
  if (seconds < 3600) {
    return `${Math.floor(seconds / 60)}m ago`;
  }
  if (seconds < 86_400) {
    return `${Math.floor(seconds / 3600)}h ago`;
  }
  return `${Math.floor(seconds / 86_400)}d ago`;
}

/** `format_last_seen` over the wire's RFC 3339 device timestamps. */
export function formatLastSeenAt(at: string | null | undefined, now: number): string {
  if (at === null || at === undefined) {
    return "never seen";
  }
  const parsed = Date.parse(at);
  return formatLastSeen(Number.isFinite(parsed) ? parsed : null, now);
}

/** `platform_label` (devices.rs:286-296): known platforms, else verbatim. */
export function platformLabel(platform: string): string {
  switch (platform) {
    case "macos":
    case "darwin":
      return "macOS";
    case "linux":
      return "Linux";
    case "windows":
      return "Windows";
    case "web":
      return "Web";
    case "ios":
      return "iOS";
    case "android":
      return "Android";
    default:
      return platform;
  }
}

/** `short_id` (devices.rs:299-305): `abcd1234…wxyz` when longer than 12. */
export function shortId(id: string): string {
  if (id.length > 12) {
    return `${id.slice(0, 8)}…${id.slice(id.length - 4)}`;
  }
  return id;
}

/**
 * The Devices page's two-section split (devices.rs:372-375 partition, blocks
 * assembled :517-557): `local` — rows whose id is the connected engine's own
 * device — render under the "This device" header, `others` under "Other
 * devices". `unknown` is the web-only fallback arm: the engine session's
 * `engineInfo` (hence its `deviceId`) has not loaded yet, so the split is
 * unknowable and the page keeps the flat single card until it is.
 */
export type DevicePartition =
  | { readonly kind: "unknown"; readonly rows: readonly Device[] }
  | { readonly kind: "known"; readonly local: readonly Device[]; readonly others: readonly Device[] };

/**
 * `partition` (devices.rs:372-375): the local device alone in the first
 * section, everything else in the second, each keeping the registry's
 * order. A `localDeviceId` that matches no row (or an empty registry)
 * leaves `local` empty — the page hides the "This device" section then,
 * like the desktop's `.when_some(local_block, …)`. `localDeviceId === null`
 * (engineInfo not loaded) routes to the flat fallback arm instead of
 * guessing every row into "Other devices".
 */
export function partitionDevices(
  rows: readonly Device[],
  localDeviceId: string | null,
): DevicePartition {
  if (localDeviceId === null) {
    return { kind: "unknown", rows };
  }
  const local: Device[] = [];
  const others: Device[] = [];
  for (const device of rows) {
    if (device.id === localDeviceId) {
      local.push(device);
    } else {
      others.push(device);
    }
  }
  return { kind: "known", local, others };
}

/**
 * The platform → tile glyph (devices.rs:348-353): LAPTOP for macos/darwin,
 * GLOBAL for web, SMARTPHONE for ios/android, MONITOR otherwise — the same
 * mapping `add-space-palette.ts` already carries for the same reason.
 */
export function platformGlyph(platform: string): IconName {
  switch (platform) {
    case "macos":
    case "darwin":
      return "laptop";
    case "web":
      return "global";
    case "ios":
    case "android":
      return "smartphone";
    default:
      return "monitor";
  }
}
