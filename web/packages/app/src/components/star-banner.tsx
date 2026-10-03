import { useSyncExternalStore } from "react";
import { Icon } from "@roboco/icons";
import { uiSettings } from "../state/ui-settings";

/**
 * The "Star on GitHub" banner — the desktop's `render_github_star_banner`
 * (shell.rs, upstream zeron d92d56a2), stacked above the update strip.
 *
 * Clicking the chip opens the repository AND dismisses it for good; the
 * close box dismisses alone. Dismissal persists as
 * `githubStarBannerDismissed` in the ui-settings store (the
 * `dismissedUpdateVersion` precedent's sibling flag), so the banner never
 * returns on the next launch — the same `schedule_save` persistence the
 * desktop's `dismiss_github_star_banner` writes.
 *
 * Structured as the `sidebar-notice.tsx` pattern (one store, one chip,
 * mounted as a `SidebarBody` sibling) rather than living inside
 * `update-strip.tsx`: the strip's other half is the engine's `UpdateStatus`
 * stream, which this banner has nothing to do with.
 */

/** Where "Star on GitHub" sends the user — the desktop's `GITHUB_REPO_URL`. */
const GITHUB_REPO_URL = "https://github.com/hoangvu12/roboco";

export function StarBanner() {
  const dismissed = useSyncExternalStore(
    (listener) => uiSettings.subscribe(listener),
    () => uiSettings.getSnapshot().githubStarBannerDismissed,
  );
  if (dismissed) {
    return null;
  }
  return (
    <div className="star-banner" id="github-star-banner">
      {/*
        Following the link dismisses too (the desktop's on_click opens the
        URL and flips the flag); the default action — the navigation — is
        left alone, and the immediate write lands before it can matter.
      */}
      <a
        className="star-banner-link"
        href={GITHUB_REPO_URL}
        target="_blank"
        rel="noreferrer"
        onClick={() => uiSettings.updateImmediate({ githubStarBannerDismissed: true })}
      >
        <Icon name="starBold" size={12} className="star-banner-star" />
        <span className="star-banner-label">Star on GitHub</span>
      </a>
      <button
        type="button"
        className="star-banner-dismiss"
        aria-label="Dismiss"
        title="Dismiss"
        onClick={() => uiSettings.updateImmediate({ githubStarBannerDismissed: true })}
      >
        <Icon name="close" size={10} />
      </button>
    </div>
  );
}
