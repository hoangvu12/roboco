import { useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import type { CheckoutDiff } from "@roboco/proto";
import {
  estimateRowHeight,
  fileCounts,
  flattenFileRows,
  gutterWidth,
  parseKey,
  parsePatch,
  splitPairs,
  type DiffLine,
  type DiffRow,
  type FileDiff,
  type Hunk,
} from "../lib/diff";
import { highlightCode, splitTokenLines } from "../lib/syntax";

/**
 * The diff viewer — file headers + hunks + per-line rows, virtualized at
 * line granularity. The desktop's right-pane Changes tab is the reference
 * (`crates/ui/src/changes.rs`): per-file collapse removes the body rows
 * from the list outright (not display:none), nowrap sections collapse with
 * a height tween on a clipped stand-in row, and syntax highlighting uses
 * the same role set the transcript code blocks use.
 *
 * The viewer takes a parsed `CheckoutDiff`. The page (which owns the watch)
 * is responsible for parsing — this component stays free of wire I/O.
 *
 * Responsive: at phone widths the gutter and marker column collapse, line
 * content wraps, and the file header's counts row stacks under the path.
 */

const FILE_HEADER_HEIGHT = 38;
const HUNK_HEADER_HEIGHT = 28;
const LINE_HEIGHT = 21;
const NOTICE_HEIGHT = 24;
const BODY_BOTTOM_PAD = 8;
const MIN_GUTTER_WIDTH = 36;
const SPLIT_MARKER_WIDTH = 18;
const UNIFIED_MARKER_WIDTH = 24;
const SPLIT_DIVIDER_WIDTH = 1;
const UNIFIED_CODE_PADDING_LEFT = 12;
const SPLIT_CODE_PADDING_LEFT = 6;
const CODE_PADDING_RIGHT = 24;
const TEXT_SIZE = 12;

export type DiffLayout = "unified" | "split";

export interface DiffViewProps {
  readonly diff: CheckoutDiff;
  readonly layout?: DiffLayout;
  readonly wrap?: boolean;
  /** Per-file collapsed state, keyed by file path. Initial = expanded. */
  readonly collapsed?: ReadonlySet<string>;
  readonly onToggleCollapse?: (path: string) => void;
}

export function DiffView({ diff, layout = "unified", wrap = false, collapsed, onToggleCollapse }: DiffViewProps) {
  const cacheRef = useRef(new Map<string, FileDiff[]>());
  const cache = cacheRef.current;

  const key = useMemo(() => parseKey(diff.checkoutId, diff.checksum, "workingTree", null), [diff.checkoutId, diff.checksum]);
  let files = cache.get(key);
  if (files === undefined) {
    files = parsePatch(diff.patch);
    cache.set(key, files);
  }

  return (
    <DiffSurface
      files={files}
      layout={layout}
      wrap={wrap}
      collapsed={collapsed}
      onToggleCollapse={onToggleCollapse}
    />
  );
}

interface DiffSurfaceProps {
  readonly files: readonly FileDiff[];
  readonly layout: DiffLayout;
  readonly wrap: boolean;
  readonly collapsed?: ReadonlySet<string>;
  readonly onToggleCollapse?: (path: string) => void;
}

function DiffSurface({ files, layout, wrap, collapsed, onToggleCollapse }: DiffSurfaceProps) {
  const isExpanded = (file: FileDiff): boolean => collapsed === undefined || !collapsed.has(file.path);
  const rows: DiffRow[] = useMemo(() => {
    const out: DiffRow[] = [];
    for (let ix = 0; ix < files.length; ix += 1) {
      const file = files[ix]!;
      out.push(...flattenFileRows(file, ix, isExpanded(file)));
    }
    return out;
  }, [files, collapsed]);

  return (
    <DiffScroller
      rows={rows}
      layout={layout}
      wrap={wrap}
      isExpanded={isExpanded}
      onToggleCollapse={onToggleCollapse}
    />
  );
}

interface ScrollerProps {
  readonly rows: readonly DiffRow[];
  readonly layout: DiffLayout;
  readonly wrap: boolean;
  readonly isExpanded: (file: FileDiff) => boolean;
  readonly onToggleCollapse?: (path: string) => void;
}

function DiffScroller({ rows, layout, wrap, isExpanded, onToggleCollapse }: ScrollerProps) {
  const scrollerRef = useRef<HTMLDivElement | null>(null);
  const heightsRef = useRef(new Map<string, number>());
  const positionsRef = useRef<readonly number[]>([]);
  const [view, setView] = useState({ top: 0, height: 0 });
  const [, bumpMeasure] = useState(0);

  const heights = heightsRef.current;
  if (heights.size > rows.length + 256) {
    const live = new Set(rows.map((row) => row.id));
    for (const id of heights.keys()) {
      if (!live.has(id)) {
        heights.delete(id);
      }
    }
  }

  const positions: number[] = new Array(rows.length + 1);
  positions[0] = 0;
  for (let ix = 0; ix < rows.length; ix += 1) {
    const row = rows[ix]!;
    const measured = heights.get(row.id);
    const height = measured !== undefined ? measured : estimateRowHeight(row);
    positions[ix + 1] = positions[ix]! + height;
  }
  positionsRef.current = positions;

  useLayoutEffect(() => {
    const scroller = scrollerRef.current;
    if (scroller === null) {
      return;
    }
    const update = (): void => {
      setView({ top: scroller.scrollTop, height: scroller.clientHeight });
    };
    update();
    const onScroll = (): void => update();
    scroller.addEventListener("scroll", onScroll, { passive: true });
    const observer = new ResizeObserver(() => update());
    observer.observe(scroller);
    return () => {
      scroller.removeEventListener("scroll", onScroll);
      observer.disconnect();
    };
  }, []);

  useEffect(() => {
    const total = positions[positions.length - 1] ?? 0;
    const scroller = scrollerRef.current;
    if (scroller === null) {
      return;
    }
    const overflow = total - scroller.clientHeight;
    if (overflow > 0 && scroller.scrollTop > overflow) {
      scroller.scrollTop = overflow;
    }
  }, [positions]);

  if (rows.length === 0) {
    return null;
  }

  const first = findRowAt(rows, positions, view.top);
  const last = findRowAt(rows, positions, view.top + view.height);
  const total = positions[positions.length - 1] ?? 0;
  const padTop = first > 0 ? positions[first]! : 0;
  const padBottom = last + 1 < rows.length ? (positions[positions.length - 1] ?? 0) - positions[last + 1]! : 0;

  return (
    <div
      className={`diff-view ${layout === "split" ? "diff-view-split" : "diff-view-unified"} ${wrap ? "diff-view-wrap" : ""}`}
      ref={scrollerRef}
    >
      <div className="diff-spacer" style={{ height: total }} aria-hidden>
        <div className="diff-window" style={{ transform: `translateY(${padTop}px)` }}>
          {rows.slice(first, last + 1).map((row) => {
            const id = row.id;
            const measured = heights.get(id);
            const height = measured !== undefined ? measured : estimateRowHeight(row);
            return (
              <div
                key={id}
                data-row-id={id}
                ref={(el) => {
                  if (el === null) {
                    return;
                  }
                  const next = el.getBoundingClientRect().height;
                  if (Math.abs((heights.get(id) ?? -1) - next) > 0.5) {
                    heights.set(id, next);
                    bumpMeasure((n) => n + 1);
                  }
                }}
                style={{ minHeight: height }}
              >
                <RowContent row={row} layout={layout} wrap={wrap} isExpanded={isExpanded} onToggleCollapse={onToggleCollapse} />
              </div>
            );
          })}
        </div>
        <div style={{ height: padBottom }} aria-hidden />
      </div>
    </div>
  );
}

function findRowAt(rows: readonly DiffRow[], positions: readonly number[], offset: number): number {
  let lo = 0;
  let hi = rows.length - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >>> 1;
    if ((positions[mid] ?? 0) <= offset) {
      lo = mid;
    } else {
      hi = mid - 1;
    }
  }
  return lo;
}

interface RowContentProps {
  readonly row: DiffRow;
  readonly layout: DiffLayout;
  readonly wrap: boolean;
  readonly isExpanded: (file: FileDiff) => boolean;
  readonly onToggleCollapse?: (path: string) => void;
}

function RowContent({ row, layout, wrap, isExpanded, onToggleCollapse }: RowContentProps) {
  switch (row.kind) {
    case "fileHeader":
      return (
        <FileHeaderRow
          file={row.file}
          expanded={row.expanded}
          onToggle={() => onToggleCollapse?.(row.file.path)}
        />
      );
    case "hunkHeader":
      return <HunkHeaderRow file={row.file} hunkIx={row.hunkIx} />;
    case "notice":
      return <NoticeRow file={row.file} messageIx={row.messageIx} />;
    case "line":
      return (
        <LineRow
          file={row.file}
          hunkIx={row.hunkIx}
          lineIx={row.lineIx}
          layout={layout}
          wrap={wrap}
        />
      );
  }
}

/**
 * The file header: chevron, path, counts. No status word and no index chip —
 * the desktop has neither; a file's added/deleted/renamed state is carried by
 * the notice row alone.
 */
function FileHeaderRow({ file, expanded, onToggle }: { file: FileDiff; expanded: boolean; onToggle: () => void }) {
  return (
    <div className={`diff-file-header ${expanded ? "diff-file-expanded" : "diff-file-collapsed"}`}>
      <button type="button" className="diff-file-button" onClick={onToggle} aria-expanded={expanded}>
        <span className="diff-chevron">{expanded ? "▾" : "▸"}</span>
        <span className="diff-file-path mono">
          {file.oldPath !== null ? <span className="diff-file-rename">{file.oldPath} → </span> : null}
          {file.path}
        </span>
        <span className="diff-file-counts mono">{fileCounts(file)}</span>
      </button>
    </div>
  );
}

function HunkHeaderRow({ file, hunkIx }: { file: FileDiff; hunkIx: number }) {
  const hunk = file.hunks[hunkIx] as Hunk | undefined;
  if (hunk === undefined) {
    return null;
  }
  return (
    <div className="diff-hunk-header mono" aria-hidden>
      {hunk.header}
    </div>
  );
}

function NoticeRow({ file, messageIx }: { file: FileDiff; messageIx: number }) {
  const message = file.notices[messageIx];
  if (message === undefined) {
    return null;
  }
  return (
    <div className="diff-notice">
      {message}
    </div>
  );
}

function LineRow({ file, hunkIx, lineIx, layout, wrap }: { file: FileDiff; hunkIx: number; lineIx: number; layout: DiffLayout; wrap: boolean }) {
  const hunk = file.hunks[hunkIx];
  const line = hunk?.lines[lineIx];
  const tokens = useMemo(() => (line !== undefined ? tokenize(file.path, line.text) : EMPTY_TOKENS), [file.path, line]);
  if (hunk === undefined || line === undefined) {
    return null;
  }
  if (layout === "split") {
    return <SplitLineRow file={file} hunk={hunk} lineIx={lineIx} tokens={tokens} wrap={wrap} />;
  }
  return <UnifiedLineRow file={file} line={line} tokens={tokens} wrap={wrap} />;
}

const EMPTY_TOKENS: { lines: { text: string; role: string | null }[][] } = { lines: [] };

function UnifiedLineRow({ file, line, tokens, wrap }: { file: FileDiff; line: DiffLine; tokens: { lines: { text: string; role: string | null }[][] }; wrap: boolean }) {
  const gutter = gutterWidth(file);
  return (
    <div className={`diff-line diff-line-${line.kind} ${wrap ? "diff-line-wrap" : ""}`}>
      <span className="diff-line-old mono">{formatLineNo(line.oldNo)}</span>
      <span className="diff-line-new mono">{formatLineNo(line.newNo)}</span>
      <span className="diff-line-marker">{markerFor(line.kind)}</span>
      <span className="diff-line-text mono" style={textStyle(gutter, "unified", wrap)}>
        {tokens.lines.map((tokenLine, ix) => (
          <span key={ix} className="diff-line-row">
            {tokenLine.map((token, tokenIx) => (
              <span key={tokenIx} className={token.role !== null ? `tk-${token.role}` : undefined}>
                {token.text}
              </span>
            ))}
            {ix === tokens.lines.length - 1 ? null : "\n"}
          </span>
        ))}
      </span>
    </div>
  );
}

function SplitLineRow({ file, hunk, lineIx, tokens, wrap }: { file: FileDiff; hunk: Hunk; lineIx: number; tokens: { lines: { text: string; role: string | null }[][] }; wrap: boolean }) {
  const pairs = useMemo(() => splitPairs(hunk.lines), [hunk.lines]);
  const pair = pairs[lineIx] ?? ([null, null] as const);
  const gutter = gutterWidth(file);
  const leftIx = pair[0];
  const rightIx = pair[1];
  const leftLine = leftIx !== null ? hunk.lines[leftIx] ?? null : null;
  const rightLine = rightIx !== null ? hunk.lines[rightIx] ?? null : null;
  return (
    <div className={`diff-line diff-line-split ${wrap ? "diff-line-wrap" : ""}`}>
      <span className="diff-split-side diff-split-old">
        <span className="diff-line-old mono">{formatLineNo(leftLine?.oldNo ?? null)}</span>
        <span className="diff-line-marker">{leftLine !== null ? markerFor(leftLine.kind) : ""}</span>
        <span className="diff-line-text mono" style={textStyle(gutter, "split-old", wrap)}>
          {leftLine !== null ? <LineText text={leftLine.text} tokens={tokens} /> : null}
        </span>
      </span>
      <span className="diff-split-divider" aria-hidden />
      <span className="diff-split-side diff-split-new">
        <span className="diff-line-old mono">{formatLineNo(rightLine?.newNo ?? null)}</span>
        <span className="diff-line-marker">{rightLine !== null ? markerFor(rightLine.kind) : ""}</span>
        <span className="diff-line-text mono" style={textStyle(gutter, "split-new", wrap)}>
          {rightLine !== null ? <LineText text={rightLine.text} tokens={tokens} /> : null}
        </span>
      </span>
    </div>
  );
}

function LineText({ text, tokens }: { text: string; tokens: { lines: { text: string; role: string | null }[][] } }) {
  const lines = tokens.lines;
  return (
    <>
      {lines.map((tokenLine, ix) => (
        <span key={ix} className="diff-line-row">
          {tokenLine.map((token, tokenIx) => (
            <span key={tokenIx} className={token.role !== null ? `tk-${token.role}` : undefined}>
              {token.text}
            </span>
          ))}
          {ix === lines.length - 1 ? null : "\n"}
        </span>
      ))}
    </>
  );
}

function markerFor(kind: DiffLine["kind"]): string {
  switch (kind) {
    case "add":
      return "+";
    case "del":
      return "−";
    case "context":
      return " ";
    case "meta":
      return "\\";
  }
}

function formatLineNo(no: number | null): string {
  return no === null ? "" : String(no);
}

function tokenize(path: string, text: string): { lines: { text: string; role: string | null }[][] } {
  const language = languageFor(path);
  const tokens = highlightCode(text, language);
  return { lines: splitTokenLines(tokens) };
}

/** Map a file path to the syntax language id used by `lib/syntax.ts`. */
export function languageFor(path: string): string | null {
  const slash = path.lastIndexOf("/");
  const base = path.slice(slash + 1);
  const dot = base.lastIndexOf(".");
  if (dot < 0 || dot === base.length - 1) {
    return null;
  }
  return base.slice(dot + 1).toLowerCase();
}

function textStyle(gutter: number, side: "unified" | "split-old" | "split-new", wrap: boolean): CSSProperties {
  if (wrap) {
    return {};
  }
  const totalGutter = Math.max(gutter, MIN_GUTTER_WIDTH);
  const padding = side === "unified" ? UNIFIED_CODE_PADDING_LEFT : SPLIT_CODE_PADDING_LEFT;
  return { paddingLeft: padding, width: `calc(100% - ${padding}px - ${CODE_PADDING_RIGHT}px)` };
}

export const DIFF_METRICS = {
  FILE_HEADER_HEIGHT,
  HUNK_HEADER_HEIGHT,
  LINE_HEIGHT,
  NOTICE_HEIGHT,
  BODY_BOTTOM_PAD,
  MIN_GUTTER_WIDTH,
  SPLIT_MARKER_WIDTH,
  UNIFIED_MARKER_WIDTH,
  SPLIT_DIVIDER_WIDTH,
  UNIFIED_CODE_PADDING_LEFT,
  SPLIT_CODE_PADDING_LEFT,
  CODE_PADDING_RIGHT,
  TEXT_SIZE,
} as const;
