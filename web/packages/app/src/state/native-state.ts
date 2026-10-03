import { useCallback, useSyncExternalStore } from "react";
import type { NativeChatState } from "@roboco/proto";
import type { TranscriptStore } from "./transcript-store";

/** The host-confirmed native state riding a chat's transcript stream; null for any other harness. */
export function useNativeState(store: TranscriptStore | null): NativeChatState | null {
  return useSyncExternalStore(
    useCallback((listener: () => void) => (store === null ? () => {} : store.subscribe(listener)), [store]),
    useCallback(() => store?.getSnapshot().native ?? null, [store]),
  );
}
