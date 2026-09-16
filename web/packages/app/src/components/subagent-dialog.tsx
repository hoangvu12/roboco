import { useEffect } from "react";
import type { EngineClient } from "@roboco/engine-client";
import { TranscriptView } from "./transcript";

/**
 * A subagent's transcript in a read-only overlay — the web stand-in for the
 * desktop's right-pane Subagent tab until that surface host lands. The doc id
 * comes off the spawn chip's `subagentRef` (`WatchDocMessages` serves any doc
 * id), so the same transcript machinery renders the child run.
 */
export function SubagentDialog({ client, docId, onClose }: { client: EngineClient; docId: string; onClose: () => void }) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="drawer-backdrop" onClick={onClose}>
      <div
        className="subagent-dialog"
        role="dialog"
        aria-label="Subagent transcript"
        onClick={(event) => event.stopPropagation()}
      >
        <header className="drawer-header">
          <h2>Subagent</h2>
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            Close
          </button>
        </header>
        <TranscriptView client={client} docId={docId} />
      </div>
    </div>
  );
}
