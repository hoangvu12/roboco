import {
  memo,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type Dispatch,
  type ReactNode,
  type SetStateAction,
} from "react";
import type { EngineClient, EngineStatus } from "@roboco/engine-client";
import { methods } from "@roboco/engine-client";
import { Icon } from "@roboco/icons";
import { motion } from "@roboco/theme";
import { cubicBezierEval, useBottomClearance } from "../state/layout";
import type { ContextUsage, FetchToolBlobReply, SessionMessageEntry } from "@roboco/proto";
import {
  echoStore,
  pendingSendStatus,
  savedViewportCache,
  TranscriptStore,
  type PendingSend,
} from "../state/transcript-store";
import { useNow } from "../state/hooks";
import { withAttachments } from "../lib/attachments";
import { parseMarkdown, PENDING_LINK_URL, blockFlatText, type Block, type InlineRun } from "../lib/markdown";
import {
  COPIED_CLEAR_MS,
  SELECTION_SCROLL_TICK_MS,
  TITLEBAR_HEIGHT,
  TRANSCRIPT_FADE_BAND,
  USER_COLLAPSED_LINES,
  USER_HOLD_DELAY_MS,
  USER_LINE_HEIGHT,
  captureSavedViewport,
  flavourSeed,
  flavourWord,
  formatElapsed,
  formatTimestamp,
  isSubagentSpawn,
  ownTurnReleasedForRestore,
  parseForRow,
  resolveViewportAnchor,
  rowsForEntry,
  selectionScrollStep,
  sendingBridge,
  SPACE_LG,
  subagentModel,
  toolChipContent,
  toolGroupTitle,
  topGapFor,
  userMessageNeedsCollapse,
  userResizeCurve,
  userResizeDurationMs,
  visibleRowWindow,
  type SavedViewport,
  type SentMentionSpan,
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
import { WorkingTrailer, type WorkingTrailerState } from "./working-trailer";

/**
 * The chat transcript — virtualization at block granularity over the row model
 * of `../lib/transcript.ts` (the desktop's `rows_for_entry` port), with the
 * stick-to-bottom spring and the own-turn runway driving the scroller. Rows
 * are accounted by measured heights (estimates until rendered, 320px
 * overdraw), so a thousand-message chat keeps a bounded DOM and a stable
 * viewport.
 */

/** Line cap for a FETCHED full output (defensive; desktop FULL_OUTPUT_MAX_LINES). */
const FULL_OUTPUT_MAX_LINES = 400;

/** Row 0's gap (transcript.rs:5359): the titlebar chrome + breathing room. */
const FIRST_ROW_GAP = TITLEBAR_HEIGHT + SPACE_LG + 10;
/** The subagent override's row-0 gap — its surface already pads the titlebar. */
const FIRST_ROW_GAP_SUBAGENT = SPACE_LG;
/** Collapsed bubble geometry (transcript.rs:1771): 5 lines + the ellipsis line. */
const USER_COLLAPSED_TEXT_HEIGHT = USER_COLLAPSED_LINES * USER_LINE_HEIGHT;
const USER_COLLAPSED_HEIGHT = USER_COLLAPSED_TEXT_HEIGHT + USER_LINE_HEIGHT;
/** Trailer estimate for unmounted last rows: `pt(16)` + one 12px line. */
const TRAILER_ESTIMATE_HEIGHT = 28;
/** Desktop widths — the underlay layout and the clearance pad apply here. */
const DESKTOP_QUERY = "(min-width: 769px)";

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
  onRetryDelivery,
  onJumpChange,
  alignTop = false,
  indicator = null,
  turnStartedAt = null,
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
  /**
   * The working-trailer's failed-send branch: `retry_send` — restart the
   * grace clocks and re-deliver. Skipped entirely when the engine is not
   * connected.
   */
  onRetryDelivery?: () => void;
  /**
   * The jump-to-bottom button's live state (`transcript.jump_button_shown()`).
   * The pill itself lives over the composer — outside this component — so the
   * chat page owns where it renders; this callback is how it learns.
   */
  onJumpChange?: (state: JumpButtonState) => void;
  /**
   * The subagent override instance (`Transcript::for_doc`): aligns to the TOP,
   * never holds an own-turn runway, owns a top-only fade gated on overflow,
   * and reads its liveness off the doc itself. Ticket 19 mounts this in the
   * right pane; the dialog is the current stand-in.
   */
  alignTop?: boolean;
  /** The chat's live indicator (`indicator_for`) — gates the working trailer. */
  indicator?: ChatIndicator | null;
  /** The session row's `started_at` in epoch ms (the trailer's timer base). */
  turnStartedAt?: number | null;
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
      onRetryDelivery={onRetryDelivery}
      onJumpChange={onJumpChange}
      alignTop={alignTop}
      indicator={indicator}
      turnStartedAt={turnStartedAt}
    />
  );
}

function TranscriptSurface({
  store,
  client,
  deviceId,
  onContextUsage,
  onRetryDelivery,
  onJumpChange,
  alignTop,
  indicator,
  turnStartedAt,
}: {
  store: TranscriptStore;
  client: EngineClient;
  deviceId: string | null;
  onContextUsage?: (usage: ContextUsage | null) => void;
  onRetryDelivery?: () => void;
  onJumpChange?: (state: JumpButtonState) => void;
  alignTop: boolean;
  indicator: ChatIndicator | null;
  turnStartedAt: number | null;
}) {
  const subscribe = useCallback((listener: () => void) => store.subscribe(listener), [store]);
  const getSnapshot = useCallback(() => store.getSnapshot(), [store]);
  const snapshot = useSyncExternalStore(subscribe, getSnapshot);

  const usage = snapshot.contextUsage;
  useEffect(() => {
    onContextUsage?.(usage);
  }, [usage, onContextUsage]);

  // `parse_for_row` state: incremental while live, handoff on settle, cache
  // for settled trees. Bounded like the entry cache below.
  const parseStateRef = useRef(new Map<string, { text: string; live: boolean; tree: ReturnType<typeof parseMarkdown> }>());
  const entryRowsCacheRef = useRef(new Map<string, { entry: SessionMessageEntry; rows: TranscriptRow[] }>());

  // Rows are rebuilt per entry only when the entry's identity changes; the
  // parse state keeps settled markdown trees shared across stream ticks.
  const rows = useMemo(() => {
    const cache = entryRowsCacheRef.current;
    const parseState = parseStateRef.current;
    // Bound the caches: a long-lived session re-parses after a prune.
    if (cache.size > 4096) {
      cache.clear();
    }
    if (parseState.size > 4096) {
      parseState.clear();
    }
    const out: TranscriptRow[] = [];
    for (const entry of snapshot.entries) {
      let hit = cache.get(entry.id);
      if (hit === undefined || hit.entry !== entry) {
        hit = {
          entry,
          rows: rowsForEntry(entry, {
            parse: (key, text, live) => parseForRow(parseState, key, text, live).tree,
          }),
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

  // One clock: the grace window needs ~10s resolution, a live trailer 1s.
  const lastEntry = snapshot.entries[snapshot.entries.length - 1];
  const subagentLive =
    alignTop &&
    snapshot.loaded &&
    lastEntry !== undefined &&
    (lastEntry.status === "streaming" || lastEntry.role === "user");
  const trailerTicks = indicator === "working" || pendingSends.length > 0 || subagentLive;
  const now = useNow(trailerTicks ? 1000 : ECHO_TICK_MS);

  const allRows = useMemo(() => {
    if (pendingSends.length === 0) {
      return rows;
    }
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
        parse: (key, text, live) => parseMarkdown(text, live),
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

  // ── The working trailer's state (render_working_trailer, §2.11) ─────────
  const trailerState = useMemo<WorkingTrailerState>(() => {
    if (alignTop) {
      // A subagent doc has no Session row — liveness rides the doc itself:
      // the last entry streams, or a trailing user entry awaits its reply.
      // Frozen snapshots never spin.
      if (!subagentLive) {
        return { kind: "none" };
      }
      const elapsed = Math.max(0, Math.floor((now - lastEntry!.createdAt) / 1000));
      return {
        kind: "working",
        word: flavourWord(flavourSeed(docId), elapsed),
        elapsed: formatElapsed(elapsed),
      };
    }
    // Failed-send state first: past the grace window the trailer IS the retry
    // affordance, whatever the indicator fell back to.
    if (pendingSends.some((send) => pendingSendStatus(send, now) === "undelivered")) {
      return { kind: "undelivered", onRetry: onRetryDelivery ?? (() => {}) };
    }
    if (indicator !== "working") {
      return { kind: "none" };
    }
    // The send→turn bridge: "Sending…" with no timer while the session row
    // still carries the previous turn's start. (`chat_delivery_degraded`
    // has no web stream yet — research 14 §5 — so the queued branch stays
    // unreachable; the component and its string ship ready for it.)
    const sendStarted =
      pendingSends.find((send) => pendingSendStatus(send, now) === "pending")?.startedAtMs ?? null;
    if (sendingBridge(sendStarted, turnStartedAt)) {
      return { kind: "sending" };
    }
    const elapsed =
      turnStartedAt !== null ? Math.max(0, Math.floor((now - turnStartedAt) / 1000)) : 0;
    return {
      kind: "working",
      word: flavourWord(flavourSeed(docId), elapsed),
      elapsed: formatElapsed(elapsed),
    };
  }, [alignTop, subagentLive, lastEntry, now, docId, pendingSends, indicator, turnStartedAt, onRetryDelivery]);

  return (
    <TranscriptScroller
      rows={allRows}
      streaming={snapshot.streaming}
      loaded={snapshot.loaded}
      replay={snapshot.replay}
      error={snapshot.error}
      client={client}
      deviceId={deviceId}
      docId={docId}
      alignTop={alignTop}
      trailer={trailerState}
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
// Scroller: virtualization + stick-to-bottom + the own-turn runway
// ---------------------------------------------------------------------------

interface ScrollerProps {
  readonly rows: readonly TranscriptRow[];
  readonly streaming: boolean;
  readonly loaded: boolean;
  readonly replay: "pending" | "empty" | "populated";
  readonly error: string | null;
  readonly client: EngineClient;
  readonly deviceId: string | null;
  readonly docId: string;
  readonly alignTop: boolean;
  readonly trailer: WorkingTrailerState;
  readonly onJumpChange?: (state: JumpButtonState) => void;
}

/** The user-bubble fold, lifted so virtualizer remounts never lose it. */
interface UserFoldState {
  readonly open: boolean;
  readonly epoch: number;
  readonly toggledAt: number;
  readonly durationMs: number;
  /** Height at toggle time: `full_h` (was open) or `collapsed_h`. */
  readonly from: number;
  readonly expansion: number;
}

/** The fold compensation tween (`UserCollapseScroll`, transcript.rs:4430). */
interface CollapseScroll {
  readonly startedAt: number;
  readonly durationMs: number;
  readonly heightDelta: number;
  readonly rowIx: number;
  readonly initialTop: number;
  readonly targetTop: number;
}

function TranscriptScroller({
  rows,
  streaming,
  loaded,
  replay,
  error,
  client,
  deviceId,
  docId,
  alignTop,
  trailer,
  onJumpChange,
}: ScrollerProps) {
  const scrollerRef = useRef<HTMLDivElement | null>(null);
  // The scroller MOUNTS AND UNMOUNTS with the empty state (the transcript
  // renders nothing when empty, exactly like the desktop). The attach effect
  // below keys off this presence state so a scroller that mounts after the
  // empty period still gets the stick controller attached.
  const scrollerPresentRef = useRef(false);
  const [, bumpScrollerPresence] = useState(0);
  const scrollerRefCallback = useCallback((el: HTMLDivElement | null) => {
    scrollerRef.current = el;
    const present = el !== null;
    if (present !== scrollerPresentRef.current) {
      scrollerPresentRef.current = present;
      bumpScrollerPresence((tick) => tick + 1);
    }
  }, []);
  const heightsRef = useRef(new Map<string, number>());
  const anchorRef = useRef<{ id: string; offset: number } | null>(null);
  const rowsRef = useRef(rows);
  const positionsRef = useRef<readonly number[]>([]);
  const rowHeightsRef = useRef<readonly number[]>([]);
  const measuredTextRef = useRef(new Map<string, number>());
  const holdTimersRef = useRef(new Map<string, number>());
  const collapseScrollRef = useRef<CollapseScroll | null>(null);
  const [, bumpMeasure] = useState(0);
  const [view, setView] = useState({ top: 0, height: 0 });
  const [showJump, setShowJump] = useState(false);
  const [hoveredEntry, setHoveredEntry] = useState<{ rowId: string; entryId: string } | null>(null);
  const [subagentDoc, setSubagentDoc] = useState<string | null>(null);
  const [userFolds, setUserFolds] = useState<ReadonlyMap<string, UserFoldState>>(new Map());
  const [topFade, setTopFade] = useState(false);
  const [, bumpRunway] = useState(0);
  const [collapseTick, bumpCollapse] = useState(0);
  const reduced =
    typeof globalThis.matchMedia === "function"
      ? globalThis.matchMedia("(prefers-reduced-motion: reduce)")
      : null;
  const isDesktop = useMediaQuery(DESKTOP_QUERY);

  // The engine connection drives the offline strip (§2.1); a null status is
  // the pre-first-connect state, which reads as "Reconnecting…".
  const subscribeEngineStatus = useCallback((listener: () => void) => client.onStatus(listener), [client]);
  const getEngineStatus = useCallback(() => client.status, [client]);
  const engineStatus = useSyncExternalStore(subscribeEngineStatus, getEngineStatus, () => null);

  // The shell's live bottom-chrome measurement (`set_bottom_clearance`).
  // The subagent dialog has no bottom chrome to clear, so its clearance is 0;
  // at phone widths the transcript is a sibling of the stack, not an
  // underlay, so only the ordinary breathing room applies.
  const shellClearance = useBottomClearance();
  const lastRowPad = alignTop || !isDesktop ? 16 : shellClearance + TRANSCRIPT_FADE_BAND + 8;

  const stickRef = useRef<StickController | null>(null);
  if (stickRef.current === null) {
    stickRef.current = new StickController({
      onJumpVisibility: setShowJump,
      // The runway's floor is render-derived — install/retire must re-render
      // the virtualizer's height model.
      onOwnTurnChange: () => bumpRunway((tick) => tick + 1),
      // A user scroll stands down the fold compensation and any pending
      // long-press (`handle_scroll`'s synchronous cancels).
      onUserInput: () => {
        collapseScrollRef.current = null;
        for (const timer of holdTimersRef.current.values()) {
          window.clearTimeout(timer);
        }
        holdTimersRef.current.clear();
      },
      reducedMotion: reduced,
    });
  }
  const stick = stickRef.current;
  const stickOwnTurn = stick.ownTurn;

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

  // ── Height model ─────────────────────────────────────────────────────────
  // Row 0's gap carries the titlebar chrome (the primary instance spans
  // under the overlay titlebar); the subagent override keeps only the
  // ordinary turn gap.
  const gapFor = useCallback(
    (ix: number): number =>
      ix === 0
        ? alignTop
          ? FIRST_ROW_GAP_SUBAGENT
          : FIRST_ROW_GAP
        : topGapFor(rows[ix - 1] ?? null, rows[ix]!),
    [rows, alignTop],
  );

  // The own-turn reservation: while a runway is live the LAST row has a
  // minimum height, so the prompt can sit at the viewport top with the
  // scroll ending at the app's bottom (`set_tail_reservation`; the floor is
  // met by the bottom spacer — plain scrollable space, never painted
  // chrome). The arithmetic reads the PREVIOUS layout's positions, exactly
  // like the desktop's pre-layout `update_runway_minimum`.
  const anchorIx =
    stickOwnTurn !== null
      ? rows.findIndex((row) => row.turnStart && row.entryId === stickOwnTurn.messageId)
      : -1;
  const lastIx = rows.length - 1;
  const viewportHeight = view.height;
  const priorPositions = positionsRef.current;
  const reservationFloor =
    anchorIx >= 0 && viewportHeight > 0
      ? Math.max(
          0,
          (priorPositions[anchorIx] ?? 0) +
            viewportHeight -
            StickController.ownSendInset(anchorIx) -
            (priorPositions[lastIx] ?? 0),
        )
      : 0;
  const trailerLive = trailer.kind !== "none";

  // Row positions: prefix sums over measured heights (estimates until
  // rendered). The last row's height carries its clearance pad — and the
  // reservation floor while a runway is live. The floor rides the BOTTOM
  // SPACER (plain scrollable space, never painted chrome): the DOM row keeps
  // its natural height, so the spacer math below tracks natural heights
  // separately from the floored arithmetic.
  const positions: number[] = new Array(rows.length);
  const rowHeights: number[] = new Array(rows.length);
  const naturalHeights: number[] = new Array(rows.length);
  let total = 0;
  for (let ix = 0; ix < rows.length; ix++) {
    positions[ix] = total;
    const gap = gapFor(ix);
    const isLast = ix === lastIx;
    const natural =
      heights.get(rows[ix]!.id) ??
      estimateRowHeight(rows[ix]!) + gap + (isLast ? lastRowPad : 0) + (isLast && trailerLive ? TRAILER_ESTIMATE_HEIGHT : 0);
    naturalHeights[ix] = natural;
    rowHeights[ix] = isLast ? Math.max(natural, reservationFloor) : natural;
    total += rowHeights[ix]!;
  }
  rowsRef.current = rows;
  positionsRef.current = positions;
  rowHeightsRef.current = rowHeights;
  // A transient replay (the store's desync resubscribe) empties the rows for
  // a frame before the reset lands. Hold the last measured layout open so
  // the content never shrinks under the viewport — the browser would clamp
  // scrollTop to 0 and the released view would land at the top (the desktop
  // keeps its offset logical across replays; the web needs the spacer).
  const lastTotalRef = useRef(0);
  if (rows.length > 0) {
    lastTotalRef.current = total;
  }
  const layoutTotal = rows.length === 0 ? lastTotalRef.current : total;

  // The reservation no longer binds — the reply's natural content has filled
  // it (`tail_reservation_filled`).
  const lastNatural =
    lastIx >= 0
      ? (heights.get(rows[lastIx]!.id) ??
        estimateRowHeight(rows[lastIx]!) + lastRowPad + (trailerLive ? TRAILER_ESTIMATE_HEIGHT : 0))
      : 0;
  const reservationFilled =
    anchorIx >= 0 &&
    viewportHeight > 0 &&
    (positions[lastIx] ?? 0) + lastNatural >=
      (positions[anchorIx] ?? 0) + viewportHeight - StickController.ownSendInset(anchorIx) + 0.5;

  // The controller's frame-time geometry: anchor position + fill state.
  const geometryRef = useRef({ anchor: null as { top: number; ix: number } | null, filled: false });
  geometryRef.current = {
    anchor: anchorIx >= 0 ? { top: positions[anchorIx]!, ix: anchorIx } : null,
    filled: reservationFilled,
  };
  const anchorExpanded = anchorIx >= 0 && (userFolds.get(rows[anchorIx]!.id)?.open ?? false) === true;
  const anchorExpandedRef = useRef(anchorExpanded);
  anchorExpandedRef.current = anchorExpanded;

  // Shared ResizeObserver: row heights land here (border-box, so the gap
  // padding and the last row's clearance pad are included) — one bump per
  // batch.
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

  // Attach/detach the stick controller, the viewport listeners, and the
  // selection drag's edge auto-scroll. Re-arms when the scroller (re)mounts —
  // an empty chat renders no scroller at all, and the first send brings one.
  useLayoutEffect(() => {
    const el = scrollerRef.current;
    if (el === null) {
      return;
    }
    stick.setGeometry(() => geometryRef.current);
    stick.attach(el);
    setView({ top: el.scrollTop, height: el.clientHeight });
    let raf = 0;
    const onScroll = (): void => {
      anchorRef.current = captureAnchor(el.scrollTop, rowsRef.current, positionsRef.current, heightsRef.current);
      if (alignTop) {
        // The override instance's top fade is gated on real overflow
        // (transcript.rs:7648-7651): max_offset − distance_from_bottom > 1.
        const scrolledUnder = el.scrollTop > 1;
        setTopFade((current) => (current === scrolledUnder ? current : scrolledUnder));
      }
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

    // ── Selection drag edge auto-scroll (§3.7) ────────────────────────────
    // Native selection is acceptable on the web, but a drag pinned near the
    // top/bottom edge still needs to auto-scroll — the t² ramp at a 24ms
    // cadence. Armed by a primary-button press on non-interactive content.
    let drag: { x: number; y: number } | null = null;
    const onPointerDown = (event: PointerEvent): void => {
      if (event.button !== 0) {
        return;
      }
      const target = event.target as Element | null;
      if (target !== null && target.closest("button, a, [role='button'], input, textarea")) {
        drag = null;
        return;
      }
      drag = { x: event.clientX, y: event.clientY };
    };
    const onPointerMove = (event: PointerEvent): void => {
      if (event.buttons === 0) {
        drag = null;
        return;
      }
      drag = { x: event.clientX, y: event.clientY };
    };
    const clearDrag = (): void => {
      drag = null;
    };
    el.addEventListener("pointerdown", onPointerDown);
    window.addEventListener("pointermove", onPointerMove, { passive: true });
    window.addEventListener("pointerup", clearDrag, { passive: true });
    const selectionTimer = window.setInterval(() => {
      if (drag === null) {
        return;
      }
      const rect = el.getBoundingClientRect();
      const step = selectionScrollStep({ top: rect.top, bottom: rect.bottom }, drag);
      if (step === 0) {
        return;
      }
      // Auto-scroll is navigation: it releases the own-turn hold and unpins
      // (`begin_scroll_navigation` per the desktop's step_selection_scroll).
      stick.beginScrollNavigation();
      stick.writePreserving(el.scrollTop + step);
    }, SELECTION_SCROLL_TICK_MS);

    const observer = observerRef.current;
    return () => {
      el.removeEventListener("scroll", onScroll);
      el.removeEventListener("pointerdown", onPointerDown);
      window.removeEventListener("pointermove", onPointerMove);
      window.removeEventListener("pointerup", clearDrag);
      window.clearInterval(selectionTimer);
      resize.disconnect();
      observer?.disconnect();
      stick.detach();
      if (raf !== 0) {
        cancelAnimationFrame(raf);
      }
    };
    // `scrollerPresentRef` flip bumps a state so this re-arms when the
    // scroller (re)mounts after the empty period.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stick, alignTop, scrollerPresentRef.current]);

  // Keep the controller's live-anchor input current.
  useEffect(() => {
    stick.setStreaming(streaming);
  }, [stick, streaming]);

  // ── Own-send: every new pending echo installs a runway (on_own_send) ────
  const ownSendsRef = useRef(new Set<string>());
  useLayoutEffect(() => {
    for (const row of rows) {
      if (row.rowKind.kind === "user" && row.rowKind.pending && !ownSendsRef.current.has(row.id)) {
        ownSendsRef.current.add(row.id);
        if (!alignTop) {
          // An echo row's id IS the client-minted message id.
          stick.onOwnSend(docId, row.id);
        }
      }
    }
  }, [rows, stick, docId, alignTop]);

  // ── Viewport memory: save on leave, restore after a populated replay ────
  const pendingViewportRef = useRef<SavedViewport | null>(null);
  const viewportInitRef = useRef(false);
  if (!viewportInitRef.current) {
    viewportInitRef.current = true;
    pendingViewportRef.current = alignTop ? null : (savedViewportCache.get(docId) ?? null);
  }
  const restoredRef = useRef(false);
  useLayoutEffect(() => {
    const el = scrollerRef.current;
    if (el === null || restoredRef.current || !loaded) {
      return;
    }
    // The restore targets the anchor row's position, which is only real once
    // the mounted rows have MEASURED (the desktop's `viewport_finalize`
    // token waits for layout the same way) — specifically the anchor row's.
    // Poll a few frames before resolving the saved viewport.
    let raf = 0;
    let waited = 0;
    const anchorId =
      pendingViewportRef.current !== null && pendingViewportRef.current.kind === "anchored"
        ? pendingViewportRef.current.anchor.rowId
        : null;
    const apply = (): void => {
      raf = 0;
      if (scrollerRef.current === null) {
        return;
      }
      const measuredEnough = anchorId === null ? heightsRef.current.size > 0 : heightsRef.current.has(anchorId);
      if (!measuredEnough && waited < 12) {
        waited++;
        raf = requestAnimationFrame(apply);
        return;
      }
      restoredRef.current = true;
      const elNow = scrollerRef.current;
      applyRestoredViewport(elNow!);
    };
    raf = requestAnimationFrame(apply);
    return () => {
      if (raf !== 0) {
        cancelAnimationFrame(raf);
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stick, loaded, replay, rows.length, alignTop]);

  /** Resolve and apply the saved viewport (or open at the end). */
  const applyRestoredViewport = (el: HTMLElement): void => {
    if (alignTop) {
      // The override instance opens at the TOP.
      setView({ top: el.scrollTop, height: el.clientHeight });
      return;
    }
    const saved = pendingViewportRef.current;
    if (saved === null) {
      // First fill lands at the bottom instantly (desktop opens at the end).
      stick.snapToEnd();
      setView({ top: el.scrollTop, height: el.clientHeight });
      return;
    }
    if (replay === "empty" && rowsRef.current.length === 0) {
      // An empty replay is authoritative — the chat really has no messages;
      // the saved snapshot retires and the tail is followed.
      pendingViewportRef.current = null;
      stick.snapToEnd();
      setView({ top: el.scrollTop, height: el.clientHeight });
      return;
    }
    if (saved.kind === "anchored" && rowsRef.current.length > 0) {
      // Fallbacks are enabled only after a populated replay; a loaded frame
      // with rows IS one (the store decides `replay` on the same frame).
      const offset = resolveViewportAnchor(saved.anchor, rowsRef.current, replay === "populated");
      if (offset !== null) {
        const scrollTop = positionsRef.current[offset.itemIx]! + offset.offsetInItem;
        // The restored row anchor doubles as the escape anchor: the
        // per-commit preserve keeps it stationary while late measurements
        // land (the desktop's viewport-finalize token).
        anchorRef.current = { id: saved.anchor.rowId, offset: offset.offsetInItem };
        stick.restoreViewport(
          scrollTop,
          saved.ownTurn === null ? null : ownTurnReleasedForRestore(saved.ownTurn),
          saved.distanceFromBottom,
        );
        setView({ top: el.scrollTop, height: el.clientHeight });
        pendingViewportRef.current = null;
        return;
      }
    }
    pendingViewportRef.current = null;
    stick.snapToEnd();
    setView({ top: el.scrollTop, height: el.clientHeight });
  };

  // Save the outgoing chat's viewport. Empty rows never overwrite an older
  // snapshot (a partial replay must not); an unresolved pending restore
  // keeps the older entry.
  useEffect(() => {
    return () => {
      if (alignTop || pendingViewportRef.current !== null) {
        return;
      }
      const el = scrollerRef.current;
      if (el === null) {
        return;
      }
      const saved = captureSavedViewport(
        rowsRef.current,
        el.scrollTop,
        positionsRef.current,
        rowHeightsRef.current,
        stick.pinned,
        Math.max(0, el.scrollHeight - el.clientHeight - el.scrollTop),
        stick.ownTurn,
      );
      if (saved !== null) {
        savedViewportCache.save(docId, saved);
      }
    };
    // Capture at unmount: the refs are current and the next chat mounts
    // after this cleanup runs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [docId, alignTop]);

  // Publish the jump button's state up to the chat page, which renders the
  // pill over the composer (`render_jump_to_bottom` floats outside the
  // transcript's fade, anchored above the composer stack). The cleanup hides
  // it again when the surface unmounts (chat switch).
  useEffect(() => {
    const state: JumpButtonState = {
      shown: showJump,
      jump: () => stickRef.current?.jumpToBottom({ anchorExpanded: anchorExpandedRef.current }),
    };
    onJumpChange?.(state);
    return () => onJumpChange?.({ ...state, shown: false });
  }, [showJump, onJumpChange]);

  // After every commit: a live runway re-arms its stepper (the desktop's
  // `own_turn_kick` on every sync — the fill-check and the post-layout
  // re-assert run per commit, never only from the rAF loop; transcript.rs
  // 7541-7549 schedules the step on every frame while an anchor is live, and
  // one commit per streamed delta is the web's equivalent cadence); pinned →
  // let the spring track growth; escaped → keep the captured anchor row
  // visually stationary across splices and measures. An animating fold owns
  // the viewport for the duration of its tween.
  useLayoutEffect(() => {
    const el = scrollerRef.current;
    if (el === null) {
      return;
    }
    // A live runway re-arms its stepper per commit (the desktop's
    // `own_turn_kick` on every sync — the fill-check and the post-layout
    // re-assert advance per streamed delta, transcript.rs:7541-7549); the
    // released reservation still needs the fill-check, so the kick fires for
    // any live anchor, held or not. A pending viewport restore owns the
    // first frames instead — the spring must not pre-empt it.
    if (pendingViewportRef.current === null && (stick.ownTurn !== null || stick.pinned)) {
      stick.kick();
    }
    if (stick.ownTurnHeld || collapseScrollRef.current !== null) {
      return;
    }
    // Escaped (or a released runway): keep the captured anchor row visually
    // stationary across splices and measures.
    const anchor = anchorRef.current;
    if (stick.pinned || anchor === null) {
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

  // ── User-fold compensation (step_user_collapse_scroll, §2.5) ────────────
  useEffect(() => {
    if (collapseScrollRef.current === null) {
      return;
    }
    let raf = 0;
    const step = (): void => {
      raf = 0;
      const scroll = collapseScrollRef.current;
      const el = scrollerRef.current;
      if (scroll === null || el === null) {
        return;
      }
      const raw = Math.min(Math.max((performance.now() - scroll.startedAt) / scroll.durationMs, 0), 1);
      const curve =
        (motion.curves[userResizeCurve(scroll.heightDelta)] as readonly [number, number, number, number]) ??
        motion.curves.easeOut!;
      const progress = cubicBezierEval(curve, raw);
      const desiredTop = scroll.initialTop + (scroll.targetTop - scroll.initialTop) * progress;
      const rowTop = (positionsRef.current[scroll.rowIx] ?? 0) - el.scrollTop;
      const correction = rowTop - desiredTop;
      if (Math.abs(correction) > 0.1) {
        stick.writePreserving(el.scrollTop + correction);
      }
      if (raw >= 1) {
        collapseScrollRef.current = null;
        return;
      }
      raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => {
      if (raf !== 0) {
        cancelAnimationFrame(raf);
      }
    };
    // Armed by the toggle's bump; the loop reads live refs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stick, collapseTick]);

  const openSubagent = useCallback((doc: string) => setSubagentDoc(doc), []);

  /** `toggle_user_fold` (transcript.rs:4380-4440). */
  const toggleUserFold = useCallback(
    (rowId: string) => {
      const el = scrollerRef.current;
      const ix = rowsRef.current.findIndex((row) => row.id === rowId);
      if (el === null || ix < 0) {
        return;
      }
      const measured = measuredTextRef.current.get(rowId) ?? 0;
      const fullH = Math.max(measured, USER_COLLAPSED_HEIGHT);
      const currentlyOpen = userFolds.get(rowId)?.open ?? false;
      const durationMs = userResizeDurationMs(fullH - USER_COLLAPSED_HEIGHT);
      // A fold toggle is scroll navigation first: the pending viewport is
      // discarded, the hold stands down, the pin drops.
      stick.beginScrollNavigation();
      setUserFolds((current) => {
        const prev = current.get(rowId);
        const next = new Map(current);
        next.set(rowId, {
          open: !currentlyOpen,
          epoch: (prev?.epoch ?? 0) + 1,
          toggledAt: performance.now(),
          durationMs,
          from: currentlyOpen ? fullH : USER_COLLAPSED_HEIGHT,
          expansion: Math.max(0, fullH - USER_COLLAPSED_HEIGHT),
        });
        return next;
      });
      // Viewport compensation: keep the toggled row on an interpolated
      // screen-space path so the rows below it do not jump.
      const initialTop = (positionsRef.current[ix] ?? 0) - el.scrollTop;
      const viewportTop = TRANSCRIPT_FADE_BAND + 28;
      const targetHeight = currentlyOpen ? USER_COLLAPSED_HEIGHT : fullH;
      const viewportBottom = el.clientHeight - targetHeight - 12;
      const targetTop =
        viewportBottom >= viewportTop
          ? Math.min(Math.max(initialTop, viewportTop), viewportBottom)
          : viewportTop;
      if (Math.abs(targetTop - initialTop) <= 0.5) {
        return;
      }
      if (reduced?.matches) {
        const rowTop = (positionsRef.current[ix] ?? 0) - el.scrollTop;
        stick.writePreserving(el.scrollTop + (rowTop - targetTop));
        return;
      }
      collapseScrollRef.current = {
        startedAt: performance.now(),
        durationMs,
        heightDelta: Math.max(0, fullH - USER_COLLAPSED_HEIGHT),
        rowIx: ix,
        initialTop,
        targetTop,
      };
      bumpCollapse((tick) => tick + 1);
    },
    [userFolds, stick, reduced],
  );

  /** The bubble's wrapped-text measurement (0.5px threshold, §2.7). */
  const onMeasureText = useCallback((rowId: string, height: number) => {
    const prev = measuredTextRef.current.get(rowId) ?? 0;
    if (Math.abs(prev - height) > 0.5) {
      measuredTextRef.current.set(rowId, height);
    }
  }, []);

  /** Long-press bookkeeping: the scroller owns the timers so a user scroll
   *  can cancel them all (`handle_scroll` cancels the pending hold). */
  const onHoldTimer = useCallback((rowId: string, timer: number): void => {
    const prev = holdTimersRef.current.get(rowId);
    if (prev !== undefined && prev !== timer) {
      window.clearTimeout(prev);
    }
    if (timer === 0) {
      holdTimersRef.current.delete(rowId);
    } else {
      holdTimersRef.current.set(rowId, timer);
    }
  }, []);

  // Empty transcript: nothing at all, as on the desktop. The shell's new-chat
  // hero (ticket 15) is what will occupy this space.
  if (loaded && rows.length === 0 && error === null) {
    return null;
  }

  // The visible window, with the desktop's 320px overdraw on both ends.
  const { first, last } = visibleRowWindow(positions, rowHeights, view.top, view.height, OVERDRAW_PX);
  const visible = rows.slice(first, last + 1);
  const topPad = rows.length === 0 ? layoutTotal : (positions[first] ?? total);
  // The spacer covers everything below the last MOUNTED row — the unmounted
  // rows' arithmetic heights plus the reservation floor, minus the mounted
  // last row's natural DOM height (the floor is spacer space, not row space).
  const bottomPad =
    rows.length === 0 ? 0 : (last >= 0 ? Math.max(0, total - (positions[last]! + (naturalHeights[last] ?? 0))) : 0);

  const offlineMessage = offlineStripMessage(alignTop, engineStatus);

  return (
    <div
      className={`transcript-wrap ${alignTop ? "transcript-subagent" : ""}`}
      data-offline={offlineMessage !== null ? "1" : "0"}
    >
      {offlineMessage !== null && (
        // The engine-offline strip (§2.1): a 24px bar ABOVE the list, not an
        // overlay — it replaces the invented floating error card.
        <div className="engine-offline-strip" role="status">
          {offlineMessage}
        </div>
      )}
      <div
        className="transcript"
        ref={scrollerRefCallback}
        data-topfade={alignTop ? (topFade ? "1" : "0") : undefined}
      >
        <div style={{ height: topPad }} aria-hidden />
        {visible.map((row, offset) => {
          const ix = first + offset;
          const isLast = ix === lastIx;
          return (
            <RowShell
              key={row.id}
              row={row}
              gap={gapFor(ix)}
              bottomPad={isLast ? lastRowPad : 0}
              register={registerRow}
              onHover={setHoveredEntry}
            >
              <RowContent
                row={row}
                streaming={streaming}
                hovered={hoveredEntry?.entryId === row.entryId}
                onOpenSubagent={openSubagent}
                client={client}
                deviceId={deviceId}
                fold={userFolds.get(row.id) ?? null}
                onToggleFold={toggleUserFold}
                onMeasureText={onMeasureText}
                onHoldTimer={onHoldTimer}
                reduced={reduced?.matches === true}
              />
              {isLast && <WorkingTrailer state={trailer} />}
            </RowShell>
          );
        })}
        <div style={{ height: bottomPad }} aria-hidden />
      </div>
      {/*
        The edge fade lives on the scroller's own mask (`.transcript` in
        app.css): the desktop's per-glyph EdgeFade is a mask, not a painted
        overlay, with a 24px quadratic band under the titlebar and a bottom
        band sized to the chrome stack via `--rb-bottom-stack` — correct now
        that the transcript spans the column and scrolls under the chrome
        (the shell.rs:6025-6072 underlay port).
      */}
      {subagentDoc !== null && (
        <SubagentDialog client={client} docId={subagentDoc} deviceId={deviceId} onClose={() => setSubagentDoc(null)} />
      )}
    </div>
  );
}

/** The offline strip's message, from the engine connection state (§2.1). */
function offlineStripMessage(alignTop: boolean, status: EngineStatus | null): string | null {
  if (alignTop) {
    // An override instance has no chat row and no engine binding of its own.
    return null;
  }
  if (status === null || status.state === "connected") {
    return null;
  }
  return status.state === "parked" || status.state === "closed"
    ? "Engine off. Cached history is read-only."
    : "Reconnecting… Cached history is read-only.";
}

/** Live matchMedia as a React value (breakpoints only — it re-renders on flip). */
function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (listener: () => void) => {
      const mql = window.matchMedia(query);
      mql.addEventListener("change", listener);
      return () => mql.removeEventListener("change", listener);
    },
    [query],
  );
  const getSnapshot = useCallback(() => window.matchMedia(query).matches, [query]);
  return useSyncExternalStore(subscribe, getSnapshot, () => false);
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
      return 42;
  }
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

function RowShell({
  row,
  gap,
  bottomPad,
  register,
  onHover,
  children,
}: {
  row: TranscriptRow;
  gap: number;
  bottomPad: number;
  register: (id: string, el: HTMLDivElement | null) => void;
  onHover: Dispatch<SetStateAction<{ rowId: string; entryId: string } | null>>;
  children: ReactNode;
}) {
  const ref = useCallback((el: HTMLDivElement | null) => register(row.id, el), [register, row.id]);
  return (
    // Wide gutters (roboco `px-4 @3xl:px-12`) around the 736px column; the
    // last row's bottom pad clears the chrome the list scrolls under.
    <div
      ref={ref}
      data-rid={row.id}
      className="trow"
      style={{ paddingTop: gap, paddingBottom: bottomPad > 0 ? bottomPad : undefined }}
      onMouseEnter={() => onHover({ rowId: row.id, entryId: row.entryId })}
      onMouseLeave={() =>
        // Only the row that OWNS the current reveal may clear it — a stale
        // leave from an earlier row must not blank the strip the newly
        // entered row just lit (transcript.rs:5652-5662).
        onHover((current) => (current !== null && current.rowId === row.id ? null : current))
      }
    >
      <div className="trow-col">{children}</div>
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
  fold,
  onToggleFold,
  onMeasureText,
  onHoldTimer,
  reduced,
}: {
  row: TranscriptRow;
  streaming: boolean;
  hovered: boolean;
  onOpenSubagent: (doc: string) => void;
  client: EngineClient;
  deviceId: string | null;
  fold: UserFoldState | null;
  onToggleFold: (rowId: string) => void;
  onMeasureText: (rowId: string, height: number) => void;
  onHoldTimer: (rowId: string, timer: number) => void;
  reduced: boolean;
}) {
  const kind = row.rowKind;
  return (
    <>
      {kind.kind === "user" && (
        <UserRow
          rowId={row.id}
          text={kind.text}
          mentions={kind.mentions}
          pending={kind.pending}
          undelivered={kind.undelivered === true}
          attachments={kind.attachments}
          badges={kind.badges}
          client={client}
          deviceId={deviceId}
          fold={fold}
          onToggle={() => onToggleFold(row.id)}
          onMeasure={(height) => onMeasureText(row.id, height)}
          onHoldTimer={onHoldTimer}
          reduced={reduced}
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
      {row.timestamp !== null && <RowMeta row={row} visible={hovered} isUserRow={kind.kind === "user"} />}
    </>
  );
}

/** The hover strip under a settled entry's last row: timestamp + copy. */
function RowMeta({ row, visible, isUserRow }: { row: TranscriptRow; visible: boolean; isUserRow: boolean }) {
  const [copied, setCopied] = useState(false);
  const copy = (): void => {
    const text = row.copyText;
    const clipboard = globalThis.navigator?.clipboard;
    if (text === null || clipboard === undefined) {
      return;
    }
    void clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), COPIED_CLEAR_MS);
    });
  };
  return (
    // A RESERVED lane (always occupies its 32px) so the virtualizer never
    // shifts; only the contents' visibility flips.
    <div className={`row-meta ${isUserRow ? "row-meta-user" : ""} ${visible ? "row-meta-on" : ""}`}>
      <div className="row-meta-inner">
        <span className="row-meta-time">{formatTimestamp(row.timestamp ?? 0)}</span>
        {row.copyText !== null && (
          <button
            type="button"
            className="row-meta-copy"
            onClick={(event) => {
              event.stopPropagation();
              copy();
            }}
            aria-label="Copy message"
          >
            <Icon name={copied ? "check" : "copy"} size={14} />
          </button>
        )}
      </div>
    </div>
  );
}

// ── User bubble (§2.4-§2.7) ─────────────────────────────────────────────────

function UserRow({
  rowId,
  text,
  mentions,
  pending,
  undelivered = false,
  attachments,
  badges,
  client,
  deviceId,
  fold,
  onToggle,
  onMeasure,
  onHoldTimer,
  reduced,
}: {
  rowId: string;
  text: string;
  mentions: readonly SentMentionSpan[];
  pending: boolean;
  undelivered?: boolean;
  attachments: readonly import("../lib/attachments").UserImageAttachment[];
  badges: readonly unknown[];
  client: EngineClient;
  deviceId: string | null;
  fold: UserFoldState | null;
  onToggle: () => void;
  onMeasure: (height: number) => void;
  onHoldTimer: (rowId: string, timer: number) => void;
  reduced: boolean;
}) {
  const textRef = useRef<HTMLDivElement | null>(null);
  const holdTimerRef = useRef(0);
  const [measured, setMeasured] = useState(0);
  const measuredRef = useRef(0);

  // The bubble's wrapped height, measured from the DOM (the desktop writes
  // it from the paint canvas in `user_bubble_text`); deltas ≤ 0.5px are
  // ignored so idle layout never feeds back.
  useEffect(() => {
    const el = textRef.current;
    if (el === null || typeof ResizeObserver === "undefined") {
      return;
    }
    const observer = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (entry === undefined) {
        return;
      }
      const height = entry.borderBoxSize?.[0]?.blockSize ?? el.getBoundingClientRect().height;
      if (Math.abs(measuredRef.current - height) > 0.5) {
        measuredRef.current = height;
        setMeasured(height);
        onMeasure(height);
      }
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, [onMeasure]);

  const fullH = Math.max(measured, USER_COLLAPSED_HEIGHT);
  // `collapsible` (:4705): the wrapped-line count supersedes the first-frame
  // proxy once measurement exists.
  const collapsible =
    text.length > 0 &&
    (text.split("\n").length > USER_COLLAPSED_LINES ||
      (measured > 0 && measured > USER_COLLAPSED_TEXT_HEIGHT + 0.5) ||
      (measured === 0 && userMessageNeedsCollapse(text)));
  const expanded = fold?.open ?? false;
  const durationMs = fold?.durationMs ?? userResizeDurationMs(Math.max(0, fullH - USER_COLLAPSED_HEIGHT));
  // The animating window (§2.5): only apply the height transition within
  // `duration + 200ms` of the toggle, keyed by epoch — past it the fold
  // renders statically, so an armed-forever tween never replays on a
  // scroll-back-into-view remount.
  const [animating, setAnimating] = useState(false);
  useEffect(() => {
    if (fold === null || fold.epoch === 0 || reduced) {
      setAnimating(false);
      return;
    }
    setAnimating(true);
    const timer = window.setTimeout(() => setAnimating(false), fold.durationMs + 200);
    return () => window.clearTimeout(timer);
    // A new epoch re-arms; the window's end is the timer's job.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fold?.epoch, reduced]);

  // ── Long-press toggle (USER_HOLD_DELAY, :4451-4484) ──────────────────────
  // Releasing before the threshold preserves a click/selection; ANY move
  // cancels so a drag-select never unexpectedly toggles the fold.
  const cancelHold = useCallback((): void => {
    if (holdTimerRef.current !== 0) {
      onHoldTimer(rowId, 0);
      window.clearTimeout(holdTimerRef.current);
      holdTimerRef.current = 0;
    }
  }, [onHoldTimer, rowId]);
  useEffect(() => cancelHold, [cancelHold]);
  const onHoldPointerDown = (event: React.PointerEvent): void => {
    if (event.button !== 0 || !collapsible) {
      return;
    }
    cancelHold();
    holdTimerRef.current = window.setTimeout(() => {
      holdTimerRef.current = 0;
      onToggle();
    }, USER_HOLD_DELAY_MS);
    onHoldTimer(rowId, holdTimerRef.current);
  };

  // Image-only sends show no bubble (desktop parity); the thumbnail strip
  // above is the whole row.
  if (text.trim().length === 0 && attachments.length === 0) {
    return null;
  }
  // The badges strip slot (ticket 20 renders its pills; the container and its
  // ordering are this ticket's).
  const badgesEl = badges.length > 0 ? <div className="user-badges" /> : null;
  return (
    <div className="row-user">
      <div className={`user-content ${pending && !undelivered ? "user-bubble-pending" : ""}`}>
        <UserAttachments client={client} deviceId={deviceId} attachments={attachments} />
        {badgesEl}
        {text.trim().length > 0 && (
          <div className="user-bubble-row">
            <div className={`user-bubble ${pending && !undelivered ? "user-bubble-pending" : ""}`}>
              <div
                className="user-body"
                onPointerDown={onHoldPointerDown}
                onPointerMove={cancelHold}
                onPointerUp={cancelHold}
                onPointerCancel={cancelHold}
                onPointerLeave={cancelHold}
              >
                {collapsible ? (
                  <UserFoldedBody
                    fold={fold}
                    animating={animating}
                    expanded={expanded}
                    fullH={fullH}
                    durationMs={durationMs}
                    reduced={reduced}
                    textRef={textRef}
                  >
                    <UserText text={text} mentions={mentions} />
                  </UserFoldedBody>
                ) : (
                  <div className="user-text" ref={textRef}>
                    <UserText text={text} mentions={mentions} />
                  </div>
                )}
              </div>
              {collapsible && (
                <button
                  type="button"
                  className="user-expand"
                  aria-label={expanded ? "Collapse message" : "Expand message"}
                  aria-expanded={expanded}
                  onClick={onToggle}
                >
                  <span className="user-expand-label">{expanded ? "Show less" : "Show more"}</span>
                  <Icon name={expanded ? "altArrowUp" : "altArrowDown"} size={12} className="user-expand-icon" />
                </button>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * The collapsible body: a clip wrapper whose height is `collapsed_text_h`
 * while settled-collapsed, the full text while expanded, and the tweened
 * `from → to` (minus the ellipsis line while collapsed) inside the animating
 * window — the ellipsis row rides outside the clip, inside the tween's
 * endpoints, so removing it never jumps the layout (§2.5).
 */
function UserFoldedBody({
  fold,
  animating,
  expanded,
  fullH,
  durationMs,
  reduced,
  textRef,
  children,
}: {
  fold: UserFoldState | null;
  animating: boolean;
  expanded: boolean;
  fullH: number;
  durationMs: number;
  reduced: boolean;
  textRef: React.RefObject<HTMLDivElement | null>;
  children: ReactNode;
}) {
  const [phase, setPhase] = useState<"from" | "to" | null>(null);
  useEffect(() => {
    if (!animating || fold === null || reduced) {
      setPhase(null);
      return;
    }
    // Phase 1: render at the pre-toggle height with no transition; phase 2
    // (next frame): flip to the target under the resize spec's curve. A new
    // epoch restarts the sequence cleanly.
    setPhase("from");
    let raf = requestAnimationFrame(() => {
      raf = 0;
      setPhase("to");
    });
    return () => {
      cancelAnimationFrame(raf);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fold?.epoch, animating, reduced]);

  const ellipsisH = expanded ? 0 : USER_LINE_HEIGHT;
  let height: number | undefined;
  let transition: string | undefined;
  if (animating && phase !== null) {
    const from = fold?.from ?? USER_COLLAPSED_HEIGHT;
    const to = expanded ? fullH : USER_COLLAPSED_HEIGHT;
    height = Math.max(0, (phase === "from" ? from : to) - ellipsisH);
    if (phase === "to") {
      const curve = userResizeCurve(Math.max(0, fullH - USER_COLLAPSED_HEIGHT));
      const curveVar = curve === "easeInOut" ? "--rb-ease-ease-in-out" : "--rb-ease-ease-out";
      transition = `height ${durationMs}ms var(${curveVar})`;
    } else {
      transition = "none";
    }
  } else if (!expanded) {
    height = USER_COLLAPSED_TEXT_HEIGHT;
  }
  return (
    <div className="user-body-inner">
      <div className="user-text-clip" style={{ height, transition }}>
        <div className="user-text" ref={textRef}>
          {children}
        </div>
      </div>
      {!expanded && <div className="user-ellipsis">...</div>}
    </div>
  );
}

/** The bubble's text runs, mention spans rendered as chips (§2.7). */
function UserText({ text, mentions }: { text: string; mentions: readonly SentMentionSpan[] }) {
  if (mentions.length === 0) {
    return <>{text}</>;
  }
  const out: ReactNode[] = [];
  let at = 0;
  mentions.forEach((mention, ix) => {
    if (mention.start > at) {
      out.push(text.slice(at, mention.start));
    }
    out.push(
      <span key={ix} className="user-mention">
        {text.slice(mention.start, mention.end)}
      </span>,
    );
    at = mention.end;
  });
  if (at < text.length) {
    out.push(text.slice(at));
  }
  return <>{out}</>;
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

// ── Input and error chips (§2.9 / §2.10) ────────────────────────────────────

function InputChipRow({ header, resolved }: { header: string; resolved: boolean }) {
  return (
    <div className="input-chip-row">
      <div className="input-chip">
        <span className="input-chip-tile" aria-hidden>
          <Icon name="chatRoundLine" size={12} />
        </span>
        {/* Neutral tones throughout — resolution never recolors the chip. */}
        <span className="input-chip-label">Question</span>
        <span className="input-chip-text">{resolved ? header : "Awaiting your answer…"}</span>
      </div>
    </div>
  );
}

function ErrorChipRow({ message }: { message: string }) {
  return (
    <div className="error-chip-row">
      <div className="error-chip" role="alert">
        <span className="error-chip-tile" aria-hidden>
          <Icon name="dangerTriangle" size={12} />
        </span>
        <span className="error-chip-label">Error</span>
        {/* The message WRAPS: a one-line ellipsis made a startup-crash report
            undiagnosable (transcript.rs:6531-6534). */}
        <span className="error-chip-text">{message}</span>
      </div>
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
