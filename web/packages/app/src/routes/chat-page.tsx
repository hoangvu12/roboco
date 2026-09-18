import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { Link, useNavigate, useParams } from "@tanstack/react-router";
import { Icon, harnessBrandIcon } from "@roboco/icons";
import { methods } from "@roboco/engine-client";
import { MESSAGE_QUEUE_ACTIONS_V1 } from "@roboco/proto";
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
import { availableQueuePrimaryAction } from "../lib/queue-row-logic";
import { ATTACHMENT_ONLY_TEXT, uploadAttachments, type StagedAttachment } from "../lib/attachments";
import { echoStore, TranscriptStore } from "../state/transcript-store";
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

  // The chat's ONE transcript store: the transcript view and the composer's
  // question wizard both read it, so an open chat carries a single
  // `WatchDocMessages` stream. Disposed on chat switch/unmount like the
  // queue store below.
  const transcriptStore = useMemo(() => {
    if (session === null) {
      return null;
    }
    return new TranscriptStore(session.client, chatId);
  }, [session, chatId]);

  useEffect(() => () => {
    transcriptStore?.dispose();
  }, [transcriptStore]);

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
  // current composer text in place. `editFinishing` is the desktop's
  // `queue_edit_finishing` — true while the lease-closing RPC is in flight
  // (the row shows "Saving…" and its Save/Cancel stand down).
  const [editingRow, setEditingRow] = useState<{
    id: string;
    text: string;
    attachments: readonly string[];
  } | null>(null);
  const [editFinishing, setEditFinishing] = useState(false);

  // The composer's `commit_queue_edit` handle (queue.rs:1430) — assigned by
  // the Composer while a queued-row edit is open, so the queue row's inline
  // Save and the composer's submit share the one commit path.
  const editCommitRef = useRef<(() => void) | null>(null);

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

  // ── The 20s edit-lease heartbeat (`start_queue_edit_renewal`,
  // queue.rs:1584-1633) ── renewed for as long as the edit is open; a
  // "lost"/"missing" outcome clears the local edit exactly like the
  // desktop's expiry path (the row then carries a ReviewRequired gate).
  // A transient RPC failure is tolerated — the 60s lease fails closed on
  // the host if every attempt misses.
  useEffect(() => {
    if (editingRow === null || queueStore === null) {
      return;
    }
    const timer = setInterval(() => {
      void queueStore
        .renewEdit()
        .then((result) => {
          if (result.kind === "lost" || result.kind === "missing") {
            setEditingRow(null);
            sidebarNotice.set("Edit protection expired; review this message before sending");
          }
        })
        .catch(() => {});
    }, 20_000);
    return () => clearInterval(timer);
  }, [editingRow, queueStore]);

  const onEditRow = useCallback((row: QueuedMessage) => {
    setEditingRow({ id: row.id, text: row.text, attachments: row.attachments ?? [] });
  }, []);

  const onEditFinish = useCallback(
    (outcome: {
      action: "commit" | "cancel" | "discard" | "releaseUnchanged";
      text: string;
      staged?: readonly StagedAttachment[];
    }) => {
      if (queueStore === null || editingRow === null || session === null) {
        return;
      }
      const store = queueStore;
      const rowId = editingRow.id;
      setEditFinishing(true);
      void (async () => {
        // Non-terminal outcomes keep the edit open with the user's text in
        // the editor (`finish_queue_edit`'s failure arms, queue.rs:1550-1575).
        let keepRow = false;
        try {
          const lease = store.getSnapshot().editLease;
          if (lease === null || lease.messageId !== rowId) {
            return;
          }
          if (outcome.action === "commit") {
            // `finish_queue_edit("commit")`: the staged set uploads first
            // (queue.rs:1522-1539) — the row's own attachments were staged
            // into the composer at edit start, newly added ones are new
            // uploads — then the commit carries text + paths.
            const staged = outcome.staged ?? [];
            const uploaded =
              staged.length > 0
                ? await uploadAttachments(session.client, staged, null)
                : ([] as readonly { path: string }[]);
            const body =
              outcome.text.trim().length > 0 ? outcome.text : ATTACHMENT_ONLY_TEXT;
            const result = await store.finishEdit("commit", {
              text: body,
              attachments: uploaded.map((entry) => entry.path),
            });
            if (result.kind === "conflict") {
              sidebarNotice.set("This message changed on another device; your edit was kept locally");
              keepRow = true;
            } else if (result.kind === "missing") {
              sidebarNotice.set("The queued message was removed; your edit was kept locally");
              keepRow = true;
            } else if (result.kind === "lost") {
              sidebarNotice.set("The edit lease changed; your text is still in the editor");
              keepRow = true;
            }
          } else {
            const result = await store.finishEdit(outcome.action);
            if (result.kind === "conflict" || result.kind === "missing" || result.kind === "lost") {
              sidebarNotice.set("The edit lease changed; your text is still in the editor");
              keepRow = true;
            }
          }
        } catch (error) {
          sidebarNotice.set("Couldn't reach the chat host; your edit is still in the editor");
          keepRow = true;
        } finally {
          setEditFinishing(false);
          if (!keepRow) {
            setEditingRow(null);
          }
        }
      })();
    },
    [queueStore, editingRow, session],
  );

  const onEditCancel = useCallback(() => {
    if (queueStore === null || editingRow === null) {
      return;
    }
    const store = queueStore;
    const rowId = editingRow.id;
    setEditingRow(null);
    setEditFinishing(true);
    void (async () => {
      try {
        const lease = store.getSnapshot().editLease;
        if (lease !== null && lease.messageId === rowId) {
          await store.finishEdit("cancel");
        }
      } catch (error) {
        sidebarNotice.set(`Could not cancel edit: ${error instanceof Error ? error.message : String(error)}`);
      } finally {
        setEditFinishing(false);
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
  // compact↔expanded flip together. The same observer also feeds the
  // composer the conversation-column WIDTH (clamped to 768 inside it) —
  // `set_available_width`'s stable reflow feed.
  const chatColumnRef = useRef<HTMLDivElement | null>(null);
  const bottomStackRef = useRef<HTMLDivElement | null>(null);
  const [columnWidth, setColumnWidth] = useState<number | null>(null);
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
    const observer = new ResizeObserver(() => {
      publish();
      setColumnWidth(column.getBoundingClientRect().width);
    });
    observer.observe(stack);
    observer.observe(column);
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

  // Mod+Enter on an empty composer activates the most recently queued row
  // (`activate_latest_queued`, queue.rs:1218-1244): Send now, interrupting
  // the current response. An edit/review gate or a host without queue
  // actions makes it a no-op. The web's engine-capability check stands in
  // for the desktop's host registry until ticket 31's fleet (ticket 16
  // defers the host routing itself).
  const hostSupportsActions =
    (session?.client.engineInfo?.capabilities ?? []).includes(MESSAGE_QUEUE_ACTIONS_V1);
  const activateLatestQueued = useCallback(() => {
    if (queueStore === null || editingRow !== null) {
      return;
    }
    const latest = queueStore.getSnapshot().rows.at(-1) ?? null;
    if (latest === null || !availableQueuePrimaryAction(latest.deliveryGate != null, hostSupportsActions)) {
      return;
    }
    void queueStore
      .sendNow(latest.id)
      .then((sent) => {
        if (!sent) {
          sidebarNotice.set("Couldn't send that message");
        }
      })
      .catch((error: unknown) => {
        sidebarNotice.set(`Couldn't send that message: ${error instanceof Error ? error.message : String(error)}`);
      });
  }, [queueStore, editingRow, hostSupportsActions]);

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
              store={transcriptStore}
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

          The composer owns its centred 768px COLUMN (composer.rs:7347-7355):
          the queue tray and the session footer are its children (tucked
          behind / slotted under the pill), not siblings of it. The queue
          panel's element is handed in as a slot so the QueueStore context
          stays the chat page's.
        */}
        <div className="bottom-stack" ref={bottomStackRef}>
          <StatusStrip status={row.status} sending={sending} />
          {session !== null && (
            <div className="persistent-composer">
              <Composer
                session={session}
                chat={row.chat}
                catalog={session.catalog}
                transcript={transcriptStore}
                availableWidth={columnWidth}
                editingMessage={editingRow}
                onEditFinish={onEditFinish}
                onEditCancel={onEditCancel}
                editCommitRef={editCommitRef}
                activateLatestQueued={activateLatestQueued}
                queueSlot={
                  queueStore !== null && deviceId !== null ? (
                    <QueueStoreProvider value={queueStore}>
                      <QueuePanel
                        client={session.client}
                        editorDeviceId={deviceId}
                        onEditRow={onEditRow}
                        editingRowId={editingRow?.id ?? null}
                        editFinishing={editFinishing}
                        composerWidth={columnWidth}
                        hostSupportsActions={hostSupportsActions}
                        onSaveEdit={() => editCommitRef.current?.()}
                        onEditCancel={onEditCancel}
                      />
                    </QueueStoreProvider>
                  ) : null
                }
                footerSlot={
                  <ComposerFooter chat={row.chat} crSummary={crSummary} contextUsage={contextUsage} />
                }
              />
              <JumpPillAnchor state={jumpState} />
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