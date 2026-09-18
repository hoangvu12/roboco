import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
  type RefObject,
} from "react";
import { highlightCode, splitTokenLines, type SyntaxToken } from "../../lib/syntax";
import { isMarkdownPath } from "../../lib/files";
import { HorizontalScrollbar, MenuScrollbar } from "../ui/Scrollbar";

/**
 * `CodeView` — the file viewer's text layer (crates/ui/src/files/preview.rs:
 * `render_document_body` / `ensure_editor` / `render_preview_line`). One
 * component renders both the read-only preview and the editable buffer,
 * exactly like the desktop's single text state: gutter + line numbers +
 * syntax-highlighted rows, with only the input layer toggled. Editing rides
 * a transparent `<textarea>` laid over the highlight layer (the research's
 * sanctioned shape — no CodeMirror/Monaco); both layers share the same
 * font metrics, paddings, and wrap mode so the caret sits exactly on the
 * colored text.
 *
 * Layout ports (preview.rs:3108-3189):
 * - gutter 48px, right-aligned mono 10px `text_faint` at 0.7, right border
 *   at 0.55.
 * - read-only rows: 20px line height, mono 11.5px, base color `text` at
 *   0.93, code cell padding 12/18, `nowrap` rows scroll horizontally.
 * - editable rows: the `editorFontSize` setting drives size and
 *   `line-height = max(size + 8.5, 20)`; selection washes `accent` at 0.22,
 *   the caret is `--rb-caret`, the active line `ink` at 0.025.
 * - word wrap: rows stretch and wrap; off: nowrap rows (the scroller gives
 *   native horizontal scroll with a working vertical wheel).
 *
 * Ticket 23 seam: gutter cells render through `renderGutterCell` — a later
 * per-row gutter affordance slots in there without restructuring the row
 * loop.
 */

/** `PREVIEW_LINE_HEIGHT` (preview.rs:34). */
const PREVIEW_LINE_HEIGHT = 20;
/** The read-only code text size (preview.rs:3181). */
const PREVIEW_TEXT_PX = 11.5;
/** The highlight debounce (preview.rs request_editor_highlight: 120ms). */
const HIGHLIGHT_DEBOUNCE_MS = 120;

export interface CodeViewProps {
  readonly text: string;
  readonly path: string;
  /** Whether the input layer accepts keystrokes. */
  readonly editable: boolean;
  readonly onChange: (text: string) => void;
  /** `filesEditorFontSize` — only meaningful while editable. */
  readonly fontSize: number;
  readonly wordWrap: boolean;
  /** Focus the input layer once mounted (the markdown toggle's off-ramp). */
  readonly autoFocus?: boolean;
  /** The host's handle to the input layer (the context menu's target). */
  readonly inputRef?: RefObject<HTMLTextAreaElement | null>;
}

/** The tokenizer's language hint: the file's own extension. */
function languageForPath(path: string): string | null {
  const name = path.split("/").pop() ?? path;
  const dot = name.lastIndexOf(".");
  if (dot <= 0) {
    return null;
  }
  return name.slice(dot + 1).toLowerCase();
}

export function CodeView({ text, path, editable, onChange, fontSize, wordWrap, autoFocus, inputRef }: CodeViewProps) {
  const language = useMemo(() => languageForPath(path), [path]);
  // Markdown files highlight as markdown; everything else keys off its
  // extension (`lib/syntax.ts` resolves aliases).
  const effectiveLanguage = isMarkdownPath(path) ? "markdown" : language;
  const lines = useMemo(() => text.split("\n"), [text]);
  const [tokenLines, setTokenLines] = useState<SyntaxToken[][]>(() =>
    splitTokenLines(highlightCode(text, effectiveLanguage)),
  );
  const [highlightedSource, setHighlightedSource] = useState(text);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const [activeLine, setActiveLine] = useState(0);

  // Keep the host's handle (the editor context menu's target) live.
  useEffect(() => {
    if (inputRef !== undefined) {
      inputRef.current = textareaRef.current;
    }
  });

  useEffect(() => {
    if (autoFocus && editable) {
      textareaRef.current?.focus();
    }
  }, [autoFocus, editable]);

  // Read-only views tokenize synchronously; editable ones debounce at the
  // desktop's 120ms so long files keep typing smooth — the plain rows hold
  // the current text while tokens catch up.
  useEffect(() => {
    if (!editable) {
      setTokenLines(splitTokenLines(highlightCode(text, effectiveLanguage)));
      setHighlightedSource(text);
      return;
    }
    if (text === highlightedSource) {
      return;
    }
    const timer = setTimeout(() => {
      setTokenLines(splitTokenLines(highlightCode(text, effectiveLanguage)));
      setHighlightedSource(text);
    }, HIGHLIGHT_DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [text, effectiveLanguage, editable, highlightedSource]);

  const measureCaretLine = useCallback((): void => {
    const textarea = textareaRef.current;
    if (textarea === null) {
      return;
    }
    setActiveLine(text.slice(0, textarea.selectionStart).split("\n").length - 1);
  }, [text]);

  // The active-line wash follows the caret (editor_adapter.rs's
  // active_line highlight).
  useEffect(() => {
    if (!editable) {
      return;
    }
    document.addEventListener("selectionchange", measureCaretLine);
    return () => {
      document.removeEventListener("selectionchange", measureCaretLine);
    };
  }, [editable, measureCaretLine]);

  return (
    <div
      className={[
        "files-code",
        editable ? "files-code-editable" : "",
        wordWrap ? "files-code-wrap" : "",
      ]
        .filter((part) => part.length > 0)
        .join(" ")}
      style={
        editable
          ? {
              fontSize: `${fontSize}px`,
              lineHeight: `${Math.max(fontSize + 8.5, PREVIEW_LINE_HEIGHT)}px`,
            }
          : { fontSize: `${PREVIEW_TEXT_PX}px`, lineHeight: `${PREVIEW_LINE_HEIGHT}px` }
      }
    >
      <div ref={scrollRef} className="files-code-scroll">
        <div className="files-code-content">
          {lines.map((line, index) => (
            <div
              key={index}
              className={`files-code-row${editable && index === activeLine ? " files-code-row-active" : ""}`}
            >
              {renderGutterCell(index)}
              <span className="files-code-line">
                {renderTokens(line, index, tokenLines)}
              </span>
            </div>
          ))}
          {editable && (
            <textarea
              ref={textareaRef}
              className="files-code-input"
              value={text}
              wrap={wordWrap ? "soft" : "off"}
              spellCheck={false}
              autoCapitalize="off"
              autoCorrect="off"
              aria-label={`Edit ${path.split("/").pop() ?? path}`}
              onChange={(event) => {
                onChange(event.target.value);
                measureCaretLine();
              }}
              onKeyUp={measureCaretLine}
              onClick={measureCaretLine}
              onSelect={measureCaretLine}
            />
          )}
        </div>
      </div>
      {/* The floating rails replace the native scrollbars (popover.rs's
          scrollbar pair — the code plane's share of the 21/25 debt). */}
      <MenuScrollbar scrollRef={scrollRef} />
      <HorizontalScrollbar scrollRef={scrollRef} />
    </div>
  );
}

/**
 * Ticket 23's seam: one gutter cell per visible row — the line number
 * today; a per-row comment affordance slots in beside it later without
 * touching the row loop above.
 */
function renderGutterCell(lineIndex: number): ReactNode {
  return (
    <span className="files-code-gutter" aria-hidden>
      {lineIndex + 1}
    </span>
  );
}

function renderTokens(line: string, index: number, tokenLines: readonly (readonly SyntaxToken[])[]): ReactNode[] {
  const tokens = index < tokenLines.length ? tokenLines[index] : undefined;
  if (tokens === undefined || tokens.length === 0) {
    // An empty line still holds its row height (white-space: pre collapses
    // an empty line box without a placeholder).
    return ["\u00a0"];
  }
  return tokens.map((token, tokenIndex) => (
    <span key={tokenIndex} className={token.role === null ? undefined : `tk-${token.role}`}>
      {token.text}
    </span>
  ));
}
