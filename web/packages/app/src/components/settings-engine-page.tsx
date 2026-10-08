import { Fragment } from "react";
import type { ReactNode } from "react";
import { useFleet } from "../state/fleet";
import { settingsEngineKey } from "../lib/settings-engine";

/**
 * The engine-addressing settings pages (Remote access, Agents, Accounts)
 * render their bodies through this keyed wrapper. Switching the settings
 * engine — the indicator's popover row, another surface's
 * `fleetStore.setActive`, or a DeviceSwitcher pick of another engine's
 * host — REMOUNTS the page body instead of reconciling it: every
 * device-local editor state (an in-flight agent sign-in and its failure,
 * install and update replies, expanded rows, error strips, the
 * DeviceSwitcher's passthrough target) is dropped with the engine it
 * belongs to, and the fresh body re-loads from the engine the picker
 * chose. Port of zeron 97f86114's "settings pages follow the selected
 * engine" contract (roboco keeps its own DeviceSwitcher and pairing
 * transport); the desktop needs nothing like it — its settings address
 * the implicit local engine by construction (`remote_access.rs:37-39`).
 *
 * The key is `settingsEngineKey(fleet)` — `fleet.active`, the engine the
 * `/settings/*` routes route to (`routedEngineKey`): null before any
 * engine is paired, and a change unmounts the body below.
 */
export function SettingsEnginePage({ children }: { readonly children: ReactNode }) {
  const key = settingsEngineKey(useFleet());
  return <Fragment key={key}>{children}</Fragment>;
}
