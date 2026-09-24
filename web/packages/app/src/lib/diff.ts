/**
 * The web client's diff store logic — resolution, phase classification,
 * scope labels, clean messages, and the per-file fold state the Changes
 * surface consumes. The hand-rolled patch parser, row model, and geometry
 * that used to live here are gone (web-pierre-adoption, ticket 04): the
 * Changes pane and the transcript's tool-diff blocks both render through
 * the Pierre diffs library (`lib/changes-diff.ts` / `lib/tool-diff.ts`
 * adapters), and the transcript's inline tool diffs diff their full
 * contents through the library's own parser.
 *
 * What remains is exactly the logic the diff store and the Changes surface
 * chrome read: `resolveDiff` (checkout-id-first matching with Windows
 * verbatim-path normalization), `diffPhase` + ticket 01's empty-state
 * classification, `upsertDiffFrame`, the scope vocabulary and labels, and
 * the fold state the library's controlled items consume.
 */

// ---------------------------------------------------------------------------
// Resolution / phases / scopes
// ---------------------------------------------------------------------------

/**
 * Resolve a per-checkout diff list to the diff that matches the given chat.
 * `checkout_id` first, then device+cwd, then cwd alone — desktop parity
 * (`crates/ui/src/changes.rs::resolve_diff`). The cwd fallback compares
 * through `normalizeCheckoutPath`: engine frames carry the canonicalized
 * cwd (Windows `std::fs::canonicalize` emits the verbatim `\\?\` form),
 * chat rows carry plain paths, so raw string equality strands a
 * checkout-id-less chat on an eternal spinner (research §2.2, live-
 * observed). The desktop never normalized because its chat rows and frames
 * share one path form; the web sees both.
 */
export function resolveDiff<T extends { readonly checkoutId: string; readonly deviceId: string; readonly cwd: string }>(
  diffs: readonly T[],
  chat: { readonly checkoutId: string | null; readonly deviceId: string; readonly cwd: string | null },
): T | null {
  if (chat.checkoutId !== null) {
    const match = diffs.find((d) => d.checkoutId === chat.checkoutId);
    if (match !== undefined) {
      return match;
    }
  }
  const cwd = chat.cwd;
  if (cwd === null) {
    return null;
  }
  const folder = normalizeCheckoutPath(cwd);
  const local = diffs.find(
    (d) => d.deviceId === chat.deviceId && normalizeCheckoutPath(d.cwd) === folder,
  );
  if (local !== undefined) {
    return local;
  }
  return diffs.find((d) => normalizeCheckoutPath(d.cwd) === folder) ?? null;
}

/**
 * Canonical form for checkout-folder matching: strip the Windows verbatim
 * prefix (`\\?\C:\…` — `std::fs::canonicalize`'s output — with the UNC form
 * `\\?\UNC\server\share` folding back to `\\server\share`) and unify
 * separators to `/`, so `\\?\C:\Users\x\repo`, `C:\Users\x\repo`, and
 * `C:/Users/x/repo` all compare equal. Purely a comparison key: the
 * original strings are never rewritten on either side.
 */
export function normalizeCheckoutPath(path: string): string {
  let normalized = path;
  if (normalized.startsWith("\\\\?\\")) {
    normalized = normalized.slice(4);
    if (normalized.startsWith("UNC\\")) {
      normalized = `\\\\${normalized.slice(4)}`;
    }
  }
  return normalized.replace(/\\/g, "/");
}

export type DiffPhase = "preparing" | "clean" | "list";

export function diffPhase(resolved: { readonly patch: string; readonly files: readonly unknown[] } | null): DiffPhase {
  if (resolved === null) {
    return "preparing";
  }
  if (resolved.patch.trim().length === 0 && resolved.files.length === 0) {
    return "clean";
  }
  return "list";
}

/**
 * Why the pane's diff is still `preparing` — the truth the eternal spinner
 * used to hide (ticket 01, web-pierre-adoption). The engine only tracks
 * checkouts for chats hosted on its OWN device (`diff_sync.rs::reconcile`
 * skips other-device chats) and never resolves a git identity for a plain
 * folder (so the chat row's `checkoutId` stays unstamped); the watch's
 * first frame enumerates every tracked checkout, so once it has arrived a
 * checkout-id-less, same-device, cwd-bearing chat is conclusively not a
 * git checkout rather than still loading. The desktop has no peer: its
 * `DiffPhase::Preparing` spinner runs open-ended on exactly these chats.
 */
export type DiffEmptyKind = "loading" | "noCheckoutFolder" | "remoteDevice" | "notAGitRepository";

/** The classification inputs — everything `ChangesBody` already resolves. */
export interface DiffEmptyInputs {
  /** The chat row's cwd; null (or blank) means the chat has no checkout folder. */
  readonly chatCwd: string | null;
  /**
   * The chat row's host device id, in the SAME scope form as `ownDeviceId`
   * (the fleet's merged rows carry scoped ids; tests may pass raw ones —
   * only the pairwise equality matters).
   */
  readonly chatDeviceId: string | null;
  /** The engine's own device id in that same scope form; null while unknown. */
  readonly ownDeviceId: string | null;
  /**
   * The chat row's checkout id — non-null means the engine resolved a git
   * identity for the folder, so an outstanding capture is still loading.
   * (Whether it is engine-raw or fleet-scoped is irrelevant here: presence
   * is the signal, the comparison lives in `resolveDiff`.)
   */
  readonly checkoutId: string | null;
  /** True once the diff watch has delivered its first frame. */
  readonly watchLoaded: boolean;
}

/**
 * Classify why no diff resolved for a chat — pure, ordering matters:
 * a remote-hosted chat can never resolve on this engine (its device's
 * engine owns the tracking), a cwd-less chat has nothing to track, an
 * undelivered watch or an unstamped-yet-tracked checkout is still loading,
 * and only a delivered watch plus a never-stamped checkout id means the
 * folder is not a git repository.
 */
export function classifyDiffEmpty(inputs: DiffEmptyInputs): DiffEmptyKind {
  if (
    inputs.chatDeviceId !== null &&
    inputs.ownDeviceId !== null &&
    inputs.chatDeviceId !== inputs.ownDeviceId
  ) {
    return "remoteDevice";
  }
  if (inputs.chatCwd === null || inputs.chatCwd.trim().length === 0) {
    return "noCheckoutFolder";
  }
  if (!inputs.watchLoaded || inputs.checkoutId !== null) {
    return "loading";
  }
  return "notAGitRepository";
}

/** The user-visible copy for each classified empty state. */
export function diffEmptyMessage(kind: DiffEmptyKind): string {
  switch (kind) {
    case "noCheckoutFolder":
      return "This chat has no checkout folder.";
    case "remoteDevice":
      return "This chat is hosted on another device — its diffs live on its own device.";
    case "notAGitRepository":
      return "This chat's checkout isn't a git repository.";
    case "loading":
      return "Preparing diff…";
  }
}

/**
 * The single-frame arm of the desktop's `apply_diff_frame`: an unknown
 * checkout appends, a known one upserts in place, and an identical frame is
 * a no-op (identity-stable, so `getSnapshot` never churns). List frames
 * replace wholesale — that branch lives in the store's watch handler.
 */
export function upsertDiffFrame<T extends { readonly checkoutId: string }>(
  list: readonly T[],
  item: T,
): readonly T[] {
  const ix = list.findIndex((row) => row.checkoutId === item.checkoutId);
  if (ix < 0) {
    return [...list, item];
  }
  if (list[ix] === item) {
    return list;
  }
  const next = list.slice();
  next[ix] = item;
  return next;
}

/**
 * The diff scope — Working tree / Branch changes / Latest turn, plus the
 * commit-pinned flavour a commit-diff tab mounts (ticket 27 reaches it via
 * `addDiffSurface(chatId, "commit", …)`; no scope row exposes it).
 * History is NOT a scope on the web: it is its own pane surface (ticket 27).
 */
export type DiffScope = "workingTree" | "branch" | "turn" | "commit";

export const DIFF_SCOPE_LABELS: Readonly<Record<DiffScope, string>> = {
  workingTree: "Working tree",
  branch: "Branch changes",
  turn: "Latest turn",
  commit: "Commit",
};

/** `DiffScope::ALL` — the scope menu's rows; commit is tab-mounted only. */
export const DIFF_SCOPE_CHIPS: readonly DiffScope[] = ["workingTree", "branch", "turn"];

/** Wire value for `GetCheckoutDiff` `mode`. */
export function scopeMode(scope: DiffScope): string {
  switch (scope) {
    case "workingTree":
      return "workingTree";
    case "branch":
      return "branch";
    case "turn":
      return "turn";
    case "commit":
      return "commit";
  }
}

export interface ScopeLabelInputs {
  readonly scope: DiffScope;
  readonly count: number;
  readonly base?: string | null;
}

export function scopeLabel({ scope, count, base }: ScopeLabelInputs): string {
  const files = count === 1 ? "file" : "files";
  switch (scope) {
    case "workingTree":
      return count === 1 ? "1 Uncommitted change" : `${count} Uncommitted changes`;
    case "branch":
      return base !== undefined && base !== null
        ? `${count} Changed ${files} vs ${base}`
        : `${count} Changed ${files}`;
    case "turn":
      return `${count} Changed ${files} this turn`;
    case "commit":
      return `${count} Changed ${files} in this commit`;
  }
}

/** Empty-state copy per scope. */
export function cleanMessage(scope: DiffScope, base: string | null): string {
  switch (scope) {
    case "workingTree":
      return "No uncommitted changes";
    case "branch":
      return base === null ? "No branch changes" : `No changes vs ${base}`;
    case "turn":
      return "No changes this turn";
    case "commit":
      return "No changes in this commit";
  }
}

/**
 * Default base for the branch scope: first branch that's not the current one,
 * else `main`/`master` if present, else first entry.
 */
export function defaultBaseRef(branches: readonly string[], current: string | null): string | null {
  const first = branches[0];
  if (first === undefined) {
    return null;
  }
  if (current !== first) {
    return first;
  }
  for (const candidate of ["main", "master"]) {
    if (branches.includes(candidate)) {
      return candidate;
    }
  }
  const other = branches.find((branch) => branch !== current);
  return other ?? first;
}

// ---------------------------------------------------------------------------
// Surface-state types
// ---------------------------------------------------------------------------

/** The diff layout the surface store persists (`diffSplit`). */
export type DiffMode = "unified" | "split";

/**
 * One file's fold state in the Changes surface store — the
 * `collapsed`/`epoch` pair the Pierre diffs library's controlled items
 * consume (`lib/changes-diff.ts`: `item.collapsed` plus the version bump
 * the epoch drives). The library renders the collapse itself; the old
 * hand-rolled tween fields died with the row model (ticket 04).
 */
export interface FileFold {
  readonly collapsed: boolean;
  /** Bumped per toggle — moves the item's version so the update lands. */
  readonly epoch: number;
}

/**
 * The diff-side draft's anchor, as the review-comment store stages it —
 * ticket 03's input for mapping staged comments and the open draft onto
 * the library's line annotations.
 */
export interface DiffDraftAnchor {
  readonly path: string;
  readonly side: "old" | "new";
  readonly line: number;
  /** The staged comment being edited — the draft row's button reads "Save". */
  readonly editingId: string | null;
}

// ---------------------------------------------------------------------------
// Parse identity
// ---------------------------------------------------------------------------

/**
 * Build the per-file patch-key fingerprint: a checksum + scope pair, folded
 * into the parse cache key. The desktop calls this `parse_key`
 * (changes.rs:1867); identical text returns the same identity.
 */
export function parseKey(checkoutId: string, checksum: string, scope: DiffScope, baseRef: string | null): string {
  const base = baseRef ?? "";
  return `${checkoutId}:${checksum}:${scope}:${base}`;
}
