import { useEffect, useMemo, useState } from "react";
import { Link } from "@tanstack/react-router";
import { useEngineSession } from "../state/session-provider";
import { useEngineStatus, useWatchSnapshot } from "../state/hooks";
import { ChangesStore } from "../state/changes-store";
import { ChangeRequestStore, type ChangeRequestTarget, changeRequestForChat } from "../state/change-requests-store";
import { chatPageRow } from "../lib/view";
import { DIFF_SCOPE_LABELS, cleanMessage, defaultBaseRef, scopeLabel, type DiffScope } from "../lib/diff";
import { DiffView, type DiffLayout } from "../components/diff-view";
import { ChangeRequestBadge } from "../components/change-request-badge";
import { useNow } from "../state/hooks";
import type { ChangeRequestSummary } from "@roboco/proto";

/**
 * The Changes surface for one chat — the per-checkout diff view with folding
 * (Working tree / Branch / Latest turn), plus the change-request header card.
 * Web peer of the desktop's right-pane Changes tab.
 *
 * Changes is a PANE surface, never a route: the desktop has no `/changes`
 * page, and a route here stripped the pane column off its own chrome. Scope
 * and base live in the surface's local state instead of in search params.
 *
 * The CR card is reactive: the surface subscribes a
 * `WatchCheckoutChangeRequest` per the chat's `(device, cwd, branch)` tuple
 * and derives the visible summary. When no change request exists the card is
 * absent — the desktop offers no create flow to mirror.
 */
/**
 * The right pane's Changes surface. The pane is chat-scoped chrome with no
 * URL of its own, so scope and base live in local state here rather than in
 * search params.
 */
export function ChangesSurface({ chatId }: { chatId: string }) {
  const [scope, setScope] = useState<DiffScope>("workingTree");
  const [base, setBase] = useState<string | null>(null);
  return (
    <ChangesBody
      chatId={chatId}
      scope={scope}
      requestedBase={base}
      onScopeChange={(next) => {
        setScope(next);
        if (next !== "branch") {
          setBase(null);
        }
      }}
      onBaseChange={(next) => {
        setScope("branch");
        setBase(next);
      }}
    />
  );
}

interface ChangesBodyProps {
  readonly chatId: string;
  readonly scope: DiffScope;
  readonly requestedBase: string | null;
  readonly onScopeChange: (next: DiffScope) => void;
  readonly onBaseChange: (next: string) => void;
}

function ChangesBody({ chatId, scope, requestedBase, onScopeChange, onBaseChange }: ChangesBodyProps) {
  const session = useEngineSession();
  const status = useEngineStatus(session);
  const snapshot = useWatchSnapshot(session);
  const now = useNow(10_000);

  const deviceId = status?.state === "connected" ? status.info.deviceId : null;
  const chat = snapshot === null
    ? null
    : chatPageRow(chatId, snapshot.chats.rows, snapshot.spaces.rows, snapshot.statuses.rows, now)?.chat ?? null;
  const branch = chat?.branch ?? null;
  const checkoutId = chat?.checkoutId ?? null;
  const cwd = chat?.cwd ?? null;

  const [store, setStore] = useState<ChangesStore | null>(null);
  useEffect(() => {
    if (session === null || deviceId === null) {
      setStore(null);
      return;
    }
    const created = new ChangesStore(session.client, {
      checkoutId,
      deviceId,
      cwd,
      chatId,
    });
    setStore(created);
    return () => {
      created.dispose();
      setStore((current) => (current === created ? null : current));
    };
  }, [session, deviceId, cwd, checkoutId, chatId]);

  const crStore = useMemo(() => {
    if (session === null || deviceId === null) {
      return null;
    }
    return new ChangeRequestStore(session.client);
  }, [session, deviceId]);

  useEffect(() => () => {
    crStore?.dispose();
  }, [crStore]);

  useEffect(() => {
    if (crStore === null || branch === null || cwd === null || deviceId === null) {
      return;
    }
    const trimmed = branch.trim();
    if (trimmed.length === 0) {
      crStore.setTargets([]);
      return;
    }
    const targets: ChangeRequestTarget[] = [
      { deviceId, cwd, branch: trimmed, checkoutId },
    ];
    crStore.setTargets(targets);
  }, [crStore, branch, cwd, deviceId, checkoutId]);

  const changes = store?.getSnapshot() ?? null;
  const crSnap = crStore?.getSnapshot() ?? null;

  useEffect(() => {
    if (store === null) {
      return;
    }
    const base = scope === "branch" ? requestedBase : null;
    store.setScope(scope, base);
  }, [store, scope, requestedBase]);

  const resolvedDiff = useMemo(() => {
    if (changes === null) {
      return null;
    }
    if (scope === "workingTree") {
      return changes.resolvedForChat;
    }
    return changes.scoped?.diff ?? null;
  }, [changes, scope]);

  const phase = changes?.phase ?? "preparing";
  const watchLoaded = changes?.watchLoaded ?? false;
  const error = changes?.error ?? null;

  const crSummary: ChangeRequestSummary | null = useMemo(() => {
    if (crSnap === null || deviceId === null || cwd === null || branch === null) {
      return null;
    }
    const target: ChangeRequestTarget = { deviceId, cwd, branch, checkoutId };
    const visible = changeRequestForChat(crSnap.snapshots, target);
    if (visible !== null) {
      return visible;
    }
    // No fresh CR — if the device has been marked unsupported, return null
    // so the page surfaces that state cleanly without an empty card.
    return null;
  }, [crSnap, deviceId, cwd, branch, checkoutId]);

  const [layout, setLayout] = useState<DiffLayout>("unified");
  const [wrap, setWrap] = useState(false);
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(() => new Set());

  const toggleCollapse = (path: string): void => {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(path)) {
        next.delete(path);
      } else {
        next.add(path);
      }
      return next;
    });
  };

  const fileCount = resolvedDiff?.files.length ?? 0;
  const additions = resolvedDiff?.additions ?? 0;
  const deletions = resolvedDiff?.deletions ?? 0;
  const baseForLabel = scope === "branch" ? changes?.scoped?.baseRef ?? requestedBase : null;

  if (snapshot === null) {
    return (
      <div className="changes-page">
        <p className="changes-empty">Pair an engine to view its changes.</p>
      </div>
    );
  }
  if (!snapshot.chats.loaded) {
    return (
      <div className="changes-page">
        <p className="changes-empty">Loading…</p>
      </div>
    );
  }
  if (chat === null) {
    return (
      <div className="changes-page">
        <p className="changes-empty">
          That chat isn't on this engine.{" "}
          <Link to="/chat/$chatId" params={{ chatId }}>
            Back to chat
          </Link>
        </p>
      </div>
    );
  }

  const crUnsupported = crSnap !== null && !crSnap.supported;

  return (
    <div className="changes-page changes-page-surface">
      <header className="changes-header">
        <nav className="changes-scope" aria-label="Diff scope">
          {(Object.keys(DIFF_SCOPE_LABELS) as DiffScope[]).map((option) => (
            <button
              key={option}
              type="button"
              className={`changes-scope-chip ${scope === option ? "changes-scope-chip-active" : ""}`}
              onClick={() => onScopeChange(option)}
              aria-pressed={scope === option}
            >
              {DIFF_SCOPE_LABELS[option]}
            </button>
          ))}
        </nav>
      </header>

      {scope === "branch" ? (
        <BasePicker
          branches={changes?.branches ?? []}
          current={requestedBase ?? changes?.scoped?.baseRef ?? null}
          onChange={onBaseChange}
        />
      ) : null}

      <div className="changes-banner">
        <span className="changes-banner-label">{scopeLabel({ scope, count: fileCount, base: baseForLabel })}</span>
        <span className="changes-banner-counts">
          {resolvedDiff !== null && (additions > 0 || deletions > 0) ? (
            <span className="mono">
              {additions > 0 ? `+${additions}` : ""}
              {additions > 0 && deletions > 0 ? " " : ""}
              {deletions > 0 ? `-${deletions}` : ""}
            </span>
          ) : null}
          {resolvedDiff !== null && resolvedDiff.truncated ? (
            <span className="changes-banner-warn">Partial snapshot</span>
          ) : null}
        </span>
        <div className="changes-banner-tools">
          <button
            type="button"
            className={`btn btn-ghost changes-tool ${layout === "unified" ? "changes-tool-active" : ""}`}
            onClick={() => setLayout("unified")}
            aria-pressed={layout === "unified"}
          >
            Unified
          </button>
          <button
            type="button"
            className={`btn btn-ghost changes-tool ${layout === "split" ? "changes-tool-active" : ""}`}
            onClick={() => setLayout("split")}
            aria-pressed={layout === "split"}
          >
            Split
          </button>
          <button
            type="button"
            className={`btn btn-ghost changes-tool ${wrap ? "changes-tool-active" : ""}`}
            onClick={() => setWrap((current) => !current)}
            aria-pressed={wrap}
            title="Wrap long lines"
          >
            Wrap
          </button>
        </div>
      </div>

      {crUnsupported ? (
        <div className="changes-cr-card changes-cr-card-disabled" role="status">
          <span className="changes-cr-card-label">Change requests unavailable on this engine</span>
        </div>
      ) : crSummary !== null ? (
        <div className="changes-cr-card" role="status">
          <span className="changes-cr-card-label">Open change request</span>
          <ChangeRequestBadge summary={crSummary} />
          <span className="changes-cr-card-base mono">
            {crSummary.baseRef} ← {crSummary.headRef}
          </span>
        </div>
      ) : null}
      {/*
        No PR yet → no card. The desktop's badge appears only once a change
        request exists; it has no create affordance to mirror.
      */}

      <div className="changes-body">
        {error !== null ? (
          <div className="changes-banner changes-banner-error" role="alert">
            <span className="changes-banner-label">{error}</span>
            <button
              type="button"
              className="btn btn-ghost"
              onClick={() => store?.resubscribe()}
            >
              Retry
            </button>
          </div>
        ) : null}
        {phase === "preparing" && !watchLoaded ? (
          <p className="changes-empty">Preparing diff…</p>
        ) : phase === "clean" ? (
          <p className="changes-empty">{cleanMessage(scope, baseForLabel)}</p>
        ) : resolvedDiff !== null ? (
          <DiffView
            diff={resolvedDiff}
            layout={layout}
            wrap={wrap}
            collapsed={collapsed}
            onToggleCollapse={toggleCollapse}
          />
        ) : (
          <p className="changes-empty">No diff available.</p>
        )}
      </div>
    </div>
  );
}

function BasePicker({
  branches,
  current,
  onChange,
}: {
  branches: readonly string[];
  current: string | null;
  onChange: (next: string) => void;
}) {
  const defaultBase = useMemo(() => defaultBaseRef(branches, current), [branches, current]);
  const value = current ?? defaultBase ?? "";
  if (branches.length === 0) {
    return (
      <div className="changes-base">
        <span className="changes-base-label">Base ref</span>
        <span className="changes-base-loading">Loading branches…</span>
      </div>
    );
  }
  return (
    <div className="changes-base">
      <label className="changes-base-label" htmlFor="changes-base-picker">
        Compare against
      </label>
      <select
        id="changes-base-picker"
        className="input changes-base-picker"
        value={value}
        onChange={(event) => onChange(event.target.value)}
      >
        {branches.map((branch) => (
          <option key={branch} value={branch}>
            {branch}
          </option>
        ))}
      </select>
    </div>
  );
}