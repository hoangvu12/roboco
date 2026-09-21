import { useSyncExternalStore } from "react";
import { EngineStore, type FleetState } from "../lib/engine-store";

/**
 * The origin-scoped engine registry singleton. One per page load; browser
 * storage supplies persistence, so a fresh store reads whatever the
 * serving origin still holds (cleared site data = clean re-pair state).
 */
export const fleetStore = new EngineStore();

const subscribe = (listener: () => void) => fleetStore.subscribe(listener);
const getSnapshot = () => fleetStore.getSnapshot();

export function useFleet(): FleetState {
  return useSyncExternalStore(subscribe, getSnapshot);
}
