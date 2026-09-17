import type { ChangeRequestState, ChangeRequestSummary } from "@roboco/proto";

/**
 * The change-request badge — the web peer of `crates/ui/src/change_requests.rs`.
 * A pill that shows `#N` + the state word; tone by state (Open / Merged /
 * Closed). Click opens the change request's own URL. On hover, a frosted
 * tooltip card surfaces the title and the same `PR #N · State` label the
 * desktop uses.
 *
 * There is no create affordance: the desktop has no `CreateChangeRequest` RPC
 * and no button — the badge simply does not appear until a PR exists.
 *
 * Used in two places: the chat header (per-chat status) and the changes
 * surface's checkout card (the underlying CR for the displayed diff).
 */

export type BadgeTone = "open" | "merged" | "closed";

export function toneFor(state: ChangeRequestState): BadgeTone {
  switch (state) {
    case "open":
      return "open";
    case "merged":
      return "merged";
    case "closed":
      return "closed";
  }
}

export const TONE_LABEL: Readonly<Record<BadgeTone, string>> = {
  open: "Open",
  merged: "Merged",
  closed: "Closed",
};

export interface ChangeRequestBadgeProps {
  readonly summary: ChangeRequestSummary;
  readonly size?: "composer" | "sidebar";
}

export function ChangeRequestBadge({ summary, size = "sidebar" }: ChangeRequestBadgeProps) {
  const tone = toneFor(summary.state);
  const label = TONE_LABEL[tone];
  const number = `#${summary.number}`;
  const title = summary.title.replace(/[\r\n]+/g, " ");
  const tooltipId = `cr-${summary.provider}-${summary.number}`;

  return (
    <a
      className={`cr-badge cr-badge-${tone} cr-badge-${size}`}
      href={summary.url}
      target="_blank"
      rel="noreferrer noopener"
      aria-describedby={tooltipId}
    >
      <span className="cr-badge-glyph" aria-hidden>
        PR
      </span>
      <span className="cr-badge-number mono">{number}</span>
      <span className="cr-badge-state">{label}</span>
      <span className="cr-tooltip panel" role="tooltip" id={tooltipId}>
        <span className="cr-tooltip-line">
          <span className={`cr-tooltip-dot cr-tooltip-dot-${tone}`} aria-hidden />
          <span className="cr-tooltip-label">
            {summary.provider} {number} · {label}
          </span>
        </span>
        <span className="cr-tooltip-title">{title}</span>
      </span>
    </a>
  );
}
