import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { Icon } from "@roboco/icons";
import {
  TREE_SPLIT_DEFAULT,
  TREE_SPLIT_HITBOX_HALF_WIDTH,
  TreeSidebarMotion,
  isWide,
  narrowTreeWidth,
} from "../../lib/tree-split";
import {
  RESIZE_EDGE_BOUNCE_MS,
  resizeBounceOffset,
  resizeDragSample,
  type ResizeEdge,
} from "../../state/layout";
import { Tooltip, TOOLTIP_VIEW_OPTIONS_MS } from "../ui/Tooltip";

/**
 * `TreeSplitPanel` — the promoted editor presentation's split layout
 * (crates/ui/src/files/mod.rs render:317-433 + preview.rs:35-39, 3214-3298):
 * the breadcrumb toolbar row with the fixed-width sidebar toggle slot
 * (`render_tree_toggle`), then the document body beside a collapsible,
 * resizable tree sidebar hosting ticket 24's `FileTreePanel`, with the
 * drag handle (`preview_split_handle`) between them.
 *
 * All state is in-memory component state — the desktop keeps `tree_width`
 * and the dismissed flag as purely local UI state on the surface (no
 * settings key; it resets on remount).
 *
 * - wide (surface ≥ 680px): the sidebar defaults visible unless dismissed
 *   this session; free-drag width within [220, 360]; double-click resets
 *   to the 286px default.
 * - narrow: the sidebar defaults hidden; when shown its width is
 *   `(surfaceWidth * 0.44).clamp(152, treeWidth)`.
 * - a breakpoint crossing flips visibility instantly (the sampler snaps
 *   when the target moved without an explicit toggle); the toggle click
 *   animates on the resize curve and reverses from the current openness.
 * - pinning a NEW drag edge arms `motion.rs`'s 5px out-and-back bounce.
 */

export interface TreeSplitPanelProps {
  /** The breadcrumb/toolbar row's leading content (`render_breadcrumb`). */
  readonly toolbar: ReactNode;
  /** The document body (`render_preview`'s half). */
  readonly body: ReactNode;
  /** The tree pane (ticket 24's `FileTreePanel`). */
  readonly sidebar: ReactNode;
  /** Focus the document body when the sidebar hides (mod.rs toggle). */
  readonly focusDocument: () => void;
}

export function TreeSplitPanel({ toolbar, body, sidebar, focusDocument }: TreeSplitPanelProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [surfaceWidth, setSurfaceWidth] = useState(0);
  const [treeWidth, setTreeWidth] = useState(TREE_SPLIT_DEFAULT);
  const [explicitVisible, setExplicitVisible] = useState(false);
  const [dismissed, setDismissed] = useState(false);
  const motionRef = useRef(new TreeSidebarMotion());
  const bounceRef = useRef<{ edge: Exclude<ResizeEdge, null>; startedAt: number } | null>(null);
  const [openness, setOpenness] = useState(0);
  const [animating, setAnimating] = useState(false);
  const [, setRenderTick] = useState(0);

  // The surface measures itself (preview.rs's width cell canvas).
  useEffect(() => {
    const container = containerRef.current;
    if (container === null) {
      return;
    }
    const apply = (): void => {
      const width = container.getBoundingClientRect().width;
      setSurfaceWidth((current) => (Math.abs(current - width) > 1 ? width : current));
    };
    apply();
    const observer = new ResizeObserver(apply);
    observer.observe(container);
    return () => {
      observer.disconnect();
    };
  }, []);

  const wide = isWide(surfaceWidth);
  const visible = explicitVisible || (wide && !dismissed);

  // The openness sampler: an explicit toggle animates (the tween reverses
  // from the CURRENT openness); layout changes and reduced motion snap —
  // `animating` false renders the end value directly, so those never wait
  // on a frame. A rAF loop drives the tween and the drag-edge bounce.
  useEffect(() => {
    let raf = 0;
    const tick = (): void => {
      const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      const [value, active] = motionRef.current.sample(visible, performance.now(), reduced);
      setOpenness(value);
      setAnimating(active);
      const bounce = bounceRef.current;
      if (
        bounce !== null &&
        (reduced || !visible || performance.now() - bounce.startedAt >= RESIZE_EDGE_BOUNCE_MS)
      ) {
        bounceRef.current = null;
      }
      setRenderTick((n) => n + 1);
      if (active || bounceRef.current !== null) {
        raf = requestAnimationFrame(tick);
      }
    };
    raf = requestAnimationFrame(tick);
    return () => {
      cancelAnimationFrame(raf);
    };
  }, [visible, animating]);

  const toggleSidebar = useCallback((): void => {
    const previous = explicitVisible || (wide && !dismissed);
    const next = !previous;
    if (previous) {
      setExplicitVisible(false);
      setDismissed(true);
      focusDocument();
    } else {
      setExplicitVisible(true);
      setDismissed(false);
    }
    motionRef.current.animateTo(previous, next, performance.now());
    setAnimating(true);
  }, [explicitVisible, wide, dismissed, focusDocument]);

  // The resize handle's drag: the requested width follows the pointer 1:1,
  // clamped; pinning a NEW edge arms the bounce once per hold.
  const beginHandleDrag = useCallback(
    (event: React.PointerEvent): void => {
      if (event.button !== 0 || !wide) {
        return;
      }
      event.preventDefault();
      const container = containerRef.current;
      if (container === null) {
        return;
      }
      let latched: ResizeEdge = null;
      const onMove = (move: PointerEvent): void => {
        const requested = container.getBoundingClientRect().right - move.clientX;
        const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        const sample = resizeDragSample(requested, 220, 360, latched, reduced);
        setTreeWidth(sample.width);
        if (sample.startsBounce && sample.edge !== null) {
          bounceRef.current = { edge: sample.edge, startedAt: performance.now() };
        }
        latched = sample.edge;
        setRenderTick((n) => n + 1);
      };
      const finish = (): void => {
        window.removeEventListener("pointermove", onMove);
        window.removeEventListener("pointerup", finish);
        window.removeEventListener("pointercancel", finish);
      };
      window.addEventListener("pointermove", onMove);
      window.addEventListener("pointerup", finish);
      window.addEventListener("pointercancel", finish);
    },
    [wide],
  );

  // Double-click the handle: back to the 286px default (preview.rs:3286).
  const resetHandle = useCallback((): void => {
    setTreeWidth(TREE_SPLIT_DEFAULT);
    bounceRef.current = null;
    setRenderTick((n) => n + 1);
  }, []);

  const bounce = bounceRef.current;
  const bounceOffset = bounce === null ? 0 : resizeBounceOffset(bounce.edge, performance.now() - bounce.startedAt);
  const baseWidth = wide ? treeWidth : narrowTreeWidth(surfaceWidth, treeWidth);
  const sidebarWidth = Math.max(0, baseWidth + (wide ? bounceOffset : 0));
  const effectiveOpenness = animating ? openness : visible ? 1 : 0;
  const outerWidth = sidebarWidth * effectiveOpenness;
  const handleVisible = wide && visible;

  return (
    <div className="files-split">
      <div className="surface-toolbar files-viewer-toolbar" role="toolbar" aria-label="File viewer">
        <div className="files-viewer-toolbar-leading">{toolbar}</div>
        <Tooltip
          label={visible ? "Hide files sidebar" : "Show files sidebar"}
          delay={TOOLTIP_VIEW_OPTIONS_MS}
          trigger={
            <div className="files-tree-toggle-slot">
              <button
                type="button"
                className="files-tree-toggle"
                aria-pressed={visible}
                aria-label={visible ? "Hide files sidebar" : "Show files sidebar"}
                onClick={toggleSidebar}
              >
                <Icon name="sidebarMinimalistic" size={14} />
              </button>
            </div>
          }
        />
      </div>
      <div ref={containerRef} className="files-split-row">
        <div className="files-split-document">{body}</div>
        <div className="files-split-sidebar" style={{ width: `${outerWidth}px` }} aria-hidden={!visible}>
          <div className="files-split-sidebar-inner" style={{ width: `${baseWidth}px` }}>
            {sidebar}
          </div>
        </div>
      </div>
      {handleVisible && (
        <div
          className="files-split-handle"
          style={{ right: `${Math.max(0, outerWidth - TREE_SPLIT_HITBOX_HALF_WIDTH)}px` }}
          onPointerDown={beginHandleDrag}
          onDoubleClick={resetHandle}
          role="separator"
          aria-orientation="vertical"
          aria-label="Resize files sidebar"
        >
          <span className="files-split-handle-line" />
        </div>
      )}
    </div>
  );
}
