/**
 * `MenuRow` / `MenuRowNav` — ports of `popover.rs:713-765`. One shared row
 * recipe at the desktop's metrics (gap 10, px 8, py 6, radius 8, 13px): the
 * selected state applies the card selection wash instantly (no transition),
 * the rest state fades its wash and text over `HOVER_FADE`, and the keyboard
 * highlight reads identically to selected (both use `card_selected_bg()` —
 * the shipped behavior, not the doc comment's two-tone intent).
 */

import type { ButtonHTMLAttributes, ReactNode } from "react";

export interface MenuRowProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  readonly children?: ReactNode;
  /**
   * The row's stable identity (its id string is the convention) — the web's
   * stand-in for the desktop's per-row `fade_key`, which keyed the hover
   * blend to the right element. Emitted as `data-rb-row-key`.
   */
  readonly fadeKey: string;
  /** Selected/active: the wash applies instantly, no transition (`:725-727`). */
  readonly selected?: boolean;
}

/** `menu_row` (`popover.rs:713-746`) — hover fades the wash and text over
 * 150ms via CSS transitions (`.menu-row`); selected snaps. */
export function MenuRow(props: MenuRowProps) {
  const { fadeKey, selected, className, children, ...rest } = props;
  return (
    <button
      type="button"
      data-rb-row-key={fadeKey}
      className={`menu-row ${selected === true ? "menu-row-selected" : ""} ${className ?? ""}`}
      aria-selected={selected === true || undefined}
      {...rest}
    >
      {children}
    </button>
  );
}

export interface MenuRowNavProps extends MenuRowProps {
  /** The keyboard cursor's row — the same wash as selected (`:752-765`).
   * Selected wins when both would apply; the two never read differently. */
  readonly highlighted?: boolean;
}

/** `menu_row_nav` (`popover.rs:752-765`) — `menu_row` plus the keyboard
 * navigation highlight. */
export function MenuRowNav(props: MenuRowNavProps) {
  const { highlighted, selected, fadeKey, className, children, ...rest } = props;
  const wash = selected === true || highlighted === true;
  return (
    <button
      type="button"
      data-rb-row-key={fadeKey}
      className={`menu-row ${wash ? "menu-row-selected" : ""} ${
        selected !== true && highlighted === true ? "menu-row-highlighted" : ""
      } ${className ?? ""}`}
      aria-selected={selected === true || undefined}
      {...rest}
    >
      {children}
    </button>
  );
}
