/**
 * The popup layer — the mount wrapper every floating menu on the web sits
 * in: a portal to `document.body` (the desktop's `deferred` floating layer),
 * placed by the anchored-menu helpers, playing `menu-in` on open and
 * `menu-out` through the closing phase, with outside-pointerdown dismissal,
 * the exit occlusion overlay, and the card-frame focus behavior of the
 * desktop's `pickers.rs::popover_frame`.
 */

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
import { createPortal } from "react-dom";
import { PopupLifecycle } from "../../lib/popup-lifecycle";

export { usePopup } from "../../lib/popup-lifecycle";

/**
 * Mark a trigger element with this attribute (`data-rb-popup-trigger`) so a
 * popup's outside-press guard lets the press through: the trigger's own
 * pointerdown notes the press (`noteTriggerPress`) and its click toggles,
 * exactly the desktop's on-mouse-down note + on-click toggle pair. Presses
 * anywhere else are consumed so content behind the menu cannot act on them.
 */
export const POPUP_TRIGGER_ATTR = "data-rb-popup-trigger";

export interface PopupProps<T> {
  /** The lifecycle instance driving this popup (see `usePopup`). */
  readonly popup: PopupLifecycle<T>;
  /**
   * Fixed-position CSS for the card, given its measured size. Callers close
   * over the trigger element and call the `popover-anchor` helpers here.
   */
  readonly placement: (size: { width: number; height: number }) => CSSProperties;
  /**
   * Extra work when an outside press dismisses the popup. Escape is NOT
   * routed through here — it belongs to the menu's key handler via
   * `popup.closeByEscape()`.
   */
  readonly onDismiss?: () => void;
  /**
   * The card through both mounted states. `value` is `popup.get()` (it keeps
   * painting through the exit); `status` distinguishes the dead closing
   * phase for callers that need to freeze content.
   */
  readonly children: (value: T, status: "open" | "closing") => ReactNode;
}

const NO_SIZE = { width: 0, height: 0 };

/**
 * Renders nothing while closed; on open, portals the card to `<body>`,
 * measures it, and positions it via `placement`. While closing the card
 * stays mounted under the `menu-out` animation with dead hit-testing
 * (`pointer-events: none`) and a full-bleed occluding overlay on top of its
 * content — a dying menu's rows must not take clicks (`popover.rs:406`).
 */
export function Popup<T>(props: PopupProps<T>) {
  const { popup, placement, onDismiss, children } = props;
  const snapshot = useSyncExternalStore(
    (listener) => popup.subscribe(listener),
    () => popup.snapshot(),
    () => popup.snapshot(),
  );
  const layerRef = useRef<HTMLDivElement | null>(null);
  const [size, setSize] = useState(NO_SIZE);
  const [, setViewportTick] = useState(0);
  const mounted = snapshot.status !== "closed";
  const status = snapshot.status === "closing" ? "closing" : "open";

  // Measure the card, then keep the placement honest as it resizes and as
  // the window moves under it (a re-render re-invokes `placement`, which
  // reads the trigger rect fresh — the card's size alone would not move it).
  useLayoutEffect(() => {
    if (!mounted) {
      return;
    }
    const layer = layerRef.current;
    if (layer === null) {
      return;
    }
    const measure = (): void => {
      const rect = layer.getBoundingClientRect();
      setSize((current) =>
        current.width === rect.width && current.height === rect.height ? current : { width: rect.width, height: rect.height },
      );
    };
    const onViewport = (): void => {
      measure();
      setViewportTick((tick) => tick + 1);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(layer);
    window.addEventListener("resize", onViewport);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", onViewport);
    };
  }, [mounted, snapshot.value]);

  // Outside-press guard (`frosted_menu` + `pickers.rs::on_mouse_down_out`):
  // a capture-phase pointerdown outside the card begins the close and —
  // unless the press landed on a trigger (which notes it and toggles on the
  // click) — is consumed, so content behind the menu cannot act on it. The
  // FOLLOWING click always passes through.
  const dismissFromOutsidePress = useCallback(
    (event: PointerEvent): void => {
      const layer = layerRef.current;
      if (layer === null) {
        return;
      }
      if (event.target instanceof Node && layer.contains(event.target)) {
        return;
      }
      const onTrigger =
        event.target instanceof Element && event.target.closest(`[${POPUP_TRIGGER_ATTR}]`) !== null;
      const hadFocus =
        document.activeElement instanceof Node && layer.contains(document.activeElement);
      popup.dismiss();
      onDismiss?.();
      if (hadFocus) {
        // Outside clicks and navigation keep focus at the clicked
        // destination (`pickers.rs:871-890`).
        if (document.activeElement instanceof HTMLElement) {
          document.activeElement.blur();
        }
      }
      if (!onTrigger) {
        event.stopPropagation();
        event.preventDefault();
      }
    },
    [popup, onDismiss],
  );

  useEffect(() => {
    if (!mounted) {
      return;
    }
    window.addEventListener("pointerdown", dismissFromOutsidePress, true);
    return () => window.removeEventListener("pointerdown", dismissFromOutsidePress, true);
  }, [mounted, dismissFromOutsidePress]);

  // Card-frame focus (`pickers.rs:2743-2756`): a mouse-down inside the card
  // re-focuses the frame if focus had escaped it. NOT a focus trap — Tab is
  // never intercepted anywhere in this primitive.
  const onMouseDown = useCallback(() => {
    const layer = layerRef.current;
    if (layer !== null && popup.isOpen() && !layer.contains(document.activeElement)) {
      layer.focus({ preventScroll: true });
    }
  }, [popup]);

  if (!mounted) {
    return null;
  }

  return createPortal(
    <div
      ref={layerRef}
      className="popover-layer"
      data-rb-popup={status}
      style={placement(size)}
      tabIndex={-1}
      onMouseDown={onMouseDown}
    >
      {snapshot.value !== null ? children(snapshot.value, status) : null}
      {status === "closing" ? <div className="popover-exit-occlude" aria-hidden /> : null}
    </div>,
    document.body,
  );
}
