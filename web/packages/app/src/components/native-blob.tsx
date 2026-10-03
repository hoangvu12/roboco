import { useEffect, useMemo, useState } from "react";
import { BLOB_WINDOW_BYTES, BlobStream, downloadBytes, type NativeCaller } from "../lib/native-actions";
import { formatBytes } from "../lib/native";

type Phase = { readonly phase: "idle" } | { readonly phase: "loading" } | { readonly phase: "failed"; readonly message: string };

/**
 * A bounded, windowed view over one engine blob (or a numbered series). The
 * first window loads on mount, "Load more" reads the next, and Download reads
 * the rest and hands over the complete bytes. Nothing is truncated silently:
 * the footer always says how much of the record is on screen.
 */
export function NativeBlobView({
  caller,
  refs,
  totalBytes,
  filename,
  render,
}: {
  readonly caller: NativeCaller;
  readonly refs: readonly string[];
  readonly totalBytes: number | null;
  readonly filename: string;
  /** Optional renderer for the text read so far; the default is a monospace block. */
  readonly render?: (text: string) => React.ReactNode;
}) {
  const key = refs.join("\n");
  const stream = useMemo(() => new BlobStream(caller, refs), [caller, key]);
  const [phase, setPhase] = useState<Phase>({ phase: "idle" });
  const [, setLoaded] = useState(0);

  const step = async (all: boolean): Promise<boolean> => {
    setPhase({ phase: "loading" });
    try {
      if (all) {
        await stream.readAll();
      } else {
        await stream.readNext(BLOB_WINDOW_BYTES);
      }
      setLoaded(stream.loadedBytes);
      setPhase({ phase: "idle" });
      return true;
    } catch (error) {
      setPhase({ phase: "failed", message: error instanceof Error ? error.message : String(error) });
      return false;
    }
  };

  useEffect(() => {
    if (stream.loadedBytes === 0) {
      void step(false);
    }
  }, [stream]);

  const text = stream.text();
  const loading = phase.phase === "loading";
  return (
    <div className="native-detail-section" data-testid="native-blob">
      {render !== undefined ? render(text) : <pre className="native-pre">{text}</pre>}
      <div className="native-link-actions">
        <span className="native-option-text">
          {stream.done
            ? `Complete, ${formatBytes(stream.loadedBytes)}`
            : `Showing ${formatBytes(stream.loadedBytes)}${totalBytes !== null ? ` of ${formatBytes(totalBytes)}` : ""}`}
        </span>
        {!stream.done && (
          <button type="button" className="btn btn-ghost" disabled={loading} onClick={() => void step(false)}>
            {loading ? "Loading" : "Load more"}
          </button>
        )}
        <button
          type="button"
          className="btn btn-ghost"
          disabled={loading}
          onClick={() => {
            void (async () => {
              if (await step(true)) {
                downloadBytes(stream.bytes(), filename);
              }
            })();
          }}
        >
          Download full record
        </button>
      </div>
      {phase.phase === "failed" && (
        <div className="native-banner" role="alert" data-testid="native-blob-error">
          {phase.message}
        </div>
      )}
    </div>
  );
}
