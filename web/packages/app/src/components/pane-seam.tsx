import { useCallback, useEffect, useRef, useState } from "react";

/**
 * A column resize seam — the desktop's `shell.rs::resize_handle`.
 *
 * Geometry is the desktop's: a 20px transparent hit target centred on the
 * seam, running from the titlebar's bottom edge to the window's, so the strip
 * never steals clicks from the titlebar controls above it. The visual divider
 * stays the adjacent pane's own 1px border; hovering adds a stronger highlight
 * that fades toward both ends, and it goes solid while dragging.
 *
 * Double-click restores the column's default width, as on the desktop.
 */
export interface PaneSeamProps {
  readonly label: string;
  /** Map a pointer x (client coords) to the requested column width. */
  readonly widthAt: (clientX: number) => number;
  readonly onWidth: (width: number) => void;
  readonly onReset: () => void;
  readonly className?: string;
}

export function PaneSeam({ label, widthAt, onWidth, onReset, className }: PaneSeamProps) {
  const [dragging, setDragging] = useState(false);
  // The handlers outlive a render, so read the live callbacks through a ref
  // rather than re-binding the window listeners on every parent render.
  const latest = useRef({ widthAt, onWidth });
  latest.current = { widthAt, onWidth };

  const onPointerDown = useCallback((event: React.PointerEvent) => {
    // Suppress the text selection a horizontal drag would otherwise paint
    // across the transcript.
    event.preventDefault();
    setDragging(true);
  }, []);

  useEffect(() => {
    if (!dragging) {
      return;
    }
    const onMove = (event: PointerEvent): void => {
      const { widthAt: map, onWidth: commit } = latest.current;
      commit(map(event.clientX));
    };
    const onUp = (): void => setDragging(false);
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
    window.addEventListener("pointercancel", onUp);
    // A drag owns the cursor even when the pointer outruns the 20px strip.
    const previous = document.body.style.cursor;
    document.body.style.cursor = "col-resize";
    // Columns glide on toggles but must track the pointer exactly on a drag —
    // the desktop drops the tween for the duration (`right_tween = None`).
    document.documentElement.setAttribute("data-rb-resizing", "");
    return () => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      window.removeEventListener("pointercancel", onUp);
      document.body.style.cursor = previous;
      document.documentElement.removeAttribute("data-rb-resizing");
    };
  }, [dragging]);

  return (
    <div
      className={`pane-seam ${dragging ? "pane-seam-dragging" : ""} ${className ?? ""}`}
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      onPointerDown={onPointerDown}
      onDoubleClick={onReset}
    >
      <div className="pane-seam-line" />
    </div>
  );
}
