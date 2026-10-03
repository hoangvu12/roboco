import { describe, expect, it } from "vitest";
import type { Device } from "@roboco/proto";
import {
  lastSeenOnline,
  formatLastSeen,
  formatLastSeenAt,
  partitionDevices,
  platformLabel,
  presenceDot,
  shortId,
} from "../src/lib/devices";

const NOW = 1_800_000_000_000;

describe("lastSeenOnline (devices.rs:27-30)", () => {
  it("deviceOnlineWithin70SecondsWindow", () => {
    expect(lastSeenOnline(ago(10), NOW)).toBe(true);
    expect(lastSeenOnline(ago(70), NOW)).toBe(true);
    expect(lastSeenOnline(ago(71), NOW)).toBe(false);
    // null → offline, unlike the sidebar's unknown-row-reading variant.
    expect(lastSeenOnline(null, NOW)).toBe(false);
    // Clock skew (future) counts as online.
    expect(lastSeenOnline(ago(-30), NOW)).toBe(true);
  });

  it("rejects unparseable timestamps as offline", () => {
    expect(lastSeenOnline("not-a-date", NOW)).toBe(false);
  });
});

describe("presenceDot (devices.rs:45-52)", () => {
  it("presenceDotFallsBackWhenNoEngineKey", () => {
    // Engine-backed rows report the connection verbatim, whatever the
    // last-seen window says.
    expect(presenceDot("connected", false)).toBe("connected");
    expect(presenceDot("reconnecting", false)).toBe("reconnecting");
    expect(presenceDot("off", true)).toBe("off");
    // Rows with no engine entry keep the last-seen presence window.
    expect(presenceDot(null, true)).toBe("connected");
    expect(presenceDot(null, false)).toBe("off");
  });
});

describe("formatLastSeen (devices.rs:56-70)", () => {
  it("formatLastSeenBucketsMatchRoboco", () => {
    expect(formatLastSeen(null, NOW)).toBe("never seen");
    expect(formatLastSeen(NOW - 30_000, NOW)).toBe("just now");
    expect(formatLastSeen(NOW - 59_000, NOW)).toBe("just now");
    expect(formatLastSeen(NOW - 60_000, NOW)).toBe("1m ago");
    expect(formatLastSeen(NOW - 5 * 60_000, NOW)).toBe("5m ago");
    expect(formatLastSeen(NOW - 3 * 3_600_000, NOW)).toBe("3h ago");
    expect(formatLastSeen(NOW - 2 * 86_400_000, NOW)).toBe("2d ago");
  });

  it("formatLastSeenAt parses the wire's RFC 3339 strings", () => {
    expect(formatLastSeenAt(null, NOW)).toBe("never seen");
    expect(formatLastSeenAt(new Date(NOW - 30_000).toISOString(), NOW)).toBe("just now");
    expect(formatLastSeenAt(new Date(NOW - 5 * 60_000).toISOString(), NOW)).toBe("5m ago");
  });
});

describe("platformLabel (devices.rs:286-296)", () => {
  it("platformLabelMapsKnownPlatforms", () => {
    expect(platformLabel("macos")).toBe("macOS");
    expect(platformLabel("darwin")).toBe("macOS");
    expect(platformLabel("linux")).toBe("Linux");
    expect(platformLabel("windows")).toBe("Windows");
    expect(platformLabel("web")).toBe("Web");
    expect(platformLabel("ios")).toBe("iOS");
    expect(platformLabel("android")).toBe("Android");
    // Unknown platforms pass through verbatim.
    expect(platformLabel("haiku")).toBe("haiku");
  });
});

describe("shortId (devices.rs:299-305)", () => {
  it("shortIdTruncatesLongIds", () => {
    expect(shortId("dev-1234")).toBe("dev-1234");
    expect(shortId("0123456789ab")).toBe("0123456789ab");
    expect(shortId("0123456789abc")).toBe("01234567…9abc");
    expect(shortId("0123456789abcdef")).toBe("01234567…cdef");
  });
});

describe("partitionDevices (devices.rs:372-375, 517-557)", () => {
  const laptop = device("dev-a", "Vu's Studio");
  const phone = device("dev-b", "Vu's Phone");
  const tablet = device("dev-c", "Vu's Tablet");

  it("splits rows around the local device — local alone in the first section, the rest in order", () => {
    // The desktop's `partition(|(_, device)| local_id == Some(device.id))`:
    // exactly the engine's own device in `local`, everything else in
    // `others`, each keeping the registry's order.
    expect(partitionDevices([phone, laptop, tablet], "dev-a")).toEqual({
      kind: "known",
      local: [laptop],
      others: [phone, tablet],
    });
  });

  it("an unknown local device id leaves every row in the others section", () => {
    // engineInfo loaded but the engine's own device not in the registry:
    // the desktop's `.when_some(local_block, …)` hides the empty "This
    // device" section — the page keys off the empty `local` array.
    expect(partitionDevices([phone, tablet], "dev-z")).toEqual({
      kind: "known",
      local: [],
      others: [phone, tablet],
    });
  });

  it("a null localDeviceId routes to the fallback arm — the flat list until known", () => {
    // engineInfo has not loaded yet; the page keeps the flat single card
    // (the web-only unknown arm, made testable here).
    expect(partitionDevices([laptop, phone], null)).toEqual({
      kind: "unknown",
      rows: [laptop, phone],
    });
  });

  it("empty others is the empty-state flag the page reads", () => {
    // `others: []` is what renders "Pair another device to see it here."
    // (devices.rs:525-536) — pinned so the flag stays observable.
    expect(partitionDevices([laptop], "dev-a")).toEqual({
      kind: "known",
      local: [laptop],
      others: [],
    });
  });
});

function device(id: string, name: string): Device {
  return { id, name, platform: "macos", lastSeenAt: null, createdAt: null };
}

function ago(seconds: number): string {
  return new Date(NOW - seconds * 1000).toISOString();
}
