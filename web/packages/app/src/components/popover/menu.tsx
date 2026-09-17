/**
 * The floating-card shell and its chrome — ports of the desktop's
 * `popover.rs` element builders: `popover_card`/`popover_card_flush`
 * (`:306-331`), `palette_card` (`:820`), `menu_heading` (`:771-795`),
 * `menu_separator`/`menu_section` (`:799-970`), `search_input_frame`
 * (`:946-955`), the key-cap family (`:841-940`), and the dialog primitives
 * (`:657-1076`). Geometry lives in `styles/app.css` next to each class.
 */

import type { ButtonHTMLAttributes, CSSProperties, ReactNode } from "react";

export interface PopoverCardProps {
  readonly children?: ReactNode;
  readonly className?: string;
  readonly style?: CSSProperties;
  readonly role?: string;
  readonly ariaLabel?: string;
  readonly onKeyDown?: (event: React.KeyboardEvent<HTMLDivElement>) => void;
}

/**
 * The floating-menu surface (`popover.rs:306-325`): radius 12, `1px
 * hairline(0.10)` border, the glass overlay tint over a 44px backdrop blur,
 * 4px inset, capped at 640px (`pickers.rs:2738`).
 */
export function PopoverCard(props: PopoverCardProps) {
  const { children, className, style, role, ariaLabel, onKeyDown } = props;
  return (
    <div
      className={`popover-card ${className ?? ""}`}
      style={style}
      role={role}
      aria-label={ariaLabel}
      onKeyDown={onKeyDown}
    >
      {children}
    </div>
  );
}

/** `popover_card_flush` (`popover.rs:329`) — no inset, for cards that own
 * their internal panes. */
export function PopoverCardFlush(props: PopoverCardProps) {
  return <PopoverCard {...props} className={`popover-card-flush ${props.className ?? ""}`} />;
}

export interface PaletteCardProps {
  /** Explicit card width (the desktop takes `Pixels`). */
  readonly width: number;
  /** The caller's corner radius (the add-space palette rounds differently). */
  readonly cornerRadius: number;
  readonly children?: ReactNode;
  readonly role?: string;
  readonly ariaLabel?: string;
}

/** `palette_card` (`popover.rs:820`) — the command-palette sibling of
 * `PopoverCard`: explicit width, caller's radius, no padding. */
export function PaletteCard(props: PaletteCardProps) {
  const { width, cornerRadius, children, role, ariaLabel } = props;
  return (
    <div
      className="palette-card"
      style={{ width: `${width}px`, borderRadius: `${cornerRadius}px` }}
      role={role}
      aria-label={ariaLabel}
    >
      {children}
    </div>
  );
}

/** `menu_heading` (`popover.rs:771-795`) — small uppercase section head.
 * The tracking is real CSS `letter-spacing`; the desktop's hair-space
 * workaround is deliberately not ported (it would break copy/paste). */
export function MenuHeading(props: { children: ReactNode }) {
  return <div className="menu-heading">{props.children}</div>;
}

/** `menu_separator` (`popover.rs:799-803`) — the full-bleed hairline: the
 * negative inline margin cancels the card's 4px inset so it runs edge to
 * edge. */
export function MenuSeparator() {
  return <div className="menu-separator" role="separator" />;
}

/** `menu_section` (`popover.rs:961-970`) — a bordered trailing section; its
 * top hairline runs edge-to-edge of the card's inset (no negative margin). */
export function MenuSection(props: { children?: ReactNode }) {
  return <div className="menu-section">{props.children}</div>;
}

/**
 * `search_input_frame` (`popover.rs:946-955`) — the borderless recessed
 * frame at the top of a picker popover. Put the input inside; the frame
 * styles it (`.search-input-frame input`).
 */
export function SearchInputFrame(props: { children: ReactNode }) {
  return <div className="search-input-frame">{props.children}</div>;
}

/** `key_cap` (`popover.rs:841-852`) — one 22px cap holding arbitrary children. */
export function KeyCap(props: { children: ReactNode }) {
  return <span className="key-cap">{props.children}</span>;
}

/** `key_hint_label` (`popover.rs:855-860`) — the tiny verb after a key-cap. */
export function KeyHintLabel(props: { children: ReactNode }) {
  return <span className="key-hint-label">{props.children}</span>;
}

/** `key_hint` (`popover.rs:864-878`) — a footer legend: cap + tiny verb.
 * The cap content (an icon) is the caller's. */
export function KeyHint(props: { cap: ReactNode; label: ReactNode }) {
  return (
    <span className="key-hint">
      <KeyCap>{props.cap}</KeyCap>
      <KeyHintLabel>{props.label}</KeyHintLabel>
    </span>
  );
}

/** `key_hint_text` (`popover.rs:882-896`) — a cap holding a WORD ("tab",
 * "esc") in the monospace font. */
export function KeyHintText(props: { cap: string; label: ReactNode }) {
  return (
    <span className="key-hint">
      <KeyCap>
        <span className="key-cap-word">{props.cap}</span>
      </KeyCap>
      <KeyHintLabel>{props.label}</KeyHintLabel>
    </span>
  );
}

/** `key_hint_pair` (`popover.rs:900-926`) — a cap holding two glyphs split
 * by a hairline divider. */
export function KeyHintPair(props: { first: ReactNode; second: ReactNode; label: ReactNode }) {
  return (
    <span className="key-hint">
      <KeyCap>
        <span className="key-cap-icon">{props.first}</span>
        <span className="key-cap-divider" />
        <span className="key-cap-icon">{props.second}</span>
      </KeyCap>
      <KeyHintLabel>{props.label}</KeyHintLabel>
    </span>
  );
}

/** `kbd_hint` (`popover.rs:929-940`) — the muted accelerator chip inside
 * menu rows ("⌘1"-style). */
export function KbdHint(props: { children: ReactNode }) {
  return <span className="kbd-hint">{props.children}</span>;
}

// ---------------------------------------------------------------------------
// Dialog primitives (popover.rs:657-1076)
//
// The modal shells themselves (`modal`/`modal_glass`, popover.rs:657-705)
// moved to `components/base/dialog.tsx` as `RbDialog` (Base UI adoption,
// blueprint Phase 1). Everything below is the card's inner chrome — pure
// styled parts that ride inside RbDialog's popup.
// ---------------------------------------------------------------------------

/** `dialog_card` (popover.rs:978-990) — the centered 360px card. */
export function DialogCard(props: { children?: ReactNode }) {
  return <section className="dialog-card">{props.children}</section>;
}

/** `dialog_title` (`popover.rs:993`) — 15px semibold. */
export function DialogTitle(props: { children: ReactNode }) {
  return <h2 className="dialog-card-title">{props.children}</h2>;
}

/** `dialog_body` (`popover.rs:1002`) — 13px/19px muted copy. */
export function DialogBody(props: { children: ReactNode }) {
  return <p className="dialog-card-body">{props.children}</p>;
}

/** `dialog_field` (`popover.rs:1012-1023`) — the text-field frame; put the
 * input inside (`.dialog-field input` styles it). */
export function DialogField(props: { children: ReactNode }) {
  return <div className="dialog-field">{props.children}</div>;
}

type DialogButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { children: ReactNode };

/** `btn_ghost` (`popover.rs:1028-1046`) — quiet text fading up on hover. */
export function BtnGhost(props: DialogButtonProps) {
  const { children, className, ...rest } = props;
  return (
    <button type="button" className={`dialog-btn-ghost ${className ?? ""}`} {...rest}>
      {children}
    </button>
  );
}

/** `btn_primary` (`popover.rs:1049-1061`) — the text-colored fill. */
export function BtnPrimary(props: DialogButtonProps) {
  const { children, className, ...rest } = props;
  return (
    <button type="button" className={`dialog-btn-primary ${className ?? ""}`} {...rest}>
      {children}
    </button>
  );
}

/** `btn_danger` (`popover.rs:1064-1076`) — the muted red fill. */
export function BtnDanger(props: DialogButtonProps) {
  const { children, className, ...rest } = props;
  return (
    <button type="button" className={`dialog-btn-danger ${className ?? ""}`} {...rest}>
      {children}
    </button>
  );
}
