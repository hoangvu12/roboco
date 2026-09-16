import type { ChangeRequestState, ChangeRequestSummary } from "@roboco/proto";

/**
 * Pure helpers for change-request display: tone mapping, badge model, and
 * the provider's create-page URL when the engine does not expose a wire
 * `CreateChangeRequest` RPC (today's case — the web v1 fallback opens the
 * provider's compare/merge-request URL with the head ref pre-filled).
 */

export type BadgeTone = "open" | "merged" | "closed";

export interface BadgeModel {
  readonly number: string;
  readonly stateLabel: string;
  readonly title: string;
  readonly tone: BadgeTone;
}

const STATE_LABEL: Readonly<Record<ChangeRequestState, string>> = {
  open: "Open",
  merged: "Merged",
  closed: "Closed",
};

export function badgeModel(summary: ChangeRequestSummary): BadgeModel {
  return {
    number: `#${summary.number}`,
    stateLabel: STATE_LABEL[summary.state],
    title: summary.title.replace(/[\r\n]+/g, " "),
    tone: toneFor(summary.state),
  };
}

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

const PROVIDER_KEYS: Readonly<Record<string, string>> = {
  github: "github",
  gitlab: "gitlab",
  bitbucket: "bitbucket",
  azuredevops: "azuredevops",
  codeberg: "codeberg",
};

function providerKey(provider: string): string {
  return PROVIDER_KEYS[provider.toLowerCase()] ?? provider.toLowerCase();
}

function repoPath(cwd: string): string {
  return cwd.replace(/\\/g, "/").replace(/\.git$/, "");
}

/**
 * Best-effort provider URL that opens the change-request create page with
 * the head ref pre-filled. `null` when we can't guess one (e.g. unknown
 * provider or empty base/head refs).
 */
export function changeRequestCreateUrl(provider: string, baseRef: string, headRef: string, cwd: string): string | null {
  const trimmedBase = baseRef.trim();
  const trimmedHead = headRef.trim();
  if (trimmedBase.length === 0 || trimmedHead.length === 0) {
    return null;
  }
  const key = providerKey(provider);
  const path = repoPath(cwd);
  if (path.length === 0) {
    return null;
  }
  switch (key) {
    case "github":
      return `https://github.com/${path}/compare/${encodeURIComponent(trimmedBase)}...${encodeURIComponent(trimmedHead)}?expand=1`;
    case "gitlab":
      return `https://gitlab.com/${path}/-/merge_requests/new?merge_request[source_branch]=${encodeURIComponent(trimmedHead)}&merge_request[target_branch]=${encodeURIComponent(trimmedBase)}`;
    case "bitbucket":
      return `https://bitbucket.org/${path}/pull-requests/new?source=${encodeURIComponent(trimmedHead)}&dest=${encodeURIComponent(trimmedBase)}`;
    case "azuredevops":
      return `https://dev.azure.com/${path}/pullrequestcreate?sourceRef=${encodeURIComponent(trimmedHead)}&targetRef=${encodeURIComponent(trimmedBase)}`;
    case "codeberg":
      return `https://codeberg.org/${path}/compare/${encodeURIComponent(trimmedBase)}...${encodeURIComponent(trimmedHead)}`;
    default:
      return null;
  }
}

/**
 * The chat-row badge copy — when no PR has been observed yet for this
 * chat, return `null`; otherwise return the badge model the chat header
 * renders. Kept here so the chat header and the changes surface share
 * the same derivation.
 */
export function changeRequestForBadge(summary: ChangeRequestSummary | null): BadgeModel | null {
  return summary === null ? null : badgeModel(summary);
}