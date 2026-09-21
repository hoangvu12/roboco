import { useState } from "react";
import type { PreviewService } from "@roboco/proto";
import {
  previewEmptyCopy,
  previewProxyReachable,
  previewRowLabel,
  previewSubtitle,
  previewUrl,
} from "../lib/preview";
import { usePreviews } from "../state/preview";
import { useEngineSession } from "../state/session-provider";

/**
 * The chat's preview pane: discovered dev servers of the chat's project,
 * and — when this browser shares the engine's loopback — the selected
 * server framed through the engine's preview proxy. The desktop's
 * "services list + open URL" UX (browser/view.rs preview_body) without
 * the native browser chrome, which is out of scope for the web client.
 *
 * Framed embedding covers same-machine use. Off-machine browsers get the
 * list plus an honest note: the WebRTC peer path that would carry frames
 * to them is deferred (rationale in src/lib/preview.ts, ticket 15).
 */
export function PreviewPanel({ chatId, onClose }: { chatId: string; onClose: () => void }) {
  const session = useEngineSession();
  const previews = usePreviews(session, chatId);
  const reachable = session !== null && previewProxyReachable(session.engine.baseUrl);
  // A frame, once opened, keeps its URL even if the service drops out of
  // discovery — the proxy answers a clear "not running" page and the
  // stable name works again after the dev server restarts.
  const [framed, setFramed] = useState<{ id: string; name: string; url: string } | null>(null);
  const [frameKey, setFrameKey] = useState(0);

  if (framed !== null) {
    return (
      <aside className="preview-panel" aria-label="Preview">
        <header className="preview-bar">
          <button type="button" className="btn btn-ghost" onClick={() => setFramed(null)}>
            Previews
          </button>
          <span className="preview-bar-title">{framed.name}</span>
          <span className="preview-bar-url">{framed.url}</span>
          <button
            type="button"
            className="btn btn-ghost"
            aria-label="Reload preview"
            onClick={() => setFrameKey((key) => key + 1)}
          >
            Reload
          </button>
          <a className="btn btn-ghost" href={framed.url} target="_blank" rel="noreferrer">
            Open
          </a>
          <button type="button" className="btn btn-ghost" aria-label="Close preview" onClick={onClose}>
            Close
          </button>
        </header>
        <iframe key={frameKey} className="preview-frame" src={framed.url} title={`${framed.name} preview`} />
      </aside>
    );
  }

  const snapshot = previews.snapshot;
  const available = reachable && snapshot !== null && snapshot.error === null;
  return (
    <aside className="preview-panel" aria-label="Preview">
      <header className="preview-bar">
        <span className="preview-bar-title">Previews</span>
        <span className="preview-bar-url" />
        <button type="button" className="btn btn-ghost" aria-label="Close preview" onClick={onClose}>
          Close
        </button>
      </header>
      <div className="preview-body">
        {snapshot !== null && snapshot.services.length > 0 && (
          <p className="preview-subtitle">{previewSubtitle(snapshot.remote)}</p>
        )}
        {snapshot !== null && snapshot.services.length > 0 && (
          <ul className="preview-list">
            {snapshot.services.map((service) => (
              <PreviewRow
                key={service.id}
                service={service}
                remote={snapshot.remote}
                proxyPort={snapshot.proxyPort}
                available={available}
                onOpen={(url) => setFramed({ id: service.id, name: service.name, url })}
              />
            ))}
          </ul>
        )}
        {(snapshot === null || snapshot.services.length === 0) && (
          <p className="preview-empty">{previewEmptyCopy(previews.loading, snapshot?.remote ?? false)}</p>
        )}
        {!reachable && (
          <p className="preview-note">
            This engine's previews frame only for a browser on the engine's own machine — the preview proxy
            listens on its loopback. Open this engine's Roboco page there to use them.
          </p>
        )}
        {previews.error !== null && <p className="preview-note">{previews.error}</p>}
      </div>
    </aside>
  );
}

interface PreviewRowProps {
  readonly service: PreviewService;
  readonly remote: boolean;
  readonly proxyPort: number;
  readonly available: boolean;
  readonly onOpen: (url: string) => void;
}

function PreviewRow({ service, remote, proxyPort, available, onOpen }: PreviewRowProps) {
  const url = previewUrl(service, proxyPort);
  return (
    <li
      className={`preview-row ${available ? "" : "preview-row-disabled"}`}
      onClick={available ? () => onOpen(url) : undefined}
    >
      <div className="preview-row-main">
        <span className="preview-row-name">{service.name}</span>
        <span className="preview-row-label">{previewRowLabel(service, remote)}</span>
      </div>
      <button
        type="button"
        className="btn btn-ghost"
        disabled={!available}
        aria-label={`Open ${service.name} preview`}
        onClick={(event) => {
          event.stopPropagation();
          onOpen(url);
        }}
      >
        Open
      </button>
    </li>
  );
}
