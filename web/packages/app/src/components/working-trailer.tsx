import { MatrixSpinner } from "./glyph-spinner";

/**
 * The working trailer — `render_working_trailer`
 * (crates/ui/src/transcript.rs:5218-5333). Appended under the transcript's
 * LAST row, above its clearance pad, so it reads as part of the streaming
 * reply and scrolls away with it.
 *
 * The failed-send branch takes precedence over everything else: past the
 * 120s grace window the trailer IS the retry affordance, whatever the
 * indicator fell back to.
 */

/** Everything the trailer can be; the surface derives it (§2.11). */
export type WorkingTrailerState =
  | { readonly kind: "none" }
  | { readonly kind: "undelivered"; readonly onRetry: () => void }
  | { readonly kind: "queued" }
  | { readonly kind: "sending" }
  | { readonly kind: "working"; readonly word: string; readonly elapsed: string };

/**
 * `gradient_spinner(cell 2.5)` → the `MatrixSpinner` at size ≈ 12 (cell 2.5 ×
 * 5). Ticket 20 corrects the spinner's phase denominator and sunrise tints;
 * this renders it as it exists.
 */
const SPINNER_SIZE = 12;

export function WorkingTrailer({ state }: { state: WorkingTrailerState }) {
  if (state.kind === "none") {
    return null;
  }
  if (state.kind === "undelivered") {
    // The desktop's affordance is the text row itself (`cursor_pointer`, one
    // `on_click`); the role/tabIndex keep it reachable from the keyboard.
    return (
      <div className="undelivered-retry" role="button" tabIndex={0} onClick={state.onRetry}>
        Not delivered — click to retry
      </div>
    );
  }
  const word =
    state.kind === "queued"
      ? "Queued — will send automatically"
      : state.kind === "sending"
        ? "Sending…"
        : `${state.word}…`;
  return (
    <div className="working-trailer" role="status">
      <MatrixSpinner size={SPINNER_SIZE} className="working-trailer-spinner" />
      <span className={`working-word ${state.kind === "queued" ? "working-word-queued" : ""}`}>{word}</span>
      {state.kind === "working" && <span className="working-elapsed">{state.elapsed}</span>}
    </div>
  );
}
