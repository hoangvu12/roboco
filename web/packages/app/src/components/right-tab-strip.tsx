import { useState } from "react";
import { Icon } from "@roboco/icons";
import {
  SURFACE_ICONS,
  SURFACE_TITLES,
  rightPaneStore,
  type ChatPaneState,
  type RightSurface,
} from "../state/right-pane";

/**
 * The right pane's surface tabs — the desktop's `render_right_tab_strip`.
 *
 * The strip lives in the titlebar band, not in the pane, because the titlebar
 * overlay owns that band's hit-testing: controls mounted in the pane itself
 * would sit under the drag region and never see a click.
 *
 * Chip geometry is the desktop's: a 24px-tall, 112px-wide slot on a 4px gap
 * with a 6px radius, 4px/8px padding and a 3px inner gap. The active chip
 * wears `wash(0.10)`, the rest light up at `wash(0.06)` on hover. The leading
 * 18px slot holds the surface icon and swaps in place for the close ✕ while
 * the chip is hovered — same slot, no width jump.
 */

/** `CHIP_W` — the terminal drawer's drag mechanics assume uniform widths. */
const CHIP_W = 112;
const CHIP_SLOT = CHIP_W + 4;

export function RightTabStrip({ chatId, pane }: { chatId: string; pane: ChatPaneState }) {
  const [drag, setDrag] = useState<{ from: number; over: number } | null>(null);

  function onDragStart(index: number): void {
    setDrag({ from: index, over: index });
  }

  function onDragOver(event: React.DragEvent, index: number): void {
    event.preventDefault();
    setDrag((current) => (current === null || current.over === index ? current : { ...current, over: index }));
  }

  function onDrop(): void {
    if (drag !== null && drag.from !== drag.over) {
      rightPaneStore.moveTab(chatId, drag.from, drag.over);
    }
    setDrag(null);
  }

  return (
    <div className="right-tab-strip" role="tablist" aria-label="Panel surfaces">
      {pane.tabs.map((surface, index) => (
        <TabChip
          key={surface}
          surface={surface}
          index={index}
          active={surface === pane.active}
          // While a chip is dragged, the ones it has passed slide out of its
          // way — the desktop animates the same offsets.
          offset={slideOffset(drag, index)}
          onPick={() => rightPaneStore.setActive(chatId, surface)}
          onClose={() => rightPaneStore.close(chatId)}
          onDragStart={() => onDragStart(index)}
          onDragOver={(event) => onDragOver(event, index)}
          onDrop={onDrop}
        />
      ))}
    </div>
  );
}

/** How far a chip slides to open a gap for the dragged one. */
function slideOffset(drag: { from: number; over: number } | null, index: number): number {
  if (drag === null || drag.from === drag.over || index === drag.from) {
    return 0;
  }
  if (drag.from < drag.over && index > drag.from && index <= drag.over) {
    return -CHIP_SLOT;
  }
  if (drag.from > drag.over && index >= drag.over && index < drag.from) {
    return CHIP_SLOT;
  }
  return 0;
}

function TabChip({
  surface,
  index,
  active,
  offset,
  onPick,
  onClose,
  onDragStart,
  onDragOver,
  onDrop,
}: {
  surface: RightSurface;
  index: number;
  active: boolean;
  offset: number;
  onPick: () => void;
  onClose: () => void;
  onDragStart: () => void;
  onDragOver: (event: React.DragEvent) => void;
  onDrop: () => void;
}) {
  const title = SURFACE_TITLES[surface];
  return (
    <div
      className={`right-tab ${active ? "right-tab-active" : ""}`}
      role="tab"
      aria-selected={active}
      aria-label={title}
      tabIndex={0}
      draggable
      style={offset === 0 ? undefined : { transform: `translateX(${offset}px)` }}
      onClick={onPick}
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          onPick();
        }
      }}
      // Middle-click closes, like every tab strip.
      onAuxClick={(event) => {
        if (event.button === 1) {
          event.preventDefault();
          onClose();
        }
      }}
      onDragStart={onDragStart}
      onDragOver={onDragOver}
      onDrop={(event) => {
        event.preventDefault();
        onDrop();
      }}
      onDragEnd={onDrop}
      data-index={index}
    >
      <span className="right-tab-slot">
        <Icon name={SURFACE_ICONS[surface]} size={13} className="right-tab-icon" />
        <button
          type="button"
          className="right-tab-close"
          aria-label={`Close ${title}`}
          onClick={(event) => {
            event.stopPropagation();
            onClose();
          }}
        >
          <Icon name="close" size={11} />
        </button>
      </span>
      <span className="right-tab-title">{title}</span>
    </div>
  );
}
