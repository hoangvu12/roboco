import {
  memo,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from "react";
import type { EngineClient } from "@roboco/engine-client";
import { methods } from "@roboco/engine-client";
import type { ContextUsage, FetchToolBlobReply, SessionMessageEntry } from "@roboco/proto";
import {
  echoStore,
  pendingSendStatus,
  TranscriptStore,
  type PendingSend,
} from "../state/transcript-store";
import { useNow } from "../state/hooks";
import { withAttachments } from "../lib/attachments";
import { MarkdownCache, PENDING_LINK_URL, blockFlatText, type Block, type InlineRun } from "../lib/markdown";
import {
  formatTimestamp,
  isSubagentSpawn,
  rowsForEntry,
  subagentModel,
  toolChipContent,
  toolGroupTitle,
  topGapFor,
  userMessageNeedsCollapse,
  visibleRowWindow,
  type ToolItem,
  type TranscriptRow,
} from "../lib/transcript";
import { OVERDRAW_PX } from "../lib/stick-spring";
import { VeilTracker } from "../lib/veil";
import type { ChatIndicator } from "../lib/view";
import { MarkdownBlockView } from "./markdown";
import { StickController } from "./stick-controller";
import { SubagentDialog } from "./subagent-dialog";
import { UserAttachments } from "./attachments/user-attachments";
import { MatrixSpinner } from "./glyph-spinner";

/**
 * The chat transcript — virtualization at block granularity over the row model
 * of `../lib/transcript.ts` (the desktop's `rows_for_entry` port), with the
 * stick-to-bottom spring driving the scroller. Rows are accounted by measured
 * heights (estimates until rendered, 320px overdraw), so a thousand-message
 * chat keeps a bounded DOM and a stable viewport.
 */

/** Transcript column max width (desktop MAX_CONTENT_WIDTH, 46rem). */
const MAX_CONTENT_WIDTH = 736;
/** Line cap for a FETCHED full output (defensive; desktop FULL_OUTPUT_MAX_LINES). */
const FULL_OUTPUT_MAX_LINES = 400;

/** The jump pill's visibility + action, published up to the chat page. */
export interface JumpButtonState {
  readonly shown: boolean;
  readonly jump: () => void;
}

export function TranscriptView({
  client,
  docId,
  deviceId,
  onContextUsage,
  onRetrySend,
  onJumpChange,
}: {
  client: EngineClient;
  docId: string;
  deviceId: string | null;
  /**
   * Replicated context occupancy, published as it changes. The composer's
   * footer draws it (the desktop's `render_footer`), but this store owns the
   * only subscription that carries it — a second watch for one number would
   * be a second live stream per open chat.
   */
  onContextUsage?: (usage: ContextUsage | null) => void;
  /** Re-send an echo the grace window declared undelivered (§2.3's retry). */
  onRetrySend?: (send: PendingSend) => void;
  /**
   * The jump-to-bottom button's live state (`transcript.jump_button_shown()`).
   * The pill itself lives over the composer — outside this component — so the
   * chat page owns where it renders; this callback is how it learns.
   */
  onJumpChange?: (state: JumpButtonState) => void;
}) {
  const [store, setStore] = useState<TranscriptStore | null>(null);
  useEffect(() => {
    const created = new TranscriptStore(client, docId);
    setStore(created);
    return () => {
      created.dispose();
      setStore((current) => (current === created ? null : current));
    };
  }, [client, docId]);
  // The desktop transcript renders NOTHING while it has no document — the
  // shell owns the empty/loading case, not this component.
  if (store === null) {
    return null;
  }
  return (
    <TranscriptSurface
      key={store.docId}
      store={store}
      client={client}
      deviceId={deviceId}
      onContextUsage={onContextUsage}
      onRetrySend={onRetrySend}
      onJumpChange={onJumpChange}
    />
  );
}

function TranscriptSurface({
  store,
  client,
  deviceId,
  onContextUsage,
  onRetrySend,
  onJumpChange,
}: {
  store: TranscriptStore;
  client: EngineClient;
  deviceId: string | null;
  onContextUsage?: (usage: ContextUsage | null) => void;
  onRetrySend?: (send: PendingSend) => void;
  onJumpChange?: (state: JumpButtonState) => void;
}) {
  const subscribe = useCallback((listener: () => void) => store.subscribe(listener), [store]);
  const getSnapshot = useCallback(() => store.getSnapshot(), [store]);
  const snapshot = useSyncExternalStore(subscribe, getSnapshot);

  const usage = snapshot.contextUsage;
  useEffect(() => {
    onContextUsage?.(usage);
  }, [usage, onContextUsage]);

  const parseCacheRef = useRef<MarkdownCache | null>(null);
  if (parseCacheRef.current === null) {
    parseCacheRef.current = new MarkdownCache();
  }
  const entryRowsCacheRef = useRef(new Map<string, { entry: SessionMessageEntry; rows: TranscriptRow[] }>());

  // Rows are rebuilt per entry only when the entry's identity changes; the
  // parse cache keeps settled markdown trees shared across stream ticks.
  const rows = useMemo(() => {
    const cache = entryRowsCacheRef.current;
    const markdown = parseCacheRef.current!;
    // Bound the caches: a long-lived session re-parses after a prune.
    if (cache.size > 4096) {
      cache.clear();
    }
    const out: TranscriptRow[] = [];
    for (const entry of snapshot.entries) {
      let hit = cache.get(entry.id);
      if (hit === undefined || hit.entry !== entry) {
        hit = {
          entry,
          rows: rowsForEntry(entry, { parse: (key, text, live) => markdown.parse(key, text, live) }),
        };
        cache.set(entry.id, hit);
      }
      out.push(...hit.rows);
    }
    return out;
  }, [snapshot.entries]);

  // The optimistic echo overlay (`AppState.echoes`). Each still-unconfirmed
  // send renders as an ORDINARY user bubble at the end of the list — the
  // position the real row will take the moment the host writes it back — so
  // the confirmation is a silent swap rather than a visible hand-off. Never a
  // section of its own.
  const docId = store.docId;
  const subscribeEchoes = useCallback((listener: () => void) => echoStore.subscribe(listener), []);
  const echoSnapshot = useCallback(() => echoStore.forChat(docId), [docId]);
  const pendingSends = useSyncExternalStore(subscribeEchoes, echoSnapshot);
  // The grace window is the only thing that changes without an event, so the
  // overlay needs a clock of its own to flip pending → undelivered.
  const now = useNow(ECHO_TICK_MS);

  const allRows = useMemo(() => {
    if (pendingSends.length === 0) {
      return rows;
    }
    const markdown = parseCacheRef.current!;
    // Belt-and-braces against a duplicated bubble: the ack rides the same
    // frame that adds the real row, but the two live in different stores, so
    // never render an echo for an id the doc already carries.
    const confirmed = new Set(snapshot.entries.map((entry) => entry.id));
    const echoRows: TranscriptRow[] = [];
    for (const send of pendingSends) {
      if (confirmed.has(send.messageId)) {
        continue;
      }
      const built = rowsForEntry(echoEntry(send, deviceId), {
        pending: true,
        parse: (key, text, live) => markdown.parse(key, text, live),
      });
      const row = built[0];
      if (row === undefined || row.rowKind.kind !== "user") {
        continue;
      }
      const undelivered = pendingSendStatus(send, now) === "undelivered";
      echoRows.push({
        ...row,
        // Keep the diff key sensitive to the status flip so the row repaints.
        version: row.version * 2 + (undelivered ? 1 : 0),
        rowKind: { ...row.rowKind, undelivered },
      });
    }
    return echoRows.length === 0 ? rows : [...rows, ...echoRows];
  }, [rows, snapshot.entries, pendingSends, deviceId, now]);

  return (
    <TranscriptScroller
      rows={allRows}
      streaming={snapshot.streaming}
      loaded={snapshot.loaded}
      error={snapshot.error}
      onRetry={() => store.resubscribe()}
      client={client}
      deviceId={deviceId}
      onRetryRow={(messageId) => {
        const send = pendingSends.find((entry) => entry.messageId === messageId);
        if (send !== undefined) {
          onRetrySend?.(send);
        }
      }}
      onJumpChange={onJumpChange}
    />
  );
}

/** How often the echo overlay re-checks the grace window. */
const ECHO_TICK_MS = 10_000;

/**
 * A pending send dressed as the transcript entry the host will eventually
 * write — same id (that is the whole dedupe contract), same author, same
 * attachment-refs trailer — so the row model builds an ordinary user bubble
 * from it and the confirmed row replaces it with no visual change.
 */
function echoEntry(send: PendingSend, deviceId: string | null): SessionMessageEntry {
  return {
    id: send.messageId,
    role: "user",
    parts: [
      {
        kind: "text",
        id: `${send.messageId}#echo`,
        text: withAttachments(send.text, send.attachmentPaths),
      },
    ],
    createdAt: send.startedAtMs,
    deviceId: deviceId ?? "",
  };
}

// ---------------------------------------------------------------------------
// Scroller: virtualization + stick-to-bottom
// ---------------------------------------------------------------------------

interface ScrollerProps {
  readonly rows: readonly TranscriptRow[];
  readonly streaming: boolean;
  readonly loaded: boolean;
  readonly error: string | null;
  readonly onRetry: () => void;
  readonly client: EngineClient;
  readonly deviceId: string | null;
  /** Retry an undelivered echo, by its message id (= the row's entry id). */
  readonly onRetryRow: (messageId: string) => void;
  readonly onJumpChange?: (state: JumpButtonState) => void;
}

/** Capture the first visible row + its pixel offset — the escape anchor. */
function captureAnchor(
  top: number,
  rows: readonly TranscriptRow[],
  positions: readonly number[],
  heights: ReadonlyMap<string, number>,
): { id: string; offset: number } | null {
  for (let ix = 0; ix < rows.length; ix++) {
    const rowTop = positions[ix]!;
    const bottom = rowTop + (heights.get(rows[ix]!.id) ?? estimateRowHeight(rows[ix]!));
    if (bottom > top + 1) {
      return { id: rows[ix]!.id, offset: top - rowTop };
    }
  }
  return null;
}

/** First-frame estimate per row kind; measurement corrects on render. */
function estimateRowHeight(row: TranscriptRow): number {
  const kind = row.rowKind;
  switch (kind.kind) {
    case "user": {
      const lines = Math.min(Math.max(1, kind.text.split("\n").length), 5);
      return 20 + lines * 22 + 8;
    }
    case "markdown":
    case "liveMarkdown": {
      const block = kind.tree.blocks[kind.blockIx]?.block;
      if (block !== undefined && block.kind === "codeBlock") {
        // Code rows are nowrap: the height is analytic (render.rs constants).
        return block.code.split("\n").length * 18 + 28;
      }
      return 30;
    }
    case "toolGroup":
      return 34;
    case "inputChip":
    case "errorChip":
      return 38;
  }
}

function TranscriptScroller({
  rows,
  streaming,
  loaded,
  error,
  onRetry,
  client,
  deviceId,
  onRetryRow,
  onJumpChange,
}: ScrollerProps) {
  const scrollerRef = useRef<HTMLDivElement | null>(null);
  const heightsRef = useRef(new Map<string, number>());
  const anchorRef = useRef<{ id: string; offset: number } | null>(null);
  const rowsRef = useRef(rows);
  const positionsRef = useRef<readonly number[]>([]);
  const [, bumpMeasure] = useState(0);
  const [view, setView] = useState({ top: 0, height: 0 });
  const [showJump, setShowJump] = useState(false);
  const [hoveredEntry, setHoveredEntry] = useState<string | null>(null);
  const [subagentDoc, setSubagentDoc] = useState<string | null>(null);

  const stickRef = useRef<StickController | null>(null);
  if (stickRef.current === null) {
    stickRef.current = new StickController({ onJumpVisibility: setShowJump });
  }
  const stick = stickRef.current;

  // Prune measured heights for rows that no longer exist.
  const heights = heightsRef.current;
  if (heights.size > rows.length + 256) {
    const live = new Set(rows.map((row) => row.id));
    for (const id of heights.keys()) {
      if (!live.has(id)) {
        heights.delete(id);
      }
    }
  }

  // Row positions: prefix sums over measured heights (estimates until rendered).
  const positions: number[] = new Array(rows.length);
  const rowHeights: number[] = new Array(rows.length);
  let total = 0;
  for (let ix = 0; ix < rows.length; ix++) {
    positions[ix] = total;
    const height = heights.get(rows[ix]!.id) ?? estimateRowHeight(rows[ix]!);
    rowHeights[ix] = height;
    total += height;
  }
  rowsRef.current = rows;
  positionsRef.current = positions;

  // Shared ResizeObserver: row heights land here (border-box, so the gap
  // padding is included) — one bump per batch.
  const observerRef = useRef<ResizeObserver | null>(null);
  if (observerRef.current === null && typeof ResizeObserver !== "undefined") {
    observerRef.current = new ResizeObserver((entries) => {
      let changed = false;
      for (const entry of entries) {
        const el = entry.target as HTMLDivElement;
        const id = el.dataset["rid"];
        if (id === undefined) {
          continue;
        }
        const height = entry.borderBoxSize?.[0]?.blockSize ?? el.getBoundingClientRect().height;
        if (Math.abs((heightsRef.current.get(id) ?? 0) - height) > 0.5) {
          heightsRef.current.set(id, height);
          changed = true;
        }
      }
      if (changed) {
        bumpMeasure((tick) => tick + 1);
      }
    });
  }
  const rowElsRef = useRef(new Map<string, HTMLDivElement>());
  const registerRow = useCallback((id: string, el: HTMLDivElement | null) => {
    const observer = observerRef.current;
    const els = rowElsRef.current;
    if (observer === null) {
      return;
    }
    if (el === null) {
      const prev = els.get(id);
      if (prev !== undefined) {
        observer.unobserve(prev);
        els.delete(id);
      }
      return;
    }
    els.set(id, el);
    observer.observe(el);
  }, []);

  // Attach/detach the stick controller and the viewport listeners.
  useLayoutEffect(() => {
    const el = scrollerRef.current;
    if (el === null) {
      return;
    }
    stick.attach(el);
    setView({ top: el.scrollTop, height: el.clientHeight });
    let raf = 0;
    const onScroll = (): void => {
      anchorRef.current = captureAnchor(el.scrollTop, rowsRef.current, positionsRef.current, heightsRef.current);
      if (raf !== 0) {
        return;
      }
      raf = requestAnimationFrame(() => {
        raf = 0;
        setView({ top: el.scrollTop, height: el.clientHeight });
      });
    };
    el.addEventListener("scroll", onScroll, { passive: true });
    const resize = new ResizeObserver(() => {
      stick.kick();
      setView({ top: el.scrollTop, height: el.clientHeight });
    });
    resize.observe(el);
    const observer = observerRef.current;
    return () => {
      el.removeEventListener("scroll", onScroll);
      resize.disconnect();
      observer?.disconnect();
      stick.detach();
      if (raf !== 0) {
        cancelAnimationFrame(raf);
      }
    };
  }, [stick]);

  // Keep the controller's live-anchor input current.
  useEffect(() => {
    stick.setStreaming(streaming);
  }, [stick, streaming]);

  // The desktop's `on_own_send` (transcript.rs:3330-3355): your own send
  // takes viewport ownership — the just-sent prompt and its reply are what
  // you asked for, so the view follows them even from an escaped position.
  // The web's minimal port: a NEWLY-appended pending echo row engages the
  // pin (glide, teleport past 2.5 viewports — `engage_pin`). The own-turn
  // hold/reservation geometry rides the underlay port (ticket 18/06).
  const ownSendsRef = useRef(new Set<string>());
  useLayoutEffect(() => {
    let ownSend = false;
    for (const row of rows) {
      if (row.rowKind.kind === "user" && row.rowKind.pending && !ownSendsRef.current.has(row.id)) {
        ownSendsRef.current.add(row.id);
        ownSend = true;
      }
    }
    if (ownSend) {
      stick.jumpToBottom();
    }
  }, [rows, stick]);

  // Publish the jump button's state up to the chat page, which renders the
  // pill over the composer (`render_jump_to_bottom` floats outside the
  // transcript's fade, anchored above the composer stack). The cleanup hides
  // it again when the surface unmounts (chat switch).
  useEffect(() => {
    const state: JumpButtonState = { shown: showJump, jump: () => stickRef.current?.jumpToBottom() };
    onJumpChange?.(state);
    return () => onJumpChange?.({ ...state, shown: false });
  }, [showJump, onJumpChange]);

  // Initial load: open at the end (desktop opens a transcript at the bottom).
  const snappedRef = useRef(false);
  useLayoutEffect(() => {
    const el = scrollerRef.current;
    if (el === null || snappedRef.current || !loaded) {
      return;
    }
    snappedRef.current = true;
    stick.snapToEnd();
    setView({ top: el.scrollTop, height: el.clientHeight });
  }, [stick, loaded]);

  // After every commit: pinned → let the spring track growth; escaped → keep
  // the captured anchor row visually stationary across splices and measures.
  useLayoutEffect(() => {
    const el = scrollerRef.current;
    if (el === null) {
      return;
    }
    if (stick.pinned) {
      stick.kick();
      return;
    }
    const anchor = anchorRef.current;
    if (anchor === null) {
      return;
    }
    const ix = rows.findIndex((row) => row.id === anchor.id);
    if (ix < 0) {
      return;
    }
    const target = positions[ix]! + anchor.offset;
    if (Math.abs(el.scrollTop - target) > 0.5) {
      stick.writePreserving(target);
    }
  });

  const openSubagent = useCallback((doc: string) => setSubagentDoc(doc), []);

  // Empty transcript: nothing at all, as on the desktop. The shell's new-chat
  // hero (ticket 15) is what will occupy this space.
  if (loaded && rows.length === 0 && error === null) {
    return null;
  }

  // The visible window, with the desktop's 320px overdraw on both ends.
  const { first, last } = visibleRowWindow(positions, rowHeights, view.top, view.height, OVERDRAW_PX);
  const visible = rows.slice(first, last + 1);
  const topPad = positions[first] ?? total;
  const lastRow = last >= 0 ? rows[last]! : null;
  const bottomPad =
    lastRow === null ? 0 : total - (positions[last]! + (heights.get(lastRow.id) ?? estimateRowHeight(lastRow)));

  return (
    <div className="transcript-wrap">
      <div className="transcript" ref={scrollerRef}>
        <div style={{ height: topPad }} aria-hidden />
        <div className="transcript-col" style={{ maxWidth: MAX_CONTENT_WIDTH }}>
          {visible.map((row, offset) => {
            const ix = first + offset;
            const gap = topGapFor(ix > 0 ? rows[ix - 1]! : null, row);
            return (
              <RowShell key={row.id} row={row} gap={gap} register={registerRow} onHover={setHoveredEntry}>
                <RowContent
                  row={row}
                  streaming={streaming}
                  hovered={hoveredEntry === row.entryId}
                  onOpenSubagent={openSubagent}
                  client={client}
                  deviceId={deviceId}
                  onRetryRow={onRetryRow}
                />
              </RowShell>
            );
          })}
        </div>
        <div style={{ height: bottomPad }} aria-hidden />
      </div>
      {/*
        The edge fade is the scroller's own mask now (`.transcript` in
        app.css): the desktop's per-glyph EdgeFade is a mask, not a painted
        overlay, with a 24px quadratic band at the top and a bottom band sized
        to the chrome stack via `--rb-bottom-stack`. No overlay element, and
        nothing reserves the status strip's height here — the strip lives in
        the chat page's bottom stack (ticket 06).
      */}
      {subagentDoc !== null && (
        <SubagentDialog client={client} docId={subagentDoc} deviceId={deviceId} onClose={() => setSubagentDoc(null)} />
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

function RowShell({
  row,
  gap,
  register,
  onHover,
  children,
}: {
  row: TranscriptRow;
  gap: number;
  register: (id: string, el: HTMLDivElement | null) => void;
  onHover: (entryId: string | null) => void;
  children: ReactNode;
}) {
  const ref = useCallback((el: HTMLDivElement | null) => register(row.id, el), [register, row.id]);
  return (
    <div
      ref={ref}
      data-rid={row.id}
      className="trow"
      style={{ paddingTop: gap }}
      onMouseEnter={() => onHover(row.entryId)}
      onMouseLeave={() => onHover(null)}
    >
      {children}
    </div>
  );
}

function RowContent({
  row,
  streaming,
  hovered,
  onOpenSubagent,
  client,
  deviceId,
  onRetryRow,
}: {
  row: TranscriptRow;
  streaming: boolean;
  hovered: boolean;
  onOpenSubagent: (doc: string) => void;
  client: EngineClient;
  deviceId: string | null;
  onRetryRow: (messageId: string) => void;
}) {
  const kind = row.rowKind;
  return (
    <>
      {kind.kind === "user" && (
        <UserRow
          text={kind.text}
          pending={kind.pending}
          undelivered={kind.undelivered === true}
          attachments={kind.attachments}
          client={client}
          deviceId={deviceId}
          // An echo row's id IS the client-minted message id.
          onRetry={() => onRetryRow(row.entryId)}
        />
      )}
      {kind.kind === "markdown" && <MarkdownRow row={row} />}
      {kind.kind === "liveMarkdown" && <LiveMarkdownRow row={row} />}
      {kind.kind === "toolGroup" && (
        <ToolGroupRowView
          tools={kind.tools}
          autoOpen={kind.autoOpen}
          streaming={streaming}
          onOpenSubagent={onOpenSubagent}
          client={client}
        />
      )}
      {kind.kind === "inputChip" && <InputChipRow header={kind.header} resolved={kind.resolved} />}
      {kind.kind === "errorChip" && <ErrorChipRow message={kind.message} />}
      {row.timestamp !== null && <RowMeta row={row} visible={hovered} />}
    </>
  );
}

/** The hover strip under a settled entry's last row: timestamp + copy. */
function RowMeta({ row, visible }: { row: TranscriptRow; visible: boolean }) {
  const [copied, setCopied] = useState(false);
  const copy = (): void => {
    const text = row.copyText;
    const clipboard = globalThis.navigator?.clipboard;
    if (text === null || clipboard === undefined) {
      return;
    }
    void clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    });
  };
  return (
    <div className={`row-meta ${visible ? "row-meta-on" : ""}`}>
      <span className="row-meta-time">{formatTimestamp(row.timestamp ?? 0)}</span>
      {row.copyText !== null && (
        <button type="button" className="row-meta-copy" onClick={copy}>
          {copied ? "Copied" : "Copy"}
        </button>
      )}
    </div>
  );
}

// ── User bubble ─────────────────────────────────────────────────────────────

function UserRow({
  text,
  pending,
  undelivered = false,
  attachments,
  client,
  deviceId,
  onRetry,
}: {
  text: string;
  pending: boolean;
  undelivered?: boolean;
  attachments: readonly import("../lib/attachments").UserImageAttachment[];
  client: EngineClient;
  deviceId: string | null;
  onRetry?: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  // Image-only sends show no bubble (desktop parity); the thumbnail strip
  // above is the whole bubble.
  if (text.trim().length === 0 && attachments.length === 0) {
    return null;
  }
  const collapsible = text.length > 0 && userMessageNeedsCollapse(text);
  const clamped = collapsible && !expanded;
  return (
    <div className="row-user">
      {/*
        Past the grace window the echo stops being quiet: the bubble goes back
        to full opacity and says so, with the retry beside it (`send_undelivered`).
      */}
      <div className={`user-content ${pending && !undelivered ? "user-bubble-pending" : ""}`}>
        <UserAttachments client={client} deviceId={deviceId} attachments={attachments} />
        {text.trim().length > 0 && (
          <div className={`user-bubble ${pending && !undelivered ? "user-bubble-pending" : ""}`}>
            <div className={`user-text ${clamped ? "user-text-clamped" : ""}`}>{text}</div>
            {collapsible === true && (
              <button type="button" className="user-expand" onClick={() => setExpanded((value) => !value)}>
                {expanded ? "▴ Show less" : "▾ Show more"}
              </button>
            )}
          </div>
        )}
        {undelivered && (
          <div className="user-undelivered" role="status">
            <span className="user-undelivered-label">Not delivered</span>
            <button type="button" className="user-undelivered-retry" onClick={onRetry}>
              Retry
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

// ── Markdown rows ───────────────────────────────────────────────────────────

const MarkdownRow = memo(function MarkdownRow({ row }: { row: TranscriptRow }) {
  const kind = row.rowKind;
  if (kind.kind !== "markdown" && kind.kind !== "liveMarkdown") {
    return null;
  }
  const block = kind.tree.blocks[kind.blockIx]?.block;
  if (block === undefined) {
    return null;
  }
  return (
    <div className="row-md">
      <MarkdownBlockView block={block} />
    </div>
  );
});

interface VeilChunk {
  readonly key: string;
  readonly start: number;
  readonly end: number;
  readonly durationMs: number;
}

/**
 * A streaming markdown row with the fade veil: newly appended text dissolves
 * in (opacity only — never a positional offset). Chunk ranges come from the
 * ported `VeilTracker`; attach semantics seed the baseline at mount, so a
 * virtualizer remount never replays the fade over already-visible text.
 */
function LiveMarkdownRow({ row }: { row: TranscriptRow }) {
  const kind = row.rowKind;
  const block = kind.kind === "liveMarkdown" ? kind.tree.blocks[kind.blockIx]?.block : undefined;
  const flat =
    block !== undefined && (block.kind === "paragraph" || block.kind === "heading")
      ? blockFlatText(block)
      : null;
  const trackerRef = useRef<VeilTracker | null>(null);
  const [chunks, setChunks] = useState<readonly VeilChunk[]>([]);

  useLayoutEffect(() => {
    if (flat === null) {
      return;
    }
    if (trackerRef.current === null) {
      const tracker = new VeilTracker();
      tracker.seed(flat);
      trackerRef.current = tracker;
      return;
    }
    const active = trackerRef.current.advance(flat, performance.now());
    setChunks(active.map((chunk) => ({ key: `${chunk.start}:${chunk.started}`, ...chunk })));
  }, [flat]);

  if (block === undefined) {
    return null;
  }

  const dropChunk = (key: string): void => {
    setChunks((current) => current.filter((chunk) => chunk.key !== key));
  };

  return (
    <div className="row-md row-md-live">
      {flat !== null && chunks.length > 0 && (block.kind === "paragraph" || block.kind === "heading") ? (
        <VeiledBlock block={block} chunks={chunks} onChunkEnd={dropChunk} />
      ) : (
        <MarkdownBlockView block={block} />
      )}
    </div>
  );
}

/** A paragraph/heading whose tail chunks fade in (React-managed splits). */
function VeiledBlock({
  block,
  chunks,
  onChunkEnd,
}: {
  block: Extract<Block, { kind: "paragraph" | "heading" }>;
  chunks: readonly VeilChunk[];
  onChunkEnd: (key: string) => void;
}) {
  const pieces = splitRunsForVeil(block.runs, chunks);
  const content = pieces.map((piece, ix) => {
    const inner = <StyledRun run={piece.run} />;
    if (piece.chunk === null) {
      return <span key={`p${ix}`} className="veil-plain">{inner}</span>;
    }
    return (
      <span
        key={piece.chunk.key}
        className="veil-fade"
        style={{ animationDuration: `${piece.chunk.durationMs}ms` }}
        onAnimationEnd={() => onChunkEnd(piece.chunk!.key)}
      >
        {inner}
      </span>
    );
  });
  if (block.kind === "heading") {
    const level = Math.min(6, Math.max(1, block.level));
    const Tag = `h${level}` as "h1" | "h2" | "h3" | "h4" | "h5" | "h6";
    return <Tag className={`md-h md-h${level}`}>{content}</Tag>;
  }
  return <p className="md-p">{content}</p>;
}

/** Split inline runs at chunk boundaries (flat-text coordinates). */
function splitRunsForVeil(
  runs: readonly InlineRun[],
  chunks: readonly VeilChunk[],
): Array<{ run: InlineRun; chunk: VeilChunk | null }> {
  const out: Array<{ run: InlineRun; chunk: VeilChunk | null }> = [];
  let offset = 0;
  for (const run of runs) {
    const runStart = offset;
    const runEnd = offset + run.text.length;
    offset = runEnd;
    const covering = chunks.filter((chunk) => chunk.start < runEnd && chunk.end > runStart);
    if (covering.length === 0) {
      out.push({ run, chunk: null });
      continue;
    }
    const cuts = new Set<number>([runStart, runEnd]);
    for (const chunk of covering) {
      cuts.add(Math.max(runStart, chunk.start));
      cuts.add(Math.min(runEnd, chunk.end));
    }
    const sorted = [...cuts].sort((a, b) => a - b);
    for (let ix = 0; ix + 1 < sorted.length; ix++) {
      const piece: InlineRun = {
        text: run.text.slice(sorted[ix]! - runStart, sorted[ix + 1]! - runStart),
        style: run.style,
      };
      const chunk =
        covering.find((candidate) => candidate.start <= sorted[ix]! && sorted[ix + 1]! <= candidate.end) ?? null;
      if (piece.text.length > 0) {
        out.push({ run: piece, chunk });
      }
    }
  }
  return out;
}

/** One styled inline run (shared by veiled rows and thought details). */
function StyledRun({ run }: { run: InlineRun }) {
  const style = run.style;
  let content: ReactNode = run.text;
  if (style.code) {
    content = <code className="md-code">{content}</code>;
  }
  if (style.bold) {
    content = <strong>{content}</strong>;
  }
  if (style.italic) {
    content = <em>{content}</em>;
  }
  if (style.strikethrough) {
    content = <s>{content}</s>;
  }
  if (style.link !== null && style.link !== undefined) {
    // The live path needs the settled renderer's pending-link guard
    // (markdown.tsx InlineRunView): a half-streamed `[text](` mends to the
    // sentinel URL, which must stay styled-but-inert — never a clickable
    // `roboco:pending-link` anchor (render.rs:986-998).
    if (style.link === PENDING_LINK_URL) {
      content = <span className="md-link md-link-pending">{content}</span>;
    } else {
      content = (
        <a className="md-link" href={style.link} target="_blank" rel="noreferrer noopener">
          {content}
        </a>
      );
    }
  }
  return <>{content}</>;
}

// ── Tool groups and chips ───────────────────────────────────────────────────

function ToolGroupRowView({
  tools,
  autoOpen,
  streaming,
  onOpenSubagent,
  client,
}: {
  tools: readonly ToolItem[];
  autoOpen: boolean;
  streaming: boolean;
  onOpenSubagent: (doc: string) => void;
  client: EngineClient;
}) {
  // Spawn chips stay out of the collapsible wrap so a running subagent is
  // visible without opening the fold; thoughts ride the ordinary fold.
  const collapsible = tools.some((tool) => !isSubagentSpawn(tool.call));
  const [override, setOverride] = useState<boolean | null>(null);
  const open = override ?? autoOpen;
  const active = autoOpen && streaming && tools.some((tool) => !tool.resolved);

  if (!collapsible) {
    return (
      <div className="tool-group">
        {tools.map((tool, ix) => (
          <ToolChipView key={ix} tool={tool} animate={active} stagger={ix} onOpenSubagent={onOpenSubagent} client={client} />
        ))}
      </div>
    );
  }

  return (
    <div className={`tool-group ${open ? "tool-group-open" : ""}`}>
      <button type="button" className="tool-group-header" onClick={() => setOverride(!open)}>
        <span className={`tool-group-chevron ${open ? "tool-group-chevron-open" : ""}`} aria-hidden>
          ▾
        </span>
        <span className={`tool-group-title ${active ? "tool-shimmer" : ""}`}>{toolGroupTitle(tools)}</span>
      </button>
      <div className="tool-group-fold">
        <div className="tool-group-body">
          {tools.map((tool, ix) => (
            <ToolChipView
              key={ix}
              tool={tool}
              animate={active}
              stagger={ix}
              onOpenSubagent={onOpenSubagent}
              client={client}
            />
          ))}
        </div>
      </div>
    </div>
  );
}

function ToolChipView({
  tool,
  animate,
  stagger,
  onOpenSubagent,
  client,
}: {
  tool: ToolItem;
  animate: boolean;
  stagger: number;
  onOpenSubagent: (doc: string) => void;
  client: EngineClient;
}) {
  const { label, detail } = tool.isThought ? { label: "Thought process", detail: "" } : toolChipContent(tool.call);
  const spawn = isSubagentSpawn(tool.call);
  // A thought chip defaults open while its reasoning still streams.
  const [override, setOverride] = useState<boolean | null>(null);
  const open = override ?? (tool.isThought && !tool.resolved);
  const [full, setFull] = useState<{ lines: string[]; truncatedBy: number } | "loading" | "failed" | null>(null);
  const expandable =
    tool.invocation !== null || tool.detail !== null || tool.outputRef !== null || tool.isThought;
  const model = spawn ? subagentModel(tool.call) : null;

  const fetchFull = (): void => {
    if (tool.outputRef === null || full !== null) {
      return;
    }
    setFull("loading");
    client
      .call<FetchToolBlobReply>(methods.FETCH_TOOL_BLOB, { blobRef: tool.outputRef })
      .then((reply) => {
        const lines = reply.text.split("\n");
        while (lines.length > 0 && lines[lines.length - 1]!.trim().length === 0) {
          lines.pop();
        }
        const truncatedBy = Math.max(0, lines.length - FULL_OUTPUT_MAX_LINES);
        setFull({ lines: lines.slice(0, FULL_OUTPUT_MAX_LINES), truncatedBy });
      })
      .catch(() => setFull("failed"));
  };

  const chip = (
    <div
      className={[
        "tool-chip",
        tool.isError ? "tool-chip-error" : "",
        spawn ? "tool-chip-agent" : "",
        tool.isThought ? "tool-chip-thought" : "",
        animate ? "chip-reveal" : "",
      ].join(" ")}
      style={animate ? { animationDelay: `${90 + stagger * 65}ms` } : undefined}
    >
      <div
        className={`tool-chip-head ${expandable ? "tool-chip-head-button" : ""}`}
        onClick={expandable ? () => setOverride(!open) : undefined}
        role={expandable ? "button" : undefined}
        tabIndex={expandable ? 0 : undefined}
        onKeyDown={
          expandable
            ? (event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  setOverride(!open);
                }
              }
            : undefined
        }
      >
        <ToolGlyph tool={tool} />
        <span className="tool-chip-label">{label}</span>
        {detail.length > 0 && <span className="tool-chip-detail">{detail}</span>}
        {/*
          No status dot, no pending dot, no "failed" word: the desktop shows a
          `mini_glyph_spinner` while running, a quiet chip when done, and the
          danger tint when failed. Building that spinner/tint is ticket 19;
          until then the chip carries no run-state indicator at all.
        */}
        {model !== null && <span className="tool-chip-model">{model}</span>}
        {expandable && (
          <span className={`tool-chip-chevron ${open ? "tool-chip-chevron-open" : ""}`} aria-hidden>
            ▾
          </span>
        )}
      </div>
      {open && (
        <div className="tool-chip-body">
          {tool.invocation !== null && tool.invocation.kind === "output" && (
            <pre className="tool-invocation">{tool.invocation.lines.join("\n")}</pre>
          )}
          {tool.detail !== null && tool.detail.kind === "thought" && (
            <div className="tool-output tool-thought">
              {tool.detail.lines.map((line, ix) => (
                <div key={ix} className="tool-output-line">
                  {line.map((run, runIx) => (
                    <StyledRun key={runIx} run={run} />
                  ))}
                </div>
              ))}
              {tool.detail.truncatedBy > 0 && (
                <div className="tool-output-line tool-output-more">. {tool.detail.truncatedBy} more lines</div>
              )}
            </div>
          )}
          {tool.detail !== null && tool.detail.kind === "output" && (
            <pre className="tool-output">
              {tool.detail.lines.join("\n")}
              {tool.detail.truncatedBy > 0 ? `\n. ${tool.detail.truncatedBy} more lines` : ""}
            </pre>
          )}
          {tool.detail !== null && tool.detail.kind === "stats" && (
            <div className="tool-output tool-stats">
              {tool.detail.stats.map((stat, ix) => (
                <div key={ix} className="tool-stat-row">
                  <span className="tool-stat-path">{stat.path}</span>
                  <span className="tool-stat-add">+{stat.additions}</span>
                  <span className="tool-stat-del">−{stat.deletions}</span>
                </div>
              ))}
            </div>
          )}
          {full !== null && full !== "loading" && full !== "failed" && (
            <pre className="tool-output tool-output-full">
              {full.lines.join("\n")}
              {full.truncatedBy > 0 ? `\n. ${full.truncatedBy} more lines` : ""}
            </pre>
          )}
          {full === "loading" && <div className="tool-output-note">Loading full output…</div>}
          {full === "failed" && <div className="tool-output-note">Could not load the full output.</div>}
          {full === null && tool.outputRef !== null && (
            <button type="button" className="tool-full-button" onClick={fetchFull}>
              Show full output{tool.outputBytes !== null ? ` (${formatBytes(tool.outputBytes)})` : ""}
            </button>
          )}
        </div>
      )}
    </div>
  );

  if (spawn && tool.subagentRef !== null) {
    return (
      <div
        className="tool-agent-link"
        role="button"
        tabIndex={0}
        title="Open subagent"
        onClick={() => onOpenSubagent(tool.subagentRef!)}
        onKeyDown={(event) => {
          if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            onOpenSubagent(tool.subagentRef!);
          }
        }}
      >
        {chip}
      </div>
    );
  }
  return chip;
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  return `${(bytes / 1024).toFixed(bytes < 10240 ? 1 : 0)} KB`;
}

/** The per-genus rail glyph (16px, currentColor). */
function ToolGlyph({ tool }: { tool: ToolItem }) {
  if (tool.isThought) {
    return (
      <span className="tool-glyph" data-glyph="thought" aria-hidden>
        ◌
      </span>
    );
  }
  const call = tool.call;
  const glyph = isSubagentSpawn(call)
    ? "⧉"
    : call.kind === "exec"
      ? "❯"
      : call.kind === "readFile"
        ? "≡"
        : call.kind === "writeFile" || call.kind === "editFile" || call.kind === "applyPatch"
          ? "✎"
          : call.kind === "search" || call.kind === "glob"
            ? "⌕"
            : call.kind === "webFetch" || call.kind === "webSearch"
              ? "◍"
              : call.kind === "todo"
                ? "☑"
                : call.kind === "mcp"
                  ? "⬡"
                  : "⚙";
  return (
    <span className="tool-glyph" data-glyph={call.kind} aria-hidden>
      {glyph}
    </span>
  );
}

// ── Input and error chips ───────────────────────────────────────────────────

function InputChipRow({ header, resolved }: { header: string; resolved: boolean }) {
  return (
    <div className={`input-chip ${resolved ? "" : "input-chip-open"}`}>
      <span className="input-chip-glyph" aria-hidden>
        ?
      </span>
      <span className="input-chip-text">{resolved ? header : "Awaiting your answer…"}</span>
    </div>
  );
}

function ErrorChipRow({ message }: { message: string }) {
  return (
    <div className="error-chip" role="alert">
      <span className="error-chip-glyph" aria-hidden>
        !
      </span>
      <span className="error-chip-text">{message}</span>
    </div>
  );
}

// ── Shell chrome published from the transcript surface ──────────────────────

/**
 * The "↓ Scroll to bottom" pill — the desktop's `jump_pill` (`shell.rs:6192`):
 * a 30px rounded-full labeled chip, frosted (blur 16 under the floating-card
 * tint), hairline border, `↓` + label at 13px, paddings 11/13 around a 6px
 * gap. Reusable so ticket 07/19's subagent pane can host its second instance.
 *
 * The entrance (`dialog_in`: 180ms, opacity 0→1, top 2px→0) and the hover
 * wash live in the stylesheet.
 */
export function JumpPill({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" className="jump-pill" onClick={onClick}>
      <span className="jump-pill-inner">
        <span className="jump-pill-glyph" aria-hidden>
          ↓
        </span>
        <span className="jump-pill-label">Scroll to bottom</span>
      </span>
    </button>
  );
}

/**
 * The reserved status strip — the desktop's `render_status_strip`
 * (`shell.rs:6373`): 24px tall, ALWAYS reserved so the composer below never
 * shifts, aligned with the composer column (max-width 768, centered,
 * 24px inner gutters, 11px type). The working loader and the awaiting-input
 * surface live elsewhere now; what is left is the error word and the sending
 * indicator.
 */
export function StatusStrip({ status, sending }: { status: ChatIndicator; sending: boolean }) {
  return (
    <div className="status-strip" role="status">
      {status === "errored" ? (
        <span className="status-strip-error">Run failed</span>
      ) : sending ? (
        <>
          {/*
            The desktop's `gradient_spinner("sending-indicator", speed 2.5)`;
            `MatrixSpinner` is that spinner's standing port, sized to the
            24px strip — its exact geometry is ticket 20's.
          */}
          <MatrixSpinner size={16} className="status-strip-spinner" />
          <span className="status-strip-sending">Sending…</span>
        </>
      ) : null}
    </div>
  );
}
