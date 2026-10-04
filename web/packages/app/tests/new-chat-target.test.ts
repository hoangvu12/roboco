import { describe, expect, it } from "vitest";
import { encodeScopedId } from "@roboco/engine-client";
import {
  resolveNewChatTarget,
  targetForDevicePick,
  targetForProjectPick,
  type NewChatDefaults,
} from "../src/lib/new-chat-target";

const ONE = "https://engine-a.test";
const TWO = "https://engine-b.test";
const deviceOne = encodeScopedId(ONE, "device-one");
const deviceTwo = encodeScopedId(TWO, "device-two");
const projectOne = encodeScopedId(ONE, "project-one");
const projectTwo = encodeScopedId(TWO, "project-two");

function defaults(overrides: Partial<NewChatDefaults> = {}): NewChatDefaults {
  return { device: deviceOne, project: projectOne, noProject: false, ...overrides };
}

describe("resolveNewChatTarget", () => {
  it("routes through the scoped project owner on the canvas", () => {
    const target = resolveNewChatTarget(
      { device: deviceOne, project: projectTwo, noProject: false },
      { spaceFilter: null, lastSpaceId: null },
      ONE,
    );
    expect(target.engineKey).toBe(TWO);
    expect(target.projectId).toBe(projectTwo);
  });

  it("honors noProject and ignores sidebar filter", () => {
    const target = resolveNewChatTarget(
      { device: null, project: null, noProject: true },
      { spaceFilter: projectOne, lastSpaceId: null },
      ONE,
    );
    expect(target.projectId).toBeNull();
    expect(target.engineKey).toBe(ONE);
  });
});

describe("new-chat engine and project selection policy", () => {
  it("clears a foreign project and records an explicit projectless target when switching engines", () => {
    expect(targetForDevicePick(defaults(), deviceTwo)).toEqual({ device: deviceTwo, project: null, noProject: true });
  });

  it("preserves the selected project when the selected device has the same owner", () => {
    expect(targetForDevicePick(defaults(), deviceOne)).toEqual({ device: deviceOne, project: projectOne, noProject: false });
  });

  it("choosing a project selects that project's owning device", () => {
    expect(targetForProjectPick(projectTwo, deviceTwo)).toEqual({ device: deviceTwo, project: projectTwo, noProject: false });
  });
});
