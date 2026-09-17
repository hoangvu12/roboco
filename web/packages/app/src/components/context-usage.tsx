import type { ContextUsage } from "@roboco/proto";

/**
 * Context occupancy — the desktop's `context_usage.rs`, read from the
 * replicated chat snapshot and never from local CLI state.
 *
 * A 16px ring: a faint full track with the used arc growing clockwise from 12
 * o'clock, and the percentage beside it. The color escalates with pressure —
 * muted below 75%, warning from 75%, danger from 90% — and an unknown window
 * reads as a faint em dash rather than a guess.
 */

/** `ContextUsage::fraction` — `None` unless both halves are present and sane. */
export function usageFraction(usage: ContextUsage | null): number | null {
  if (usage === null) {
    return null;
  }
  const { tokens, window } = usage;
  if (tokens === null || window === null || window <= 0) {
    return null;
  }
  return tokens / window;
}

const RADIUS = 6;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

export function ContextUsageIndicator({ usage }: { usage: ContextUsage | null }) {
  const fraction = usageFraction(usage);
  const tone =
    fraction === null ? "none" : fraction >= 0.9 ? "danger" : fraction >= 0.75 ? "warning" : "muted";
  const filled = Math.min(Math.max(fraction ?? 0, 0), 1);
  const label = fraction === null ? "—" : `${Math.round(fraction * 100)}%`;
  return (
    <div className="context-usage" data-tone={tone} title={`Context used: ${label}`}>
      <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
        {/* Rotated so both arcs start at 12 o'clock, like the desktop's paths. */}
        <g transform="rotate(-90 8 8)" fill="none" strokeWidth="1.8">
          <circle cx="8" cy="8" r={RADIUS} className="context-usage-track" />
          <circle
            cx="8"
            cy="8"
            r={RADIUS}
            className="context-usage-arc"
            strokeDasharray={`${CIRCUMFERENCE * filled} ${CIRCUMFERENCE}`}
            strokeLinecap="butt"
          />
        </g>
      </svg>
      <span>{label}</span>
    </div>
  );
}
