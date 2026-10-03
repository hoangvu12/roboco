import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import type { NativeToolDetail, NativeToolView, SessionMessageEntry } from "@roboco/proto";
import type { EngineClient } from "@roboco/engine-client";
import { BlobStream, downloadBytes, exportToolDetail, seriesRefs, type NativeCaller } from "../lib/native-actions";
import { formatBytes, nativeChipTitle, nativeDetailVersion, parseProgressLines, parseToolDetail, pretty } from "../lib/native";
import { BtnGhost, Dialog, DialogCard, DialogTitle } from "./ui/Dialog";
import { NativeBlobView } from "./native-blob";
import { NativeMarkdown } from "./native-markdown";

/** Detail records larger than this open as a windowed raw view instead of being parsed whole. */
const PARSE_LIMIT_BYTES = 4 * 1024 * 1024;

interface NativeToolActions {
  readonly openDetail: (views: readonly NativeToolView[]) => void;
  readonly quietShown: boolean;
  readonly toggleQuiet: () => void;
}

const NativeToolContext = createContext<NativeToolActions>({
  openDetail: () => {},
  quietShown: false,
  toggleQuiet: () => {},
});

export function useNativeToolActions(): NativeToolActions {
  return useContext(NativeToolContext);
}

/** A tool call as the transcript shows it now: its view and whether it settled. */
interface LiveCall {
  readonly view: NativeToolView;
  readonly resolved: boolean;
  readonly isError: boolean;
}

function liveCalls(entries: readonly SessionMessageEntry[]): ReadonlyMap<string, LiveCall> {
  const calls = new Map<string, LiveCall>();
  for (const entry of entries) {
    for (const part of entry.parts) {
      if (part.kind === "tool" && part.call.kind === "native") {
        calls.set(part.call.view.toolCallId, { view: part.call.view, resolved: part.resolved, isError: part.isError });
      }
    }
  }
  return calls;
}

/**
 * Hosts the detail dialog above the virtualized rows, so it survives a row
 * unmounting. The dialog holds call identities, not views: each section reads
 * the call's current view, so a call that settles while its detail is open is
 * read again instead of keeping the running record.
 */
export function NativeToolProvider({
  client,
  quietShown,
  onToggleQuiet,
  entries,
  children,
}: {
  readonly client: EngineClient;
  readonly quietShown: boolean;
  readonly onToggleQuiet: () => void;
  readonly entries: readonly SessionMessageEntry[];
  readonly children: ReactNode;
}) {
  const [views, setViews] = useState<readonly NativeToolView[] | null>(null);
  const live = useMemo(() => liveCalls(entries), [entries]);
  const actions = useMemo<NativeToolActions>(
    () => ({ openDetail: setViews, quietShown, toggleQuiet: onToggleQuiet }),
    [quietShown, onToggleQuiet],
  );
  return (
    <NativeToolContext.Provider value={actions}>
      {children}
      {views !== null && (
        <Dialog ariaLabel="Tool call details" onClose={() => setViews(null)}>
          <DialogCard>
            <div className="native-detail" data-testid="native-tool-detail">
              <DialogTitle>{views.length === 1 ? "Tool call" : `${views.length} tool calls`}</DialogTitle>
              {views.map((opened) => {
                const call = live.get(opened.toolCallId) ?? { view: opened, resolved: true, isError: false };
                return (
                  <ToolDetailSection
                    key={opened.toolCallId}
                    caller={client}
                    view={call.view}
                    version={nativeDetailVersion(call.view, call.resolved, call.isError)}
                  />
                );
              })}
              <div className="native-link-actions">
                <BtnGhost onClick={() => setViews(null)}>Close</BtnGhost>
              </div>
            </div>
          </DialogCard>
        </Dialog>
      )}
    </NativeToolContext.Provider>
  );
}

/** The "quiet calls hidden" toggle row. */
export function QuietCallsRow({ count, shown }: { readonly count: number; readonly shown: boolean }) {
  const { toggleQuiet } = useNativeToolActions();
  return (
    <div className="native-quiet" data-testid="native-quiet-calls">
      <span>{shown ? `Showing ${count} quiet calls` : `Quiet calls hidden: ${count}`}</span>
      <button type="button" className="btn btn-ghost" onClick={toggleQuiet}>
        {shown ? "Hide" : "Show"}
      </button>
    </div>
  );
}

type Loaded =
  | { readonly phase: "loading" }
  | { readonly phase: "failed"; readonly message: string }
  /** Too large to read whole without asking; the bounded raw view and full-on-intent stand in. */
  | { readonly phase: "large" }
  /** Read, but not a tool detail record: shown as stored. */
  | { readonly phase: "raw" }
  | { readonly phase: "ready"; readonly detail: NativeToolDetail; readonly bytes: Uint8Array };

/** Read the whole record and parse it: a record that is not a tool detail is shown as stored. */
async function readDetail(caller: NativeCaller, ref: string): Promise<Loaded> {
  const stream = new BlobStream(caller, [ref]);
  await stream.readAll();
  let raw: unknown = null;
  try {
    raw = JSON.parse(stream.text());
  } catch {
    return { phase: "raw" };
  }
  const detail = parseToolDetail(raw);
  return detail === null ? { phase: "raw" } : { phase: "ready", detail, bytes: stream.bytes() };
}

function ToolDetailSection({
  caller,
  view,
  version,
}: {
  readonly caller: NativeCaller;
  readonly view: NativeToolView;
  /** A new version (the call settled) reads the record again. */
  readonly version: string;
}) {
  const ref = view.detailRef;
  const [loaded, setLoaded] = useState<Loaded>({ phase: "loading" });
  // A large record is read whole only when the user asks; each ask reads again.
  const [wholeAsked, setWholeAsked] = useState(0);
  useEffect(() => {
    if (ref === null) {
      return;
    }
    if (wholeAsked === 0 && view.detailBytes !== null && view.detailBytes > PARSE_LIMIT_BYTES) {
      setLoaded({ phase: "large" });
      return;
    }
    let live = true;
    setLoaded({ phase: "loading" });
    readDetail(caller, ref)
      .then((next) => {
        if (live) {
          setLoaded(next);
        }
      })
      .catch((error: unknown) => {
        if (live) {
          setLoaded({ phase: "failed", message: error instanceof Error ? error.message : String(error) });
        }
      });
    return () => {
      live = false;
    };
  }, [caller, ref, version, wholeAsked]);

  const title = nativeChipTitle(view, true);
  return (
    <section className="native-detail-section" data-testid="native-tool-detail-section" data-version={version}>
      <h3>{title}</h3>
      {ref === null && (
        <p className="native-option-text">The host kept no detail record for this call.</p>
      )}
      {ref !== null && loaded.phase === "loading" && <p className="native-option-text">Loading the detail record.</p>}
      {loaded.phase === "failed" && (
        <div className="native-banner" role="alert">
          {loaded.message}{" "}
          <button type="button" className="btn btn-ghost" onClick={() => setWholeAsked((n) => n + 1)}>
            Try again
          </button>
        </div>
      )}
      {ref !== null && loaded.phase === "large" && (
        <>
          <p className="native-option-text" data-testid="native-detail-large">
            This record is {formatBytes(view.detailBytes ?? 0)}, so it opens as stored, one window at a time. Read it whole to see
            its fields, its progress and its streamed output.
          </p>
          <div className="native-link-actions">
            <button type="button" className="btn btn-ghost" onClick={() => setWholeAsked((n) => n + 1)}>
              Read the whole record
            </button>
            <ExportButton caller={caller} detailRef={ref} toolCallId={view.toolCallId} />
          </div>
          <NativeBlobView caller={caller} refs={[ref]} totalBytes={view.detailBytes} filename={`${view.toolCallId}.json`} />
        </>
      )}
      {ref !== null && loaded.phase === "raw" && (
        <>
          <p className="native-option-text">This record is not a tool detail this version can read, so it is shown as stored.</p>
          <NativeBlobView caller={caller} refs={[ref]} totalBytes={view.detailBytes} filename={`${view.toolCallId}.json`} />
        </>
      )}
      {ref !== null && loaded.phase === "ready" && (
        <DetailBody caller={caller} detailRef={ref} detail={loaded.detail} bytes={loaded.bytes} />
      )}
    </section>
  );
}

/** Downloads the complete export: the record and every series chunk. A failed read says so here. */
function ExportButton({ caller, detailRef, toolCallId }: { readonly caller: NativeCaller; readonly detailRef: string; readonly toolCallId: string }) {
  const [state, setState] = useState<{ readonly phase: "idle" | "reading" } | { readonly phase: "failed"; readonly message: string }>({
    phase: "idle",
  });
  return (
    <>
      <button
        type="button"
        className="btn btn-ghost"
        disabled={state.phase === "reading"}
        onClick={() => {
          setState({ phase: "reading" });
          exportToolDetail(caller, detailRef)
            .then((bytes) => {
              downloadBytes(bytes, `${toolCallId}.export.txt`);
              setState({ phase: "idle" });
            })
            .catch((error: unknown) => setState({ phase: "failed", message: error instanceof Error ? error.message : String(error) }));
        }}
      >
        {state.phase === "reading" ? "Reading every record" : "Download complete export"}
      </button>
      {state.phase === "failed" && (
        <span className="native-feedback native-feedback-failed" role="alert" data-testid="native-export-error">
          Could not export: {state.message}
        </span>
      )}
    </>
  );
}

function Labeled({ label, children }: { readonly label: string; readonly children: ReactNode }) {
  return (
    <div className="native-detail-section">
      <h3>{label}</h3>
      {children}
    </div>
  );
}

function DetailBody({
  caller,
  detailRef,
  detail,
  bytes,
}: {
  readonly caller: NativeCaller;
  readonly detailRef: string;
  readonly detail: NativeToolDetail;
  readonly bytes: Uint8Array;
}) {
  const view = detail.view;
  const output = detail.output ?? "";
  const hasInput = detail.input !== null && detail.input !== undefined;
  return (
    <>
      <p className="native-option-text">
        {detail.isError === true ? "Failed" : detail.isError === false ? "Succeeded" : "No result yet"}
        {view.durationMs !== null ? `, ${(view.durationMs / 1000).toFixed(1)} s` : ""}
        {` · ${detail.name}`}
      </p>
      {view.locations.length > 0 && (
        <ul className="native-notice-body">
          {view.locations.map((location) => (
            <li key={`${location.path}:${location.line ?? ""}`}>
              {location.path}
              {location.line !== null ? `:${location.line}` : ""}
            </li>
          ))}
        </ul>
      )}
      {hasInput && (
        <Labeled label="Input">
          <pre className="native-pre" data-testid="native-detail-input">
            {pretty(detail.input)}
          </pre>
        </Labeled>
      )}
      {detail.rawInput !== null && (
        <Labeled label="Raw input, as the host received it">
          <pre className="native-pre" data-testid="native-detail-raw-input">
            {detail.rawInput}
          </pre>
        </Labeled>
      )}
      {(output.length > 0 || detail.displayContent.length > 0) && (
        <Labeled label="Result">
          {output.length > 0 ? (
            view.semantic !== null ? (
              <NativeMarkdown text={output} />
            ) : (
              <pre className="native-pre" data-testid="native-detail-output">
                {output}
              </pre>
            )
          ) : (
            <p className="native-option-text">
              The host's public content for this call has no text. Its {detail.displayContent.length} content blocks are
              listed below.
            </p>
          )}
        </Labeled>
      )}
      {detail.displayContent.length > 0 && (
        <Labeled label={`Public content blocks (${detail.displayContent.length})`}>
          <pre className="native-pre" data-testid="native-detail-display">
            {pretty(detail.displayContent)}
          </pre>
        </Labeled>
      )}
      {detail.details !== null && detail.details !== undefined && (
        <Labeled label="Host details">
          <pre className="native-pre">{pretty(detail.details)}</pre>
        </Labeled>
      )}
      {detail.outputProfile !== null && detail.outputProfile !== undefined && (
        <Labeled label="Output profile">
          <pre className="native-pre" data-testid="native-detail-output-profile">
            {pretty(detail.outputProfile)}
          </pre>
        </Labeled>
      )}
      {detail.compactions.length > 0 && (
        <Labeled label={`Compactions (${detail.compactions.length}). The original bytes may be unavailable.`}>
          <pre className="native-pre" data-testid="native-detail-compactions">
            {pretty(detail.compactions)}
          </pre>
        </Labeled>
      )}
      {detail.progress !== null && (
        <SeriesToggle
          caller={caller}
          label={`Progress (${detail.progress.records} lines, ${formatBytes(detail.progress.bytes)})`}
          refs={seriesRefs(detail.progress)}
          bytes={detail.progress.bytes}
          filename={`${view.toolCallId}.progress.jsonl`}
          render={(text) => (
            <ol className="native-notice-body" data-testid="native-detail-progress">
              {parseProgressLines(text).map((line, ix) => (
                <li key={ix}>{line}</li>
              ))}
            </ol>
          )}
        />
      )}
      {detail.stream !== null && (
        <SeriesToggle
          caller={caller}
          label={`Streamed output (${detail.stream.records} deltas, ${formatBytes(detail.stream.bytes)})`}
          refs={seriesRefs(detail.stream)}
          bytes={detail.stream.bytes}
          filename={`${view.toolCallId}.stream.txt`}
        />
      )}
      <div className="native-link-actions">
        <button
          type="button"
          className="btn btn-ghost"
          onClick={() => downloadBytes(bytes, `${view.toolCallId}.detail.json`, "application/json")}
        >
          Download detail record
        </button>
        <ExportButton caller={caller} detailRef={detailRef} toolCallId={view.toolCallId} />
      </div>
    </>
  );
}

function SeriesToggle({
  caller,
  label,
  refs,
  bytes,
  filename,
  render,
}: {
  readonly caller: NativeCaller;
  readonly label: string;
  readonly refs: readonly string[];
  readonly bytes: number;
  readonly filename: string;
  readonly render?: (text: string) => ReactNode;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div className="native-detail-section">
      <button type="button" className="btn btn-ghost" aria-expanded={open} onClick={() => setOpen(!open)}>
        {open ? `Hide ${label}` : `Show ${label}`}
      </button>
      {open && <NativeBlobView caller={caller} refs={refs} totalBytes={bytes} filename={filename} render={render} />}
    </div>
  );
}
