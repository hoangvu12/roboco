import { Icon } from "@roboco/icons";
import type { ChangeRequestSummary, ContextUsage } from "@roboco/proto";
import { ContextUsageIndicator } from "./context-usage";
import { ChangeRequestBadge } from "./change-request-badge";

/**
 * The session footer under the composer — the desktop's `render_footer`
 * (`SESSION_FOOTER_HEIGHT` = 24, sized to hold the usage indicator and the
 * change-request badge inside equal 8px gutters).
 *
 * Workspace identity sits on the leading edge (the branch the run is stamped
 * with); occupancy and the change-request badge pin to the trailing edge. The
 * row exists whether or not it has anything to say, so the composer above it
 * never shifts.
 */
export function ComposerFooter({
  branch,
  crSummary,
  contextUsage,
}: {
  branch: string | null;
  crSummary: ChangeRequestSummary | null;
  contextUsage: ContextUsage | null;
}) {
  return (
    <div className="composer-footer">
      {branch !== null && (
        <span className="footer-chip" title={`Branch: ${branch}`}>
          <Icon name="gitBranch" size={12} />
          <span className="footer-chip-label">{branch}</span>
        </span>
      )}
      <span className="footer-spring" />
      {crSummary !== null && <ChangeRequestBadge summary={crSummary} />}
      <ContextUsageIndicator usage={contextUsage} />
    </div>
  );
}
