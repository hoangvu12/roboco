import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { Link, useNavigate, useParams } from "@tanstack/react-router";
import { Icon, harnessBrandIcon } from "@roboco/icons";
import { methods } from "@roboco/engine-client";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { useTitlebar } from "../state/chrome";
import { emitShortcut } from "../state/shortcuts";
import { chatPageRow, type ChatRow } from "../lib/view";
import { JumpPill, StatusStrip, TranscriptView, type JumpButtonState } from "../components/transcript";
import { Composer } from "../components/composer";
import { QueuePanel } from "../components/queue-panel";
import { ComposerFooter } from "../components/composer-footer";
import { bottomClearance } from "../state/layout";
import { chatRoute } from "../router";
import { ChangeRequestStore, type ChangeRequestTarget, changeRequestForChat } from "../state/change-requests-store";
import { QueueStore } from "../state/queue-store";
import { QueueStoreProvider } from "../state/queue-store-context";
import { sidebarNotice } from "../state/notice";
import { markChatSeen } from "../lib/chat-actions";
import { echoStore } from "../state/transcript-store";
import type { QueuedMessage } from "@roboco/proto";
import type { ChangeRequestSummary, ContextUsage } from "@roboco/proto";

/**
 * One chat's main panel: title, live status, the streaming transcript
 * (`../components/transcript.tsx`), the on-demand preview pane (right-dock
 * on wide viewports, stacked on phones), the queue panel above the composer
 * (ticket 09), the composer (docks at phone widths), and the terminal dock
 * (Ctrl+J). Archived chats stay open and say so in the header.
 *
 * Queue store lifecycle mirrors the change-request store: one QueueStore
 * per open chat, memoized on chatId, disposed on unmount or chat switch.
 * The store owns the per-chat `WatchQueue` subscription and the local
 * edit-lease state; the page forwards edits from the panel into the
 * composer and the lease releases back into the store.
 */
export function ChatPage() {
  const { chatId } = useParams({ from: chatRoute.id });
  const session = useEngineSession();
  const snapshot = useWatchSnapshot(session);
  const status = session === null ? null : session.client.status;
  const now = useNow(10_000);
  const navigate = useNavigate();

  // Occupancy arrives on the transcript's watch; the composer footer draws it.
  const [contextUsage, setContextUsage] = useState<ContextUsage | null>(null);
  useEffect(() => {
    setContextUsage(null);
  }, [chatId]);

  // Lazily fetch the harness catalog once per chat page open so the
  // composer chips aren't blank behind a stale "Loading." pill.
  useEffect(() => {
    if (session === null) {
      return;
    }
    void session.catalog.loadHarnesses();
  }, [session, chatId]);

  const deviceId = status?.state === "connected" ? status.info.deviceId : null;
  const chat = snapshot === null ? null : snapshot.chats.rows.find((row) => row.id === chatId) ?? null;
  const branch = chat?.branch ?? null;
  const checkoutId = chat?.checkoutId ?? null;
  const cwd = chat?.cwd ?? null;

  const crStore = useMemo(() => {
    if (session === null) {
      return null;
    }
    return new ChangeRequestStore(session.client);
  }, [session]);

  useEffect(() => () => {
    crStore?.dispose();
  }, [crStore]);

  useEffect(() => {
    if (crStore === null || deviceId === null || cwd === null || branch === null) {
      return;
    }
    const trimmed = branch.trim();
    if (trimmed.length === 0) {
      crStore.setTargets([]);
      return;
    }
    const targets: ChangeRequestTarget[] = [{ deviceId, cwd, branch: trimmed, checkoutId }];
    crStore.setTargets(targets);
  }, [crStore, deviceId, cwd, branch, checkoutId]);

  const crSummary: ChangeRequestSummary | null = useMemo(() => {
    if (crStore === null || deviceId === null || cwd === null || branch === null) {
      return null;
    }
    const snap = crStore.getSnapshot();
    return changeRequestForChat(snap.snapshots, { deviceId, cwd, branch: branch.trim(), checkoutId });
  }, [crStore, deviceId, cwd, branch, checkoutId]);

  // Queue store: one per chat. Disposed on chat switch so a fresh
  // subscription lands immediately.
  const queueStore = useMemo(() => {
    if (session === null || deviceId === null) {
      return null;
    }
    return new QueueStore(session.client, chatId, { editorDeviceId: deviceId });
  }, [session, chatId, deviceId]);

  useEffect(() => () => {
    queueStore?.dispose();
  }, [queueStore]);

  // Edit state: the chat page owns which queued row (if any) is feeding
  // text into the composer. Clearing it cancels the lease (the user
  // backed out without saving); committing finishes the lease with the
  // current composer text in place.
  const [editingRow, setEditingRow] = useState<{ id: string; text: string } | null>(null);

  // If the queue store reports the row disappeared while we were editing
  // (another device removed/sent it), drop the edit state so the composer
  // doesn't carry stale text.
  useEffect(() => {
    if (editingRow === null || queueStore === null) {
      return;
    }
    if (queueStore.rowById(editingRow.id) === null) {
      setEditingRow(null);
    }
  }, [editingRow, queueStore, queueStore?.getSnapshot().generation]);

  const onEditRow = useCallback((row: QueuedMessage) => {
    setEditingRow({ id: row.id, text: row.text });
  }, []);

  const onEditFinish = useCallback(
    (outcome: { action: "commit" | "cancel" | "releaseUnchanged"; text: string }) => {
      if (queueStore === null || editingRow === null) {
        return;
      }
      const store = queueStore;
      const rowId = editingRow.id;
      setEditingRow(null);
      void (async () => {
        try {
          const lease = store.getSnapshot().editLease;
          if (lease === null || lease.messageId !== rowId) {
            return;
          }
          if (outcome.action === "commit") {
            await store.finishEdit("commit", { text: outcome.text });
          } else {
            await store.finishEdit(outcome.action);
          }
        } catch (error) {
          sidebarNotice.set(`Could not finish edit: ${error instanceof Error ? error.message : String(error)}`);
        }
      })();
    },
    [queueStore, editingRow],
  );

  const onEditCancel = useCallback(() => {
    if (queueStore === null || editingRow === null) {
      setEditingRow(null);
      return;
    }
    const store = queueStore;
    const rowId = editingRow.id;
    setEditingRow(null);
    void (async () => {
      try {
        const lease = store.getSnapshot().editLease;
        if (lease !== null && lease.messageId === rowId) {
          await store.finishEdit("cancel");
        }
      } catch (error) {
        sidebarNotice.set(`Could not cancel edit: ${error instanceof Error ? error.message : String(error)}`);
      }
    })();
  }, [queueStore, editingRow]);

  const row =
    snapshot === null || !snapshot.chats.loaded
      ? undefined
      : chatPageRow(chatId, snapshot.chats.rows, snapshot.spaces.rows, snapshot.statuses.rows, now, snapshot.devices.rows);

  // ── Bottom chrome stack bookkeeping ─────────────────────────────────────
  // `bottom_stack` measured live (the desktop's paint-time canvas): the
  // height feeds BOTH the transcript's bottom fade band through a custom
  // property on the column AND the last row's clearance pad through
  // `state/layout.ts`'s store, so the fade and the pad track the composer's
  // compact↔expanded flip together.
  const chatColumnRef = useRef<HTMLDivElement | null>(null);
  const bottomStackRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const stack = bottomStackRef.current;
    const column = chatColumnRef.current;
    if (stack === null || column === null || typeof ResizeObserver === "undefined") {
      return;
    }
    const publish = (): void => {
      const height = stack.getBoundingClientRect().height;
      column.style.setProperty("--rb-bottom-stack", `${height}px`);
      bottomClearance.set(height);
    };
    const observer = new ResizeObserver(publish);
    observer.observe(stack);
    // `set_bottom_clearance`'s discipline (transcript.rs:2940): publish once
    // at attach too, not only on change.
    publish();
    return () => observer.disconnect();
    // `row` gates the main return: the first render(s) take the loading
    // early-return, where the refs are null and the effect above bailed — so
    // the observer must re-arm once the row lands and the tree with the refs
    // actually mounts.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chatId, row?.chat.id]);

  // The jump pill's state, published by the transcript surface. The pill
  // itself renders over the composer (see `JumpPillAnchor`).
  const [jumpState, setJumpState] = useState<JumpButtonState | null>(null);
  const onJumpChange = useCallback((state: JumpButtonState) => {
    setJumpState((current) =>
      current?.shown === state.shown && state.shown === false ? current : state,
    );
  }, []);

  // `composer.is_sending()`: a send is still awaiting its confirmation. The
  // pending sends live on the app-wide echo store, so the strip sees them
  // even though the composer owns the sends themselves.
  const sending = useSyncExternalStore(
    useCallback((listener: () => void) => echoStore.subscribe(listener), []),
    useCallback(() => echoStore.forChat(chatId).length > 0, [chatId]),
  );

  // The session row's `started_at` — the working trailer's timer base
  // (`session_for(chat_id).started_at` on the desktop).
  const turnStartedAt = useMemo(() => {
    const started = snapshot?.statuses.rows.find((row) => row.chatId === chatId)?.startedAt ?? null;
    if (started === null) {
      return null;
    }
    const parsed = Date.parse(started);
    return Number.isFinite(parsed) ? parsed : null;
  }, [snapshot, chatId]);

  // Opening a chat IS reading it (`mark_chat_seen`): the local stamp lands
  // first and stands whatever the mutation does, so a dropped `Mutate` never
  // makes a chat the user plainly looked at flash unread again.
  useEffect(() => {
    if (session === null) {
      return;
    }
    markChatSeen(session.client, chatId);
  }, [session, chatId]);

  /**
   * The working trailer's failed-send retry — the desktop's `retry_send`
   * (transcript.rs:5190): skip when the engine is not connected, restart the
   * grace clocks (same message ids, so the overlay returns to Sending), and
   * re-deliver through the engine's `RETRY_DELIVERY` — it re-issues the dead
   * durable commands with their original message ids, and the host's
   * user-entry pre-write dedupes by id, so no bubble doubles.
   */
  const onRetryDelivery = useCallback(() => {
    if (session === null || session.client.state !== "connected") {
      return;
    }
    echoStore.restartGrace(chatId, Date.now());
    void session.client
      .call(methods.RETRY_DELIVERY, { chatId })
      .catch((error: unknown) => {
        sidebarNotice.set(
          `Could not retry delivery: ${error instanceof Error ? error.message : String(error)}`,
        );
      });
  }, [session, chatId]);

  // The titlebar is the shell's; the route fills its identity and the `+`'s
  // handler. The right pane, its toggle, its surface tabs and its expand
  // control are all shell chrome — see `state/chrome.ts` for why they must
  // not travel through here. `onNewSession` is null when the chat is not in
  // the engine's list: `titlebar_plus_alpha` requires a SELECTED chat, and a
  // missing row selects nothing. Note `pane` is deliberately NOT a dep: this
  // effect clears the store on every dep change, and a toggle rebuilding the
  // chrome is what used to tear the pane column down mid-animation.
  useTitlebar(
    () => ({
      identity: row === undefined ? null : <ChatIdentity row={row} />,
      onNewSession: row === undefined ? null : () => emitShortcut("new-chat"),
    }),
    [chatId, row?.chat.id, row?.chat.title, row?.folder, row?.harness],
  );

  if (snapshot === null || !snapshot.chats.loaded) {
    return <div className="chat-page" />;
  }
  if (row === undefined) {
    return (
      <div className="empty-state">
        <p>That chat is not in this engine's list.</p>
        <Link to="/" className="btn btn-ghost">
          Back to chats
        </Link>
      </div>
    );
  }
  return (
    <div className="chat-page">
      {/*
        The conversation column: the transcript, then the bottom chrome stack
        (`render_main`'s flex-none bottom section): the reserved status strip,
        the queue panel, the composer — with the jump pill floating over the
        composer — and the session footer. Its sibling — the right pane,
        carrying whichever surface its tabs select — is a SHELL column mounted
        by `AppShell`, as on the desktop; this page only names the chat that
        owns it.
      */}
      <div className="chat-column" ref={chatColumnRef}>
        <div className="chat-body">
          {session === null ? (
            <div className="empty-state">
              <p>No engine connected.</p>
            </div>
          ) : (
            <TranscriptView
              client={session.client}
              docId={chatId}
              deviceId={deviceId}
              onContextUsage={setContextUsage}
              onRetryDelivery={onRetryDelivery}
              onJumpChange={onJumpChange}
              indicator={row.status}
              turnStartedAt={turnStartedAt}
            />
          )}
        </div>
        {/*
          The bottom chrome stack. The ResizeObserver measures its height into
          `--rb-bottom-stack` on the column, which the transcript's bottom
          fade band reads — the web peer of the desktop's paint-time canvas
          that measures `bottom_stack` for the EdgeFade inset.
        */}
        <div className="bottom-stack" ref={bottomStackRef}>
          <StatusStrip status={row.status} sending={sending} />
          {queueStore !== null && deviceId !== null ? (
            <QueueStoreProvider value={queueStore}>
              <QueuePanel
                editorDeviceId={deviceId}
                onEditRow={onEditRow}
                editingRowId={editingRow?.id ?? null}
              />
              {session !== null && (
                <div className="persistent-composer">
                  <Composer
                    session={session}
                    chat={row.chat}
                    catalog={session.catalog}
                    editingMessage={editingRow}
                    onEditFinish={onEditFinish}
                  />
                  <JumpPillAnchor state={jumpState} />
                </div>
              )}
            </QueueStoreProvider>
          ) : (
            session !== null && (
              <div className="persistent-composer">
                <Composer session={session} chat={row.chat} catalog={session.catalog} />
                <JumpPillAnchor state={jumpState} />
              </div>
            )
          )}
          <ComposerFooter chat={row.chat} crSummary={crSummary} contextUsage={contextUsage} />
          {editingRow !== null && (
            <div className="chat-edit-toolbar">
              <button type="button" className="btn btn-ghost" onClick={onEditCancel}>
                Cancel edit
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * `render_jump_to_bottom`'s positioner: the pill floats 36px ABOVE the
 * composer, horizontally centered across the composer's own width less its
 * 10px right inset. It paints outside the transcript's fade, over whatever
 * sits above the composer.
 */
function JumpPillAnchor({ state }: { state: JumpButtonState | null }) {
  if (state === null || !state.shown) {
    return null;
  }
  return (
    <div className="jump-pill-anchor">
      <JumpPill onClick={state.jump} />
    </div>
  );
}

/**
 * The titlebar's identity group — the desktop's (`tabs.rs:318-350`): the
 * harness's 14px brand mark, the 12px/500 title at `text @ 85%`, and the
 * 12px `folder @ device` tag at `text_muted @ 50%`. Nothing else — no badge,
 * no archived marker; the group's own gap (6px) and truncation live in the
 * stylesheet.
 */
function ChatIdentity({ row }: { row: ChatRow }) {
  const brand = row.harness === null ? null : harnessBrandIcon(row.harness);
  return (
    <>
      {brand !== null && (
        <Icon
          name={brand.name}
          size={14}
          className="identity-brand"
          style={brand.tint === null ? undefined : { color: brand.tint }}
        />
      )}
      <span className="identity-title">{row.chat.title ?? "New session"}</span>
      <span className="identity-folder">{row.folder}</span>
    </>
  );
}