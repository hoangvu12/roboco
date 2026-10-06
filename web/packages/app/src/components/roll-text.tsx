import { useEffect, useRef, useState, type ReactNode } from "react";

/**
 * `roll_text` — the web peer of `crates/ui/src/roll_text.rs` (Scritto's
 * `<scritto-text>` roll, upstream #799): when a keyed label changes, the
 * characters shared at either end hold while the changed middle rolls —
 * here as a CSS rise: the old value fades/slides up-and-out as the new one
 * rises in from below, on Scritto's ease (cubic-bezier(.22,1,.36,1), ~550ms
 * plus a little stagger). Reduced motion (and the first sight of a key)
 * renders the plain label.
 *
 * The desktop paints glyphs through a custom element; the web achieves the
 * same read with two stacked spans (out and in) keyed by the label value —
 * React's keyed remount drives the enter animation, and the leaving copy
 * animates out while absolutely positioned so the chip's width follows the
 * new label immediately (the desktop's width glide, in CSS the container
 * sizes to the incoming text at once and the outgoing copy overflows
 * invisibly).
 */

/** Scritto's default roll duration (roll_text.rs DURATION). */
const ROLL_MS = 550;

interface RollTextProps {
  /** Per-surface key: rolls happen only when THIS key's label changes. */
  readonly rollKey: string;
  readonly label: string;
  /** Reduced motion renders the plain label and skips the roll. */
  readonly reduced: boolean;
  readonly className?: string;
}

export function RollText({ rollKey, label, reduced, className }: RollTextProps): ReactNode {
  const [rolls, setRolls] = useState<Map<string, string>>(() => new Map());
  const last = useRef<Map<string, string>>(new Map());
  const [tick, setTick] = useState(0);

  useEffect(() => {
    const previous = last.current.get(rollKey);
    if (previous !== undefined && previous !== label) {
      setRolls((current) => new Map(current).set(rollKey, previous));
      // Bound the roll memory (MAX_KEYS = 256 on the desktop): clear once
      // many keys have accumulated.
      if (last.current.size > 256) {
        last.current.clear();
      }
      setTick((value) => value + 1);
    }
    last.current.set(rollKey, label);
  }, [rollKey, label]);

  const from = rolls.get(rollKey);
  const rolling = !reduced && from !== undefined && from !== label;

  return (
    <span
      className={`roll-text ${className ?? ""}`}
      style={{ "--rb-roll-ms": `${ROLL_MS}ms` } as React.CSSProperties}
      key={rolling ? `${rollKey}:${tick}` : rollKey}
    >
      {rolling ? (
        <>
          <span className="roll-text-out" aria-hidden="true">
            {from}
          </span>
          <span className="roll-text-in">{label}</span>
        </>
      ) : (
        <span className="roll-text-still">{label}</span>
      )}
    </span>
  );
}
