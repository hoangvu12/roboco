import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import type { PreviewService } from "@roboco/proto";
import { Icon } from "@roboco/icons";
import type { EngineSession } from "../state/engine-session";
import { useEngineSession } from "../state/session-provider";
import { usePreviews } from "../state/preview";
import {
  previewEmptyCopy,
  previewOffMachineNote,
  previewProxyReachable,
  previewRowLabel,
  previewSubtitle,
  previewUrl,
} from "../lib/preview";

/**
 * The chat's preview panel — the web peer of the desktop browser surface's
 * preview-list body (`crates/ui/src/browser/view.rs` `preview_body`), docked
 * beside the transcript as a right-pane surface exactly where the desktop
 * puts its Browser surface (agent-previews ticket 06). The desktop's
 * arbitrary-address browsing stays desktop-only; this surface frames
 * nothing but the engine's own preview-proxy URLs.
 *
 * Framing is loopback-only by construction (the spec's "Web client v1 is
 * loopback framing only"): `previewProxyReachable(engineBaseUrl)` gates the
 * iframe — a browser that dialed the engine over loopback shares its
 * loopback namespace, so `http://{device}.{service}.localhost:7331`
 * resolves through the engine's proxy with WebSocket/HMR upgrades intact;
 * SSH port-forwards count, which is why the gate keys on the endpoint. An
 * off-machine browser gets the live list (labels and empty states in the
 * desktop's vocabulary) plus the honest note — never a dead frame. No
 * remote/relay path ships here: the origin-engine relay and ServiceWorker
 * fetch bridge are Phase D, specced separately.
 */

/**
 * The framed dev server's sandbox. There is no prior iframe usage in
 * `web/packages/app` to inherit from (this is the app's first), so the
 * posture is decided here, against the preview origin's own shape:
 *
 * - The preview origin (`{device}.{service}.localhost:7331`) is always
 *   cross-origin to the app (the engine serves the app from its own
 *   listener port, Vite from its) — the frame can never touch this app's
 *   DOM, scripts, or storage whatever the sandbox says.
 * - `allow-scripts` + `allow-same-origin`: the dev server needs its scripts
 *   and its own origin (localStorage, cookies, HMR state). The classic
 *   "sandbox escape" of that pair needs the framed page to BE the parent's
 *   origin, which this never is.
 * - `allow-forms`, `allow-popups` + `allow-popups-to-escape-sandbox` (dev
 *   servers' OAuth popups must land unsandboxed), `allow-downloads`,
 *   `allow-modals` (`alert()` debugging): the dev-server toolset.
 * - Deliberately omitted: `allow-top-navigation` and friends — a dev
 *   server's page must never navigate this app's top window away, sandboxed
 *   or by user activation.
 */
const PREVIEW_IFRAME_SANDBOX =
  "allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox allow-downloads allow-modals";

/** A framed service, captured at open — stable through discovery dropouts. */
interface FramedPreview {
  readonly name: string;
  readonly url: string;
}

interface PreviewPanelProps {
  /** The selected chat (scoped id; the wire boundary decodes it per engine). */
  readonly chatId: string;
}

/** The registry's surface body — context-bound (the pane routes the session). */
export function PreviewPanel({ chatId }: PreviewPanelProps): ReactNode {
  const session = useEngineSession();
  return <PreviewPanelBody session={session} chatId={chatId} />;
}

interface PreviewPanelBodyProps extends PreviewPanelProps {
  /** The chat's engine session — passed by the surface, or directly by tests. */
  readonly session: EngineSession | null;
}

/**
 * The panel core: the live preview list over `usePreviews`, the framing
 * gate over the engine endpoint, and the framed view a row click opens.
 * `session` is a prop (the Composer's pattern) so the mounted component is
 * testable against a fake client without the session context.
 */
export function PreviewPanelBody({ session, chatId }: PreviewPanelBodyProps): ReactNode {
  const previews = usePreviews(session, chatId);
  // The framing gate — the web peer of the desktop's `EngineKey::is_local`
  // check in browser/mod.rs `watch_previews` (browser↔engine loopback, SSH
  // port-forwards included). Rows stay listed either way; only framing is
  // gated, so off-machine browsers get the list plus the honest note.
  const reachable = session !== null && previewProxyReachable(session.engine.baseUrl);
  const [framed, setFramed] = useState<FramedPreview | null>(null);
  const [frameKey, setFrameKey] = useState(0);

  // The pane's surface survives a chat switch in place (one component, the
  // destination chat's pane state) — a framed dev server belongs to the
  // chat that opened it, so the frame never crosses the switch.
  useEffect(() => {
    setFramed(null);
  }, [chatId]);

  if (framed !== null) {
    return (
      <div className="preview-panel" aria-label="Preview">
        <div className="surface-toolbar preview-bar" role="toolbar" aria-label="Preview frame">
          <button
            type="button"
            className="preview-tool"
            aria-label="Back to previews"
            title="Back to previews"
            onClick={() => setFramed(null)}
          >
            <Icon name="arrowLeft" size={14} />
          </button>
          <span className="preview-bar-title" title={framed.name}>
            {framed.name}
          </span>
          <span className="preview-bar-url mono" title={framed.url}>
            {framed.url}
          </span>
          <button
            type="button"
            className="preview-tool"
            aria-label="Reload preview"
            title="Reload preview"
            onClick={() => setFrameKey((key) => key + 1)}
          >
            <Icon name="refresh" size={14} />
          </button>
          <a
            className="preview-tool"
            href={framed.url}
            target="_blank"
            rel="noreferrer"
            aria-label="Open in a browser tab"
            title="Open in a browser tab"
          >
            <Icon name="arrowUpRight" size={14} />
          </a>
        </div>
        {/*
          The frame keeps its URL through discovery dropouts: the proxy
          answers a clear "not running" page for a dead server, and the
          engine-persisted hostname works again the moment it restarts.
          `key` is the reload — a fresh element is a fresh load.
        */}
        <iframe
          key={frameKey}
          className="preview-frame"
          src={framed.url}
          title={`${framed.name} preview`}
          sandbox={PREVIEW_IFRAME_SANDBOX}
        />
      </div>
    );
  }

  const snapshot = previews.snapshot;
  // The desktop's row availability (`snapshot.error.is_none()`), extended
  // with the web's framing gate: rows open frames only when this browser
  // can reach the proxy.
  const available = reachable && snapshot !== null && snapshot.error === null;
  return (
    <div className="preview-panel" aria-label="Preview">
      <div className="preview-body">
        {snapshot !== null && snapshot.services.length > 0 ? (
          <>
            <p className="preview-subtitle">{previewSubtitle(snapshot.remote)}</p>
            <ul className="preview-list">
              {snapshot.services.map((service) => (
                <PreviewRow
                  key={service.id}
                  service={service}
                  remote={snapshot.remote}
                  proxyPort={snapshot.proxyPort}
                  available={available}
                  onOpen={() =>
                    setFramed({
                      name: service.name,
                      url: previewUrl(service, snapshot.proxyPort),
                    })
                  }
                />
              ))}
            </ul>
          </>
        ) : (
          <p className="preview-empty">
            {previewEmptyCopy(previews.loading, snapshot?.remote ?? false)}
          </p>
        )}
        {/*
          The honest off-machine note — the spec's boundary, verbatim: the
          list stays live, the rows stand down, and the note says what
          would make them openable (same machine, or SSH port-forwards).
        */}
        {!reachable && <p className="preview-note">{previewOffMachineNote()}</p>}
        {previews.error !== null && (
          <p className="preview-note" role="status">
            {previews.error}
          </p>
        )}
      </div>
    </div>
  );
}

interface PreviewRowProps {
  readonly service: PreviewService;
  /** The engine-reported device remoteness — remote rows name their device. */
  readonly remote: boolean;
  readonly proxyPort: number;
  readonly available: boolean;
  readonly onOpen: () => void;
}

/**
 * One discovered dev server — the desktop row's shape (name over label,
 * trailing "Open"), with the row itself the button so the whole affordance
 * is one focusable target (the desktop's row click and Open pill are the
 * same action on the web panel).
 */
function PreviewRow({ service, remote, proxyPort, available, onOpen }: PreviewRowProps): ReactNode {
  return (
    <li>
      <button
        type="button"
        className="preview-row"
        disabled={!available}
        aria-label={`Open ${service.name} preview`}
        title={previewUrl(service, proxyPort)}
        onClick={onOpen}
      >
        <span className="preview-row-main">
          <span className="preview-row-name">{service.name}</span>
          <span className="preview-row-label">{previewRowLabel(service, remote)}</span>
        </span>
        <span className="preview-row-open" aria-hidden="true">
          Open
        </span>
      </button>
    </li>
  );
}
