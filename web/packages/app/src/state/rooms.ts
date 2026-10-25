import { useSyncExternalStore } from "react";
import { RoomStore, type RoomsState } from "../lib/room-store";

/**
 * The app's joined-room singleton, beside `state/fleet.ts`: rooms live in
 * browser storage scoped to the serving origin (the secret with the same
 * at-rest care as a session credential, ADR 0011), the member labels feed
 * the engines UI's offline rows, and `useRooms()` is the React seam.
 */

export const roomStore = new RoomStore();

const subscribeRooms = (listener: () => void) => roomStore.subscribe(listener);
const getRoomsSnapshot = () => roomStore.getSnapshot();

/** The joined rooms, subscribed. */
export function useRooms(): RoomsState {
  return useSyncExternalStore(subscribeRooms, getRoomsSnapshot, getRoomsSnapshot);
}
