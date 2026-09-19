import { describe, expect, it } from "vitest";
import type { LoadableList } from "../src/state/picker-catalog";
import { catalogLoading, modelsLoading, shouldReload } from "../src/lib/catalog-loading";

function slot(overrides: Partial<LoadableList<unknown>> = {}): LoadableList<unknown> {
  return { rows: [], loaded: false, error: null, loading: false, generation: 0, ...overrides };
}

describe("chip loading states", () => {
  it("chip_loading_states_exclude_error", () => {
    // The desktop shape (pickers.rs:4207-4221): chip loading composes
    // `catalog_loading`, `models_loading` and the label resolution — an
    // errored slot never reads as loading on either arm.
    const idle = slot();
    const loading = slot({ loading: true });
    const ready = slot({ rows: [{}], loaded: true });
    const error = slot({ error: "Engine is offline; reconnecting" });

    // catalog_loading (pickers.rs:4207): Idle | Loading only — an Error
    // slot is settled, so the chip's brand slot never spins forever.
    expect(catalogLoading(idle)).toBe(true);
    expect(catalogLoading(loading)).toBe(true);
    expect(catalogLoading(ready)).toBe(false);
    expect(catalogLoading(error)).toBe(false);

    // models_loading (pickers.rs:4208-4213): the model slot must be
    // neither Ready nor Error — absent, Idle, or Loading.
    expect(modelsLoading(undefined)).toBe(true);
    expect(modelsLoading(idle)).toBe(true);
    expect(modelsLoading(loading)).toBe(true);
    expect(modelsLoading(ready)).toBe(false);
    expect(modelsLoading(error)).toBe(false);

    // chip_icon_loading / chip_label_loading (pickers.rs:4216-4221)
    // composed over the label resolution (`model_label`,
    // pickers.rs:4186-4206): remembered label → configured/raw id, so an
    // errored catalog — whose model slot was never requested — still
    // names the pick and never reads as loading.
    const noAgents = false;
    const noEffectiveHarness = true; // fresh install: no chat config, nothing remembered
    const modelLabel = (catalog: LoadableList<unknown>, configuredId: string | null): string => {
      if (catalog.loaded) {
        return "Fable 5"; // the selected row's label once rows exist
      }
      return configuredId ?? ""; // remembered label → the configured id
    };
    const chipIconLoading = (catalog: LoadableList<unknown>): boolean =>
      noEffectiveHarness && !noAgents && catalogLoading(catalog);
    const chipLabelLoading = (
      catalog: LoadableList<unknown>,
      models: LoadableList<unknown> | undefined,
      configuredId: string | null,
    ): boolean =>
      !noAgents &&
      modelLabel(catalog, configuredId).length === 0 &&
      (catalogLoading(catalog) || modelsLoading(models));

    // Errored catalog with a configured id: the raw id is the label — no
    // spinner, no skeleton, even though the model slot was never
    // requested (modelsLoading true).
    expect(chipIconLoading(error)).toBe(false);
    expect(chipLabelLoading(error, undefined, "fable-5")).toBe(false);
    // Loading catalog, fresh install (no id, nothing remembered): the
    // ghost label slot.
    expect(chipIconLoading(loading)).toBe(true);
    expect(chipLabelLoading(loading, undefined, null)).toBe(true);
    // A Ready or Error model slot never sets models loading (the
    // remembered label or raw id names the pick instead).
    expect(chipLabelLoading(ready, error, "fable-5")).toBe(false);
    expect(chipLabelLoading(ready, ready, "fable-5")).toBe(false);
  });

  it("a failed forced revalidation keeps its rows and never reads as loading", () => {
    // listWithError preserves loaded rows: the stale catalog stays on
    // screen (stale-while-revalidate) and the chip keeps its label.
    const staleWithError = slot({ rows: [{}], loaded: true, error: "timeout" });
    expect(catalogLoading(staleWithError)).toBe(false);
    expect(modelsLoading(staleWithError)).toBe(false);
  });
});

describe("shouldReload (ensure_harnesses table, pickers.rs:1037-1041)", () => {
  it("idle loads with and without force", () => {
    expect(shouldReload(slot(), false)).toBe(true);
    expect(shouldReload(slot(), true)).toBe(true);
  });

  it("loading never re-fires, forced or not (the in-flight guard stands)", () => {
    expect(shouldReload(slot({ loading: true }), false)).toBe(false);
    expect(shouldReload(slot({ loading: true }), true)).toBe(false);
  });

  it("ready reloads only when forced (stale-while-revalidate)", () => {
    expect(shouldReload(slot({ rows: [{}], loaded: true }), false)).toBe(false);
    expect(shouldReload(slot({ rows: [{}], loaded: true }), true)).toBe(true);
  });

  it("error reloads only when forced (healed by the retryable-offline rule, focus, or open)", () => {
    expect(shouldReload(slot({ error: "Engine is offline; reconnecting" }), false)).toBe(false);
    expect(shouldReload(slot({ error: "Engine is offline; reconnecting" }), true)).toBe(true);
  });

  it("an error that kept its rows reloads only when forced, like a ready slot", () => {
    const staleWithError = slot({ rows: [{}], loaded: true, error: "timeout" });
    expect(shouldReload(staleWithError, false)).toBe(false);
    expect(shouldReload(staleWithError, true)).toBe(true);
  });

  it("the non-forced cadence is a no-op on every settled slot", () => {
    // The render-cadence invariant: exactly one kick per Idle slot, never
    // a re-fire on Loading, Ready, or Error.
    for (const settled of [
      slot({ loading: true }),
      slot({ rows: [{}], loaded: true }),
      slot({ error: "boom" }),
      slot({ rows: [{}], loaded: true, error: "boom" }),
    ]) {
      expect(shouldReload(settled, false)).toBe(false);
    }
  });
});
