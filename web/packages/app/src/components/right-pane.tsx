import { useEffect, useRef, useState } from "react";
import { motion } from "@roboco/theme";
import { ChangesSurface } from "../routes/changes-page";
import { FilesSurface } from "../routes/files-page";
import { TerminalDock } from "../terminal/terminal-dock";
import { useTerminalStore } from "../terminal/store";
import type { ChatPaneState } from "../state/right-pane";

/**
 * The right pane — the desktop's changes/files/terminal panel.
 *
 * It is a flush, left-bordered glass panel beside the conversation column, not
 * an inset card, and it is a sibling of that column at the SHELL level (the
 * desktop's root row is `[sidebar][conversation][right pane]`). That placement
 * is what lets its surface and its seam hairline run the full window height,
 * behind the overlaid titlebar, the way `render_right_pane` does; only its
 * body pads down past the titlebar band. Its tabs live up in that band (see
 * `RightTabStrip`), and its drag seam is a shell child too, because this
 * column clips.
 *
 * Geometry is `right_pane_container` (`shell.rs:3826`): the OUTER width rides
 * the 200ms resize curve while an inner, right-anchored child holds the wider
 * endpoint's width for the duration. The surface therefore slides out from the
 * window's edge at a fixed layout width instead of reflowing through every
 * intermediate one — the same clip-don't-squeeze trick the sidebar uses,
 * mirrored. The column stays mounted at width 0 while closed, because a CSS
 * width transition has nothing to animate from if the element is absent.
 */

/** `motion::RESIZE` — the same 200ms the stylesheet transitions on. */
const RESIZE_MS = motion.specs.find((spec) => spec.name === "resize")?.durationMs ?? 200;

/**
 * TEMPORARY — bisecting the sidebar-toggle flash. The pane renders as an empty
 * box so nothing inside it can repaint while the sidebar animates. Flip back
 * to `false` (or delete this and its one use below) once the cause is found.
 */
const SURFACES_DISABLED = true;

export function RightPane({
  chatId,
  pane,
  openWidth,
  glide,
}: {
  chatId: string;
  pane: ChatPaneState;
  /** What the pane resolves to when open — its width, and its content's. */
  openWidth: number;
  /** Owned by the shell, which needs the same glide for the conversation. */
  glide: PaneGlide;
}) {
  const terminalStore = useTerminalStore();

  // A terminal surface needs a live PTY the moment its tab is shown; the
  // dock's own toggle is what mints one.
  useEffect(() => {
    if (pane.open && pane.active === "terminal") {
      terminalStore.open(chatId);
    }
  }, [pane.open, pane.active, chatId, terminalStore]);

  return (
    <aside
      className={`right-pane ${pane.expanded ? "right-pane-expanded" : ""}`}
      style={{ width: pane.open ? openWidth : 0 }}
      aria-label="Panel"
      aria-hidden={!pane.open}
    >
      {/* `null` width = follow the column, which is what takeover wants. */}
      <div
        className="right-pane-inner"
        style={glide.content === null ? undefined : { width: glide.content }}
      >
        {/*
          Surfaces stay mounted through the closing glide and leave with it —
          unmounting on the first frame would empty the panel before it moves.
        */}
        {glide.mounted && (
          <div className="right-pane-body">
            {SURFACES_DISABLED ? null : (
              <>
                {pane.active === "changes" && <ChangesSurface chatId={chatId} />}
                {pane.active === "files" && <FilesSurface />}
                {pane.active === "terminal" && (
                  <TerminalDock store={terminalStore} chatId={chatId} docked />
                )}
              </>
            )}
          </div>
        )}
      </div>
    </aside>
  );
}

/**
 * The desktop's `right_panel_content_width`, as a hook — and the two cases it
 * deliberately treats differently:
 *
 * - **Open / close** (`toggle_right_pane`) leaves
 *   `right_takeover_content_tween` unset, so the content width is
 *   `stable_panel_content_width` — the LARGER endpoint, held for the glide.
 *   The surface keeps its geometry and is revealed or clipped away rather than
 *   reflowing through every intermediate width. It also stays mounted for the
 *   duration, so a close animates instead of blinking out.
 * - **Takeover** (`toggle_right_pane_expand`) sets that tween to the same
 *   endpoints as the column, so the content TRACKS the animating width. The
 *   pane really is changing to a different width here, and holding one end
 *   would leave the surface laid out wrong for the whole 200ms. `null` means
 *   "follow the column" — the stylesheet's 100%.
 *
 * Drags are excluded by construction: they change neither flag, and must track
 * the pointer exactly rather than lag behind a held width.
 */
export function usePaneGlide(
  open: boolean,
  expanded: boolean,
  openWidth: number,
): { mounted: boolean; content: number | null } {
  const [glide, setGlide] = useState<{ held: number | null } | null>(null);
  const previous = useRef({ open, expanded, openWidth });

  useEffect(() => {
    const was = previous.current;
    previous.current = { open, expanded, openWidth };
    if (was.open === open && was.expanded === expanded) {
      // A drag (or a window resize): retarget with no glide and no hold.
      return;
    }
    // Takeover tracks; an open/close holds the wider endpoint.
    const held =
      was.expanded === expanded
        ? Math.max(was.open ? was.openWidth : 0, open ? openWidth : 0)
        : null;
    setGlide({ held });
    const timer = window.setTimeout(() => setGlide(null), RESIZE_MS);
    return () => window.clearTimeout(timer);
  }, [open, expanded, openWidth]);

  if (glide === null) {
    return { mounted: open, content: openWidth };
  }
  // Mid-glide the surface is mounted whichever way the column is moving.
  return {
    mounted: true,
    content: glide.held === null ? null : Math.max(glide.held, openWidth),
  };
}

export type PaneGlide = ReturnType<typeof usePaneGlide>;
