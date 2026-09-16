import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    projects: [
      {
        test: {
          name: "unit",
          environment: "node",
          include: ["tests/codec.test.ts", "tests/fake-server.test.ts"],
        },
      },
      {
        test: {
          name: "conformance",
          environment: "node",
          include: ["tests/conformance.test.ts"],
          // The suite builds the conformance engine example on first run.
          hookTimeout: 600_000,
          testTimeout: 120_000,
        },
      },
    ],
  },
});
