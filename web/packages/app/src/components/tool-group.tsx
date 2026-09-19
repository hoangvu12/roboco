import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type CSSProperties,
  type ReactNode,
} from "react";
import { Icon } from "@roboco/icons";
import type { FetchToolBlobReply } from "@roboco/proto";
import { methods } from "@roboco/engine-client";
import { useResolvedAppearance } from "../state/appearance";
import type { FileDiff } from "../lib/diff";
import type { InlineRun } from "../lib/markdown";
import {
  BLOB_AFFORDANCE_HEIGHT,
  CHIPS_TOP_PAD,
  CHIP_CARD_HEIGHT,
  CHIP_HEIGHT,
  detailHeight,
  fileBadgeName,
  formatKb,
  isAgentTool,
  isSpawnLink,
  subagentModel,
  subagentTabTitle,
  toolChipContent,
  toolGroupCollapses,
  TOOL_GROUP_HEADER_HEIGHT,
  toolGroupTitle,
  toolIconName,
  type ToolDetail,
  type ToolItem,
} from "../lib/transcript";
import { wellBg } from "../lib/file-icons";
import {
  FOLD_TWEEN_WINDOW_MS,
  TOOL_CONNECTOR_REVEAL_MS,
  ACTIVITY_TEXT_GAP,
  toolConnectorContinuation,
  toolConnectorParts,
  toolConnectorRevealProgress,
  toolDisclosureProgress,
  toolFoldProgress,
  toolRevealClock,
  toolRowRevealProgress,
  ToolGroupMotionStore,
  type BlobFetch,
  type FoldState,
} from "../lib/tool-motion";
import { ActivityRail } from "./activity-rail";
import { FileBodyUpto, FilePlaneScroll } from "./diff-view";
import { FileIcon } from "./files/file-icon";
import { GlyphSpinner } from "./glyph-spinner";

/**
 * The transcript's task tree — the desktop's `render_tool_group`
 * (transcript.rs:5837) and its chip family (`chip_header_row` :6871,
 * `tool_chip` :7327, `subagent_chip` :7392, `detail_body` :6688).
 *
 * A group is either **collapsible** (at least one non-agent tool — a quiet
 * summary header with a shimmering title and a chevron) or a **standalone
 * spawn card row** (all agent chips, always open, no header). Each step is a
 * 32px rail row drawn against the activity rail, staggering in on arrival
 * (90ms first-row delay for a new group, 65ms per arrival, 360ms height
 * clip, 480ms connector draw, a 4px lift + fade on the content only). Every
 * step expands in place: invocation block, separator, detail block, then a
 * blob affordance row when one is offered; spawn chips are LINKS whose whole
 * card opens the subagent's transcript as a right-pane tab.
 *
 * While any tween/reveal is unfinished the row re-renders on the SHARED rAF
 * clock (ticket 59, the desktop's invisible per-frame canvas — ONE loop for
 * every live row, armed by the first subscriber and stopped by the last);
 * per-row timings are unchanged.
 */

// ---------------------------------------------------------------------------
// The host's contract
// ---------------------------------------------------------------------------

/** `TranscriptEvent::OpenSubagent` (transcript.rs:2740). */
export interface SubagentOpen {
  readonly chatId: string;
  readonly docId: string;
  readonly title: string;
  readonly frozen: boolean;
}

export interface ToolGroupRowProps {
  readonly rowId: string;
  readonly tools: readonly ToolItem[];
  /** Set at row build time: streaming && this group is the entry's LAST part. */
  readonly autoOpen: boolean;
  /** The transcript's chat id (the primary chat, or the subagent doc itself). */
  readonly chatId: string;
  /** The surface's tool-motion store (folds, reveals, blob fetches). */
  readonly motion: ToolGroupMotionStore;
  readonly client: {
    call: <T>(method: string, params: unknown) => Promise<T>;
  };
  readonly onOpenSubagent: (payload: SubagentOpen) => void;
}

const prefersReducedMotion = (): boolean =>
  typeof globalThis.matchMedia === "function" &&
  globalThis.matchMedia("(prefers-reduced-motion: reduce)").matches;

// ---------------------------------------------------------------------------
// The group row (render_tool_group :5837)
// ---------------------------------------------------------------------------

export function ToolGroupRow({ rowId, tools, autoOpen, chatId, motion, client, onOpenSubagent }: ToolGroupRowProps) {
  // Folds/fetches/reveals live in the surface's store: a virtualized row
  // scrolling back into view is a remount and must find its fold.
  useSyncExternalStore(motion.subscribe, motion.getVersion);
  const reduced = prefersReducedMotion();
  const [now, setNow] = useState(() => performance.now());

  const collapses = toolGroupCollapses(tools);
  const fold = motion.groupFold(rowId);
  const reveal = motion.revealOf(rowId);
  const starts = reveal?.starts ?? [];
  // A FUTURE start reads as pending (elapsed saturates at 0), exactly like
  // the desktop's checked_duration_since.
  const arrivalPending = !reduced && starts.some((start) => start !== null && now - start < TOOL_CONNECTOR_REVEAL_MS);
  const effectiveAutoOpen = autoOpen || arrivalPending;
  const open = !collapses || (fold?.open ?? effectiveAutoOpen);
  const active = collapses && autoOpen;
  const baseRowHeight = collapses ? 32 : CHIP_HEIGHT;

  // ── The chips' effective payloads (:5874-5989) ──────────────────────────
  const details: (ToolDetail | null)[] = [];
  const invocations: (ToolDetail | null)[] = [];
  const affordances: ({ ref: string; label: string; loading: boolean } | null)[] = [];
  const detailFolds: (FoldState | null)[] = [];
  const detailOpens: boolean[] = [];
  for (let ix = 0; ix < tools.length; ix += 1) {
    const tool = tools[ix]!;
    // Spawn chips never expand — the subagent doc is the record of what the
    // tool did; the whole chip is the "open that doc" click instead.
    if (isSpawnLink(tool)) {
      details.push(null);
      invocations.push(null);
      affordances.push(null);
      detailFolds.push(null);
      detailOpens.push(false);
      continue;
    }
    const detail = effectiveDetail(tool, motion);
    const invocation = tool.invocation;
    const key = `${rowId}#d${ix}`;
    const dfold = motion.detailFold(key);
    const defaultOpen = tool.isThought && !tool.resolved;
    details.push(detail);
    invocations.push(invocation);
    affordances.push(effectiveAffordance(tool, motion));
    detailFolds.push(dfold);
    detailOpens.push((detail !== null || invocation !== null) && (dfold?.open ?? defaultOpen));
  }

  // ── The chips' heights (analytic — :6000-6042) ──────────────────────────
  let motionActive = false;
  const rowHeights: number[] = [];
  for (let ix = 0; ix < tools.length; ix += 1) {
    const target = detailOpens[ix]
      ? baseRowHeight +
        (invocations[ix] !== null ? detailHeight(invocations[ix]!) : 0) +
        (details[ix] !== null ? detailHeight(details[ix]!) : 0) +
        (affordances[ix] !== null ? BLOB_AFFORDANCE_HEIGHT : 0)
      : baseRowHeight;
    const dfold = detailFolds[ix] ?? null;
    const from = dfold !== null && dfold.toggledAt !== null ? dfold.from + baseRowHeight - CHIP_CARD_HEIGHT : null;
    const tweened = tweenHeight(from, target, dfold, now, reduced);
    if (tweened.motion) {
      motionActive = true;
    }
    rowHeights.push(tweened.height);
  }

  // ── Reveal progress per row (:6044-6079) ───────────────────────────────
  const revealProgress: number[] = [];
  const connectorProgress: number[] = [];
  for (let ix = 0; ix < tools.length; ix += 1) {
    const start = starts[ix] ?? null;
    revealProgress.push(toolRowRevealProgress(start, now, reduced));
    connectorProgress.push(toolConnectorRevealProgress(start, now, reduced));
  }
  const headerReveal = collapses ? toolRowRevealProgress(reveal?.headerStartedAt ?? null, now, reduced) : 1;
  if (headerReveal < 1 || revealProgress.some((p) => p < 1) || connectorProgress.some((p) => p < 1)) {
    motionActive = true;
  }

  // ── Group body height (:6080-6087, :6360-6387) ─────────────────────────
  const revealedHeight = CHIPS_TOP_PAD + rowHeights.reduce((sum, height, ix) => sum + height * revealProgress[ix]!, 0);
  const bodyTarget = open ? revealedHeight : 0;
  const bodyTweened = tweenHeight(fold?.from ?? null, bodyTarget, fold, now, reduced);
  if (bodyTweened.motion) {
    motionActive = true;
  }
  const bodyHeight = bodyTweened.height;

  // The SHARED rAF clock (ticket 59): the desktop keeps requesting frames
  // while a tween/reveal is unfinished — ONE loop drives every live row and
  // stops when the last row's progress reaches 1. The row still computes
  // every progress from the delivered `now`, so its timings are untouched.
  useEffect(() => {
    if (!motionActive) {
      return;
    }
    return toolRevealClock.subscribe(setNow);
  }, [motionActive]);

  // The rendered-open flip without a user click (auto-open expiring) seeds
  // the fold's tween from the last RENDERED height (:5862-5870) — in a
  // layout effect so the seeded re-render lands before paint.
  useLayoutEffect(() => {
    motion.noteRendered(rowId, open, bodyHeight);
  });

  const disclosure = reduced ? (open ? 1 : 0) : toolDisclosureProgress(open, fold, now);

  const onToggleGroup = useCallback(
    (event: React.MouseEvent) => {
      event.stopPropagation();
      motion.toggleGroupFold(rowId, revealedHeight, effectiveAutoOpen);
    },
    [rowId, revealedHeight, effectiveAutoOpen, motion],
  );

  const fetchBlob = useCallback(
    (ref: string): void => {
      motion.beginBlobFetch(ref, () =>
        client.call<FetchToolBlobReply>(methods.FETCH_TOOL_BLOB, { blobRef: ref }).then((reply) => reply.text),
      );
    },
    [client, motion],
  );

  const onOpenSubagentFor = useCallback(
    (tool: ToolItem): void => {
      if (tool.subagentRef === null) {
        return;
      }
      const frozen = tool.subagentStatus === "done" || tool.subagentStatus === "failed";
      onOpenSubagent({
        chatId,
        docId: tool.subagentRef,
        title: subagentTabTitle(tool.call),
        frozen,
      });
    },
    [chatId, onOpenSubagent],
  );

  const shimmerActive = active && !reduced;

  const chips = tools.map((tool, ix) => (
    <ToolChipRow
      key={ix}
      rowId={rowId}
      ix={ix}
      tool={tool}
      collapses={collapses}
      continues={ix + 1 < tools.length}
      baseRowHeight={baseRowHeight}
      rowHeight={rowHeights[ix] ?? baseRowHeight}
      revealProgress={revealProgress[ix] ?? 1}
      connectorReveal={connectorProgress[ix] ?? 1}
      continuationReveal={toolConnectorContinuation(ix + 1 < tools.length ? (connectorProgress[ix + 1] ?? null) : null)}
      detail={details[ix] ?? null}
      invocation={invocations[ix] ?? null}
      detailFold={detailFolds[ix] ?? null}
      detailOpen={detailOpens[ix] ?? false}
      affordance={affordances[ix] ?? null}
      now={now}
      motion={motion}
      fetchBlob={fetchBlob}
      onOpenSubagent={onOpenSubagentFor}
    />
  ));

  if (!collapses) {
    // A spawn-only group renders its chips unwrapped — no fold, no header.
    return <div className="tool-group">{chips}</div>;
  }

  return (
    <div className="tool-group">
      <div className="tool-reveal" style={{ height: TOOL_GROUP_HEADER_HEIGHT * headerReveal }}>
        <ToolGroupHeader
          rowId={rowId}
          summary={toolGroupTitle(tools)}
          open={open}
          disclosure={disclosure}
          shimmer={shimmerActive}
          onToggle={onToggleGroup}
        />
      </div>
      <div className="tool-group-fold" style={{ height: bodyHeight }}>
        <div className="tool-group-body">{chips}</div>
      </div>
    </div>
  );
}

/**
 * The height tween shared by the group fold and the chip cards (:6025-6041,
 * :6360-6375): lerp from `from` to `target` over TOOL_FOLD while the fold's
 * clock is armed; past 140ms it saturates at `target` (an aged tween renders
 * its endpoint — a remount never flashes). Null `from` renders the target.
 */
function tweenHeight(
  from: number | null,
  target: number,
  fold: FoldState | null,
  now: number,
  reduced: boolean,
): { height: number; motion: boolean } {
  if (from === null || reduced || fold === null || fold.toggledAt === null) {
    return { height: target, motion: false };
  }
  const t = toolFoldProgress(fold, now);
  if (t === null) {
    return { height: target, motion: false };
  }
  return { height: from + (target - from) * t, motion: t < 1 };
}

// ---------------------------------------------------------------------------
// The group header (:6107-6158)
// ---------------------------------------------------------------------------

function ToolGroupHeader({
  rowId,
  summary,
  open,
  disclosure,
  shimmer,
  onToggle,
}: {
  rowId: string;
  summary: string;
  open: boolean;
  disclosure: number;
  shimmer: boolean;
  onToggle: (event: React.MouseEvent) => void;
}) {
  // −90° closed → 0° open over TOOL_FOLD.
  const rotation = -90 * (1 - disclosure);
  return (
    <button type="button" id={`${rowId}-hdr`} className="tool-group-header" aria-expanded={open} onClick={onToggle}>
      <span className="tool-group-chevron" aria-hidden>
        <Icon name="altArrowDown" size={14} style={{ transform: `rotate(${rotation}deg)` }} />
      </span>
      <span className={`tool-group-title ${shimmer ? "tool-shimmer" : ""}`}>{summary}</span>
    </button>
  );
}

// ---------------------------------------------------------------------------
// One chip row — plain rail chip, expandable card, or spawn link
// ---------------------------------------------------------------------------

interface ToolChipRowProps {
  readonly rowId: string;
  readonly ix: number;
  readonly tool: ToolItem;
  readonly collapses: boolean;
  readonly continues: boolean;
  readonly baseRowHeight: number;
  readonly rowHeight: number;
  readonly revealProgress: number;
  readonly connectorReveal: number;
  readonly continuationReveal: number;
  readonly detail: ToolDetail | null;
  readonly invocation: ToolDetail | null;
  readonly detailFold: FoldState | null;
  readonly detailOpen: boolean;
  readonly affordance: { ref: string; label: string; loading: boolean } | null;
  readonly now: number;
  readonly motion: ToolGroupMotionStore;
  readonly fetchBlob: (ref: string) => void;
  readonly onOpenSubagent: (tool: ToolItem) => void;
}

function ToolChipRow(props: ToolChipRowProps) {
  const { tool, collapses, ix, rowHeight, revealProgress, detail, invocation } = props;

  // A spawn link: the WHOLE card opens the subagent's tab (:6175-6197).
  if (isSpawnLink(tool)) {
    return <SubagentChip tool={tool} rail={collapses} onOpen={() => props.onOpenSubagent(tool)} />;
  }

  const { connectorReveal, continuationReveal } = props;
  const contentReveal = toolConnectorParts(connectorReveal, ix > 0).branch;
  const expandable = detail !== null || invocation !== null;

  if (!expandable) {
    // `tool_chip` (:7327) — a detail-less row: rail + a borderless card.
    return revealRow(
      <div className="tool-chip" style={{ height: props.baseRowHeight }}>
        {collapses && (
          <ActivityRail
            tool={tool}
            hasPredecessor={ix > 0}
            continues={props.continues}
            connectorReveal={connectorReveal}
            continuationReveal={continuationReveal}
            bendRowHeight={props.baseRowHeight}
            canvasHeight={props.baseRowHeight}
          />
        )}
        <div
          className={`tool-chip-card ${collapses ? "" : "tool-chip-card-bordered"}`}
          style={{
            height: CHIP_CARD_HEIGHT,
            marginTop: (props.baseRowHeight - CHIP_CARD_HEIGHT) / 2,
            marginBottom: (props.baseRowHeight - CHIP_CARD_HEIGHT) / 2,
            // The rail margin (transcript.rs:7363): the label breaks 8px off
            // the rail icon when the rail renders.
            marginLeft: collapses ? ACTIVITY_TEXT_GAP : undefined,
            ...(collapses && contentReveal < 1 ? liftStyle(contentReveal) : null),
          }}
        >
          <ChipHeaderRow tool={tool} trail={null} />
        </div>
      </div>,
      rowHeight,
      revealProgress,
    );
  }

  // The expandable card (:6231-6352).
  const key = `${props.rowId}#d${ix}`;
  const animating =
    props.detailFold !== null &&
    props.detailFold.epoch > 0 &&
    props.detailFold.toggledAt !== null &&
    props.now - props.detailFold.toggledAt < FOLD_TWEEN_WINDOW_MS;
  const cardHeight = rowHeight - props.baseRowHeight + CHIP_CARD_HEIGHT;
  const defaultOpen = tool.isThought && !tool.resolved;
  const onToggle = (event: React.MouseEvent): void => {
    event.stopPropagation();
    props.motion.toggleDetailFold(key, cardHeight, defaultOpen);
  };
  return revealRow(
    <div className="tool-chip" style={{ height: rowHeight }}>
      {collapses && (
        <ActivityRail
          tool={tool}
          hasPredecessor={ix > 0}
          continues={props.continues}
          connectorReveal={connectorReveal}
          continuationReveal={continuationReveal}
          bendRowHeight={props.baseRowHeight}
          canvasHeight={rowHeight}
        />
      )}
      <div
        className={`tool-chip-card tool-chip-card-expandable ${collapses ? "" : "tool-chip-card-bordered"}`}
        style={{
          height: cardHeight,
          marginTop: (props.baseRowHeight - CHIP_CARD_HEIGHT) / 2,
          marginBottom: (props.baseRowHeight - CHIP_CARD_HEIGHT) / 2,
          // The rail margin (transcript.rs:6233): same 8px icon→label break
          // when the rail renders.
          marginLeft: collapses ? ACTIVITY_TEXT_GAP : undefined,
          ...(collapses && contentReveal < 1 ? liftStyle(contentReveal) : null),
        }}
      >
        <ChipHeaderRow tool={tool} trail="chevron" open={props.detailOpen} toggle={{ onToggle, defaultOpen }} />
        {/* The body stays mounted while the close tween shrinks over it
            (FOLD_TWEEN_WINDOW). */}
        {(props.detailOpen || animating) && (
          <ToolDetailPane
            invocation={invocation}
            detail={detail}
            affordance={props.affordance}
            collapses={collapses}
            fetchBlob={props.fetchBlob}
          />
        )}
      </div>
    </div>,
    rowHeight,
    revealProgress,
  );
}

/** The content-only 4px lift + fade (the connector keeps full contrast). */
function liftStyle(contentReveal: number): CSSProperties {
  return {
    transform: `translateY(${4 * (1 - contentReveal)}px)`,
    opacity: contentReveal,
  };
}

/** `reveal_tool_row` (:7210) — only the height clips. */
function revealRow(children: ReactNode, height: number, progress: number): ReactNode {
  if (progress >= 1) {
    return children;
  }
  return (
    <div className="tool-reveal" style={{ height: height * progress }}>
      {children}
    </div>
  );
}

// ---------------------------------------------------------------------------
// `chip_header_row` (:6871) — the chip's content row
// ---------------------------------------------------------------------------

function ChipHeaderRow({
  tool,
  trail,
  open = false,
  toggle,
}: {
  tool: ToolItem;
  trail: "chevron" | "openArrow" | null;
  open?: boolean;
  /**
   * The expandable card's click target — the desktop's `chip_header` is the
   * SAME row with `cursor_pointer` + the toggle, never a wrapper.
   */
  toggle?: { onToggle: (event: React.MouseEvent) => void; defaultOpen: boolean };
}) {
  const appearance = useResolvedAppearance();
  const activity = !isAgentTool(tool);
  const { label, detail } = tool.isThought
    ? { label: "Thought process", detail: "" }
    : toolChipContent(tool.call);
  const filePath =
    tool.call.kind === "readFile" || tool.call.kind === "writeFile" || tool.call.kind === "editFile"
      ? tool.call.path
      : tool.call.kind === "applyPatch" && tool.call.path !== null && tool.call.path !== undefined
        ? tool.call.path
        : null;
  const running = tool.subagentRef !== null && tool.subagentStatus === "running";
  const failed = tool.isError || (tool.subagentRef !== null && tool.subagentStatus === "failed");
  // Group-hover lights the label/detail/chevron only on expandable rail rows
  // that did not fail (:6898).
  const hoverText = activity && trail !== null && !failed;
  const model = tool.call.kind === "unknown" || tool.call.kind === "mcp" ? subagentModel(tool.call) : null;

  return (
    <div
      className={`tool-chip-head ${activity ? "" : "tool-chip-head-card"} ${toggle !== undefined ? "tool-chip-head-button" : ""}`}
      data-hover-text={hoverText ? "1" : undefined}
      data-failed={failed ? "1" : undefined}
      role={toggle !== undefined ? "button" : undefined}
      tabIndex={toggle !== undefined ? 0 : undefined}
      aria-expanded={toggle !== undefined ? open : undefined}
      onClick={toggle?.onToggle}
      onKeyDown={
        toggle !== undefined
          ? (event) => {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                toggle.onToggle(event as unknown as React.MouseEvent);
              }
            }
          : undefined
      }
    >
      {!activity && (
        <span className="tool-chip-icon-tile" aria-hidden>
          <Icon name={tool.isThought ? "chatRoundLine" : toolIconName(tool.call)} size={12} />
        </span>
      )}
      <span className="tool-chip-label">{label}</span>
      {filePath !== null ? (
        <FileBadge path={filePath} failed={failed} appearance={appearance} />
      ) : activity && detail.length === 0 ? null : (
        <span className="tool-chip-detail">{detail}</span>
      )}
      {model !== null && <span className="tool-chip-model">{model}</span>}
      {running && <GlyphSpinner size={8} className="tool-chip-spinner" />}
      {trail !== null && (
        <span className={`tool-chip-trail ${activity ? "" : "tool-chip-trail-card"} ${trail === "openArrow" ? "tool-chip-trail-open" : ""}`}>
          {trail === "chevron" ? (
            <Icon name={open ? "altArrowDown" : "altArrowRight"} size={12} />
          ) : (
            <Icon name="arrowUpRight" size={11} />
          )}
        </span>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// `FileBadge` — the frosted file-action chip (:6986-7037)
// ---------------------------------------------------------------------------

function FileBadge({
  path,
  failed,
  appearance,
}: {
  path: string;
  failed: boolean;
  appearance: "light" | "dark";
}) {
  return (
    <span className="tool-chip-detail-slot">
      <span className={`tool-file-badge ${failed ? "tool-file-badge-failed" : ""}`}>
        <span className="tool-file-badge-well" style={{ background: wellBg(appearance, true) }}>
          <FileIcon kind="file" name={path} appearance={appearance} size={14} />
        </span>
        <span className="tool-file-badge-name">{fileBadgeName(path)}</span>
      </span>
    </span>
  );
}

// ---------------------------------------------------------------------------
// `subagent_chip` (:7392) — the spawn link
// ---------------------------------------------------------------------------

function SubagentChip({ tool, rail, onOpen }: { tool: ToolItem; rail: boolean; onOpen: () => void }) {
  return (
    <div className="tool-chip" style={{ height: CHIP_HEIGHT }}>
      {rail && <span className="tool-agent-guide" aria-hidden />}
      <div
        className="tool-chip-card tool-chip-card-bordered tool-agent-link"
        role="button"
        tabIndex={0}
        title="Open subagent"
        style={{
          height: CHIP_CARD_HEIGHT,
          marginTop: (CHIP_HEIGHT - CHIP_CARD_HEIGHT) / 2,
          marginBottom: (CHIP_HEIGHT - CHIP_CARD_HEIGHT) / 2,
          marginLeft: rail ? 12 : undefined,
        }}
        onClick={onOpen}
        onKeyDown={(event) => {
          if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            onOpen();
          }
        }}
      >
        <ChipHeaderRow tool={tool} trail="openArrow" />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// The detail panel + bodies (:6273-6324, `detail_body` :6688)
// ---------------------------------------------------------------------------

function ToolDetailPane({
  invocation,
  detail,
  affordance,
  collapses,
  fetchBlob,
}: {
  invocation: ToolDetail | null;
  detail: ToolDetail | null;
  affordance: { ref: string; label: string; loading: boolean } | null;
  collapses: boolean;
  fetchBlob: (ref: string) => void;
}) {
  return (
    <div className="tool-chip-body">
      {invocation !== null && (
        <>
          <div className={`tool-detail-separator ${collapses ? "tool-detail-separator-rail" : ""}`} />
          <DetailBody detail={invocation} invocation />
        </>
      )}
      {detail !== null && (
        <>
          <div className={`tool-detail-separator ${collapses ? "tool-detail-separator-rail" : ""}`} />
          <DetailBody detail={detail} />
        </>
      )}
      {affordance !== null && (
        <button
          type="button"
          className="tool-full-button"
          disabled={affordance.loading}
          onClick={(event) => {
            event.stopPropagation();
            fetchBlob(affordance.ref);
          }}
        >
          {affordance.label}
        </button>
      )}
    </div>
  );
}

function DetailBody({ detail, invocation = false }: { detail: ToolDetail; invocation?: boolean }) {
  const appearance = useResolvedAppearance();
  switch (detail.kind) {
    case "output":
      return (
        <div className={`tool-output ${invocation ? "tool-invocation" : ""}`}>
          {detail.lines.map((line, ix) => (
            <div key={ix} className="tool-output-line">
              <span className="tool-output-text">{line}</span>
            </div>
          ))}
          {detail.truncatedBy > 0 && (
            <div className="tool-output-line tool-output-more">… {detail.truncatedBy} more lines</div>
          )}
        </div>
      );
    case "thought":
      return (
        <div className="tool-output tool-thought">
          {detail.lines.map((line, ix) => (
            <div key={ix} className="tool-output-line">
              {line.length === 0 ? null : (
                <span className="tool-thought-line">
                  {line.map((run, runIx) => (
                    <ThoughtRun key={runIx} run={run} />
                  ))}
                </span>
              )}
            </div>
          ))}
          {detail.truncatedBy > 0 && (
            <div className="tool-output-line tool-output-more">… {detail.truncatedBy} more lines</div>
          )}
        </div>
      );
    case "stats":
      return (
        <div className="tool-output tool-stats">
          {detail.stats.map((stat, ix) => (
            <div key={ix} className="tool-stat-row">
              <FileIcon kind="file" name={stat.path} appearance={appearance} size={14} />
              <span className="tool-stat-path">{stat.path}</span>
              <span className="tool-stat-add">+{stat.additions}</span>
              <span className="tool-stat-del">−{stat.deletions}</span>
            </div>
          ))}
        </div>
      );
    case "diff":
      return <ToolDiffBody file={detail.file} />;
  }
}

/**
 * `thought_line_text` (:6812) — faint prose, semibold bold, mono code,
 * underlined links that are NOT clickable (a thought is a record, not a
 * surface), 1px strikethrough.
 */
function ThoughtRun({ run }: { run: InlineRun }) {
  let content: ReactNode = run.text;
  if (run.style.code) {
    content = <code className="tool-thought-code">{content}</code>;
  }
  if (run.style.bold) {
    content = <strong className="tool-thought-strong">{content}</strong>;
  }
  if (run.style.italic) {
    content = <em>{content}</em>;
  }
  if (run.style.strikethrough) {
    content = <s>{content}</s>;
  }
  if (run.style.link !== null && run.style.link !== undefined) {
    // Underlined span, never an anchor — a thought link is decoration.
    content = <span className="tool-thought-link">{content}</span>;
  }
  return <>{content}</>;
}

/**
 * The diff detail: the Changes pane's own body renderer (`FileBodyUpto`,
 * ticket 22's port of `render_file_body_with_syntax`), comments disabled —
 * an inline tool diff is a record, not a review surface.
 */
function ToolDiffBody({ file }: { file: FileDiff }) {
  const scrollRef = useRef<FilePlaneScroll | null>(null);
  if (scrollRef.current === null) {
    scrollRef.current = new FilePlaneScroll();
  }
  return (
    <div className="tool-diff-body">
      <FileBodyUpto file={file} maxPx={Number.POSITIVE_INFINITY} layout="unified" scroll={scrollRef.current} />
      <div className="diff-body-pad" aria-hidden />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Blob-upgrade resolution (render_tool_group :5880-5959)
// ---------------------------------------------------------------------------

/** The most recently REQUESTED Ready blob wins; else the doc detail. */
function effectiveDetail(tool: ToolItem, motion: ToolGroupMotionStore): ToolDetail | null {
  let best: { order: number; detail: ToolDetail } | null = null;
  for (const ref of [tool.diffRef, tool.outputRef]) {
    if (ref === null) {
      continue;
    }
    const fetch = motion.blobFetchOf(ref);
    if (fetch !== null && fetch.state === "ready") {
      const order = motion.blobOrderOf(ref);
      if (best === null || order > best.order) {
        best = { order, detail: fetch.detail };
      }
    }
  }
  return best !== null ? best.detail : tool.detail;
}

/** The ref of the blob whose upgrade is currently showing, if any. */
function shownBlobRef(tool: ToolItem, motion: ToolGroupMotionStore): string | null {
  let best: { order: number; ref: string } | null = null;
  for (const ref of [tool.diffRef, tool.outputRef]) {
    if (ref === null) {
      continue;
    }
    const fetch: BlobFetch | null = motion.blobFetchOf(ref);
    if (fetch !== null && fetch.state === "ready") {
      const order = motion.blobOrderOf(ref);
      if (best === null || order > best.order) {
        best = { order, ref };
      }
    }
  }
  return best !== null ? best.ref : null;
}

/**
 * The one affordance slot (:5913-5959): diff offered first (the richer
 * upgrade), then the output. A fetched-and-SHOWING ref hands the slot to the
 * next unfetched one; a fetched-but-not-showing ref stays offered as a
 * no-fetch recency toggle. Failure re-arms as a manual retry — there is no
 * backoff ladder.
 */
function effectiveAffordance(
  tool: ToolItem,
  motion: ToolGroupMotionStore,
): { ref: string; label: string; loading: boolean } | null {
  if (isSpawnLink(tool)) {
    return null;
  }
  const shown = shownBlobRef(tool, motion);
  const candidates: { ref: string | null; what: string; bytes: number | null }[] = [
    { ref: tool.diffRef, what: "diff", bytes: null },
    { ref: tool.outputRef, what: "output", bytes: tool.outputBytes },
  ];
  for (const { ref, what, bytes } of candidates) {
    if (ref === null) {
      continue;
    }
    const fetch = motion.blobFetchOf(ref);
    if (fetch === null) {
      return {
        ref,
        label: bytes !== null ? `Show full ${what} (${formatKb(bytes)})` : `Show full ${what}`,
        loading: false,
      };
    }
    if (fetch.state === "loading") {
      return { ref, label: `Loading full ${what}…`, loading: true };
    }
    if (fetch.state === "failed") {
      return { ref, label: `Couldn't load full ${what} — tap to retry`, loading: false };
    }
    if (ref === shown) {
      continue;
    }
    return { ref, label: `Show full ${what}`, loading: false };
  }
  return null;
}
