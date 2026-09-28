import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import type { ReactNode } from "react";
import type { Chat, ContextUsage, FetchToolBlobReply, SessionMessageEntry } from "@roboco/proto";
import { methods, type ChatStatus } from "@roboco/engine-client";
import type { IconName } from "@roboco/icons";
import type { StagedAttachment } from "../lib/attachments";
import { displayStatus, type ChatIndicator } from "../lib/view";
import { rightPaneStore, type RightSurface } from "../state/right-pane";
import { useEngineSession } from "../state/session-provider";
import { useEngineStatus, useNow, useWatchSnapshot } from "../state/hooks";
import { useFleetSnapshot } from "../state/fleet";
import { TranscriptStore, chatDeliveryDegraded, echoStore } from "../state/transcript-store";
import {
  clearPendingSideChat,
  dropUnsavedSideChat,
  isUnsavedSideChat,
  pendingSideChat,
  seedPendingSideChat,
  sideChatDrafts,
  sideChatHasDraft,
  subscribeUnsavedSideChats,
  unsavedSideChat,
} from "../state/side-chats";
import { reviewCommentStore } from "../state/review-comments";
import { chatDrafts } from "../lib/composer-draft";
import { dockFrameSettled } from "../lib/composer-dock";
import { COMPOSER_MAX_WIDTH } from "../lib/composer-flip";
import { sidebarNotice } from "../state/notice";
import { markChatSeen } from "../lib/chat-actions";
import { childChatTitle } from "../lib/explorer-sections";
import { ChangesSurface, ChangesToolbar, CommitDiffToolbar } from "../routes/changes-page";
import { HistoryPane } from "./history/history-pane";
import { HistoryToolbar } from "./history/history-toolbar";
import { FileSurface } from "./files/file-viewer";
import { TerminalDock } from "../terminal/terminal-dock";
import { paneTerminalStore } from "../terminal/store";
import { SurfacePicker } from "./surface-picker";
import { Composer } from "./composer";
import { ChatTranscriptOutlet } from "./chat-transcript-outlet";
import { ContextUsageIndicator, hasWindow } from "./context-usage";
import { JumpPill, TranscriptView, type JumpButtonState } from "./transcript";
import type { SubagentOpen } from "./tool-group";

/**
 * The right pane's surface registry — the seam between the pane HOST (this
 * ticket) and the surface BODIES (Changes 22, Files 24/25, Terminal 26,
 * History 27, subagent transcript 19). Later tickets replace an entry's
 * `render` through `registerRightSurface` without touching the host.
 *
 * Contextual chrome (title, detail, icon, dirty) reads the backing entities
 * out of `state/right-pane.ts` — the peer of the desktop's
 * `right_surface_rows` walking `file_surfaces` / `diffs` / `subagent_tabs`.
 */

export interface SurfaceContext {
  readonly chatId: string;
}

export interface RightSurfaceEntry {
  readonly kind: RightSurface["kind"];
  readonly title: (s: RightSurface, ctx: SurfaceContext) => string;
  readonly detail?: (s: RightSurface, ctx: SurfaceContext) => string | null;
  readonly icon: (s: RightSurface, ctx: SurfaceContext) => IconName;
  readonly isDirty?: (s: RightSurface, ctx: SurfaceContext) => boolean;
  /** Default true; the picker is the one unclosable surface. */
  readonly isClosable?: (s: RightSurface) => boolean;
  /** The `surface_chrome::toolbar` row above the body (Diff surfaces). */
  readonly toolbar?: (s: RightSurface, ctx: SurfaceContext) => ReactNode;
  readonly render: (s: RightSurface, ctx: SurfaceContext) => ReactNode;
}

const entries = new Map<RightSurface["kind"], RightSurfaceEntry>();

/** Register (or replace) a surface kind's entry. */
export function registerRightSurface(entry: RightSurfaceEntry): void {
  entries.set(entry.kind, entry);
}

export function surfaceEntry(kind: RightSurface["kind"]): RightSurfaceEntry | undefined {
  return entries.get(kind);
}

/*
 * Boot wiring: the pane's terminal host is injected here rather than imported
 * by `state/right-pane.ts` (that module must stay loadable in the node test
 * environment, and xterm must not come with it). The pane store's version
 * bumps — a shell's OSC title changing, a tab exiting — re-render the pane's
 * chips through the right-pane store's notify, the desktop's TitleChanged
 * fan-out's peer.
 */
rightPaneStore.setTerminalSource(paneTerminalStore);
paneTerminalStore.subscribe(() => {
  rightPaneStore.notify();
});

/*
 * More boot wiring (ticket 10): the side-chat entity source — the pane
 * store's close/prune consult it for draft retention and entity disposal.
 * `has_draft` mirrors the desktop's `composer.has_draft` (text, staged
 * attachments, staged comments; the web composer has no appshots), and the
 * dispose drops the mirrored draft when a side chat's row is gone for good.
 */
rightPaneStore.setSideChatEntitySource({
  hasDraft: (chatId) => sideChatHasDraft(chatId, (id) => reviewCommentStore.stagedFor(id).length),
  isUnsaved: (chatId) => isUnsavedSideChat(chatId),
  dispose: (chatId) => {
    sideChatDrafts.clear(chatId);
    clearPendingSideChat(chatId);
    dropUnsavedSideChat(chatId);
  },
});

/** The backing facts for a surface, straight from the entity maps. */
function facts(surface: RightSurface, ctx: SurfaceContext) {
  return rightPaneStore.describe(surface, ctx.chatId);
}

function titleOf(fallback: string): (s: RightSurface, ctx: SurfaceContext) => string {
  return (s, ctx) => facts(s, ctx)?.title ?? fallback;
}

/**
 * The pane's content for a surface — `render_right_pane`'s match. A surface
 * whose backing entity is gone renders the picker (`… else the picker`).
 */
export function renderRightSurface(surface: RightSurface, ctx: SurfaceContext): ReactNode {
  if (surface.kind !== "picker" && facts(surface, ctx) === null) {
    return <SurfacePicker chatId={ctx.chatId} />;
  }
  return entries.get(surface.kind)?.render(surface, ctx) ?? null;
}

/**
 * `surface_chrome::toolbar` (§3.27): the 38 px border-box row a Diff surface
 * mounts above its body. Its controls — the scope selector, ref selector,
 * split/wrap/fold-all — are ticket 22's `ChangesToolbar`, reading and
 * mutating the same per-surface state store the body renders from.
 */

/**
 * The Terminal surface — the pane's embedded panel (`RightSurface::Terminal`
 * rendering the shared `right_terminal` panel with `select_tab_by_key`).
 * Each surface chip addresses ONE terminal tab: the id is the tab's key,
 * minted together by `addTerminalSurface`/`openTabFor`.
 */
function TerminalSurface({ surfaceId, chatId }: { surfaceId: string; chatId: string }) {
  return <TerminalDock store={paneTerminalStore} chatId={chatId} docked tabKey={surfaceId} />;
}

/**
 * A subagent's transcript, as a right-pane tab — the desktop's
 * `add_subagent_surface` (shell.rs:2682) + `Transcript::for_doc`. A LIVE
 * subagent watches its doc directly; a FROZEN one (done/failed) tries the
 * `{chatId}/{docId}` uploaded snapshot blob first and falls back to the
 * live watch on any failure. The transcript rides the subagent override
 * (alignTop: top-aligned, top-only fade, no rail, no own-turn runway).
 * Spawn chips inside it open their own nested tabs through the same
 * registrar, keyed to the pane's chat.
 */
function SubagentSurface({ surfaceId, chatId }: { surfaceId: string; chatId: string }) {
  const session = useEngineSession();
  const status = useEngineStatus(session);
  const meta = rightPaneStore.subagentSurfaceOf(surfaceId);
  const client = session?.client ?? null;
  const deviceId = status !== null && status.state === "connected" ? status.info.deviceId : null;
  const docId = meta?.docId ?? null;
  const frozen = meta?.frozen ?? false;
  const blobChatId = meta?.chatId ?? null;

  const [store, setStore] = useState<TranscriptStore | null>(null);
  useEffect(() => {
    if (client === null || docId === null) {
      return;
    }
    const created = new TranscriptStore(client, docId, frozen ? { follow: false } : undefined);
    setStore(created);
    return () => {
      created.dispose();
      setStore((current) => (current === created ? null : current));
    };
  }, [client, docId, frozen]);

  // The frozen snapshot fetch — a best-effort blob read; ANY failure falls
  // back to the live doc watch (`resubscribe` arms it).
  useEffect(() => {
    if (!frozen || client === null || docId === null || blobChatId === null || store === null) {
      return;
    }
    let cancelled = false;
    client
      .call<FetchToolBlobReply>(methods.FETCH_TOOL_BLOB, { blobRef: `${blobChatId}/${docId}` })
      .then((reply) => {
        if (cancelled) {
          return;
        }
        const entries = parseSnapshotEntries(reply.text);
        if (entries !== null) {
          store.seedEntries(entries);
        } else {
          store.resubscribe();
        }
      })
      .catch(() => {
        if (!cancelled) {
          store.resubscribe();
        }
      });
    return () => {
      cancelled = true;
    };
  }, [frozen, client, docId, blobChatId, store]);

  if (client === null || store === null) {
    return null;
  }
  const onOpenSubagent = (payload: SubagentOpen): void => {
    rightPaneStore.addSubagentSurface(chatId, payload);
  };
  return (
    <TranscriptView client={client} docId={store.docId} deviceId={deviceId} store={store} alignTop onOpenSubagent={onOpenSubagent} />
  );
}

/** Parse a frozen snapshot blob — a JSON array of transcript entries. */
function parseSnapshotEntries(text: string): SessionMessageEntry[] | null {
  try {
    const parsed: unknown = JSON.parse(text);
    if (!Array.isArray(parsed)) {
      return null;
    }
    return parsed.filter(
      (entry): entry is SessionMessageEntry =>
        typeof entry === "object" && entry !== null && "id" in entry && "parts" in entry,
    );
  } catch {
    return null;
  }
}

/** The side-chat surface's settled-docked frame (set_side_chat +
 *  `DockFrame::settled(true)`, render_side_chat) — one constant, shared. */
const SIDE_CHAT_DOCK_FRAME = dockFrameSettled(true);

/**
 * One side chat, as a right-pane tab — the desktop's `render_side_chat`
 * (side_chats.rs:336-381): its OWN transcript store and its OWN composer
 * for that chat id, stacked like the conversation column (transcript over
 * composer), with the jump pill floating over the composer.
 *
 * Parity notes against the desktop's `open_side_chat`:
 *
 * - **Independent per-side-chat state**: the desktop seeds a whole
 *   `AppState::side_chat_state`; the web's store is chat-id-scoped (the
 *   registry row and the watch streams are already per-id), so the
 *   surface owns exactly the pieces that are per-INSTANCE: the transcript
 *   store and the composer. Chat-row lookups (title/status) read the
 *   MERGED fleet snapshot, bridged by the `pending_side_chat` seed until
 *   the registry frame lands a fresh fork's row; a row gone for good
 *   renders the picker (the desktop's `render_side_chat` → picker when
 *   `selected_chat` is gone).
 * - **Draft retention**: the composer's text and staged attachments push
 *   into `sideChatDrafts` (plus `chatDrafts` for the text seed) on every
 *   change, so an unmounted surface restores its draft on remount — the
 *   desktop's kept `SideChatTab`, web-shaped.
 * - **The composer is side-chat-shaped**: settled-docked frame (the
 *   desktop's `set_dock_frame(DockFrame::settled(true))`), no queue panel,
 *   and a footer of ONLY the context ring — the desktop's
 *   `set_side_chat` ("no checkout/ref footer, no plan usage").
 */
function SideChatSurface({ surfaceId, chatId }: { surfaceId: string; chatId: string }) {
  const session = useEngineSession();
  const status = useEngineStatus(session);
  const snapshot = useFleetSnapshot();
  const now = useNow(10_000);
  const sessionWatch = useWatchSnapshot(session);
  const meta = rightPaneStore.sideChatSurfaceOf(surfaceId);
  const sideChatId = meta?.chatId ?? null;
  // The row: the fleet's, else the freshly created seed (the desktop's
  // `pending_side_chat`) — a fork that just landed owns its row before the
  // registry's watch frame carries it — else the UNSAVED local row (the
  // desktop's `unsaved_side_chat`): a hand-started side chat exists only
  // client-side until its first send mints it.
  const chat =
    sideChatId !== null && snapshot.chats.loaded
      ? snapshot.chats.rows.find((row) => row.id === sideChatId) ?? null
      : null;
  const effectiveChat =
    chat ??
    (sideChatId !== null ? (pendingSideChat(sideChatId) ?? unsavedSideChat(sideChatId)) : null);
  // The unsaved flag is store-shaped: the first send's mint flips it and the
  // surface re-renders — the transcript store effect re-runs with its watch.
  const unsaved = useSyncExternalStore(
    subscribeUnsavedSideChats,
    () => sideChatId !== null && isUnsavedSideChat(sideChatId),
  );
  const deviceId = status !== null && status.state === "connected" ? status.info.deviceId : null;

  // The transcript store: the surface's own, like the subagent pane's. It
  // unmounts with the surface (the pane renders one surface at a time) and
  // re-subscribes on remount — the watch re-sends a full reset, so the rows
  // come back exactly as they were. An UNSAVED side chat defers its doc
  // watch (`select_chat`'s skip, upstream #568): the store mounts watchless
  // and seeded empty (nothing transient to shimmer), and the first send's
  // mint flips `unsaved` — this effect re-runs and the fresh store attaches
  // the deferred watch (`side_chat_saved` → `start_chat_watches`).
  const [store, setStore] = useState<TranscriptStore | null>(null);
  useEffect(() => {
    if (session === null || sideChatId === null) {
      return;
    }
    const created = new TranscriptStore(session.client, sideChatId, { follow: !unsaved });
    if (unsaved) {
      created.seedEntries([]);
    }
    setStore(created);
    return () => {
      created.dispose();
      setStore((current) => (current === created ? null : current));
    };
  }, [session, sideChatId, unsaved]);

  // The tab title follows the chat row — `child_chat_title` (title →
  // lastMessagePreview → placeholder), refreshed as the row changes so the
  // strip always says what the explorer's Chats section says.
  useEffect(() => {
    if (effectiveChat !== null) {
      rightPaneStore.updateSideChatTitle(surfaceId, childChatTitle(effectiveChat));
    }
  }, [effectiveChat, surfaceId]);

  // The creation seed retires the moment the registry row lands (the
  // snapshot wins by construction; the map stays the size of the gap).
  useEffect(() => {
    if (chat !== null && sideChatId !== null) {
      clearPendingSideChat(sideChatId);
    }
  }, [chat, sideChatId]);

  // Opening a side chat IS reading it (`mark_chat_seen`) — same rule as the
  // chat page, keyed to the side chat's own id. An unsaved one skips it:
  // the desktop's `mark_chat_seen` early-returns on a chat with no messages,
  // and this chat has none until its first send.
  useEffect(() => {
    if (session === null || sideChatId === null || isUnsavedSideChat(sideChatId)) {
      return;
    }
    markChatSeen(session.client, sideChatId);
  }, [session, sideChatId]);

  const [contextUsage, setContextUsage] = useState<ContextUsage | null>(null);
  useEffect(() => {
    setContextUsage(null);
  }, [sideChatId]);
  const [jumpState, setJumpState] = useState<JumpButtonState | null>(null);
  const onJumpChange = useCallback((state: JumpButtonState) => {
    setJumpState((current) =>
      current?.shown === state.shown && state.shown === false ? current : state,
    );
  }, []);

  // The pane's width, measured live — the composer's `set_available_width`
  // feed (the desktop's `right_visible_width`).
  const hostRef = useRef<HTMLDivElement | null>(null);
  const [width, setWidth] = useState<number | null>(null);
  useEffect(() => {
    const host = hostRef.current;
    if (host === null || typeof ResizeObserver === "undefined") {
      return;
    }
    const observer = new ResizeObserver(() => {
      setWidth(host.getBoundingClientRect().width);
    });
    observer.observe(host);
    setWidth(host.getBoundingClientRect().width);
    return () => observer.disconnect();
  }, []);
  const availableWidth =
    width === null ? null : Math.min(Math.max(width, 0), COMPOSER_MAX_WIDTH);

  if (session === null || sideChatId === null) {
    return <SurfacePicker chatId={chatId} />;
  }
  // The row is gone for good (deleted): the picker, exactly the desktop's
  // `render_side_chat` fallback. A just-created chat renders from its seed
  // below.
  if (effectiveChat === null || store === null) {
    return <SurfacePicker chatId={chatId} />;
  }

  const onOpenSubagent = (payload: SubagentOpen): void => {
    rightPaneStore.addSubagentSurface(chatId, payload);
  };
  const onRetryDelivery = (): void => {
    if (session.client.state !== "connected") {
      return;
    }
    // Same shape as the chat page's `retry_send` half: restart the grace
    // clocks (same message ids, so the overlay returns to Sending), then
    // re-deliver through the engine's RETRY_DELIVERY.
    echoStore.restartGrace(sideChatId, Date.now());
    void session.client
      .call(methods.RETRY_DELIVERY, { chatId: sideChatId })
      .catch((error: unknown) => {
        sidebarNotice.set(
          `Could not retry delivery: ${error instanceof Error ? error.message : String(error)}`,
        );
      });
  };
  // The draft mirror (`onDraftChange` → `sideChatDrafts` + the text seed in
  // `chatDrafts`); the staged seed reads the mirror back on mount.
  const onDraftChange = (draft: { text: string; staged: readonly StagedAttachment[] }): void => {
    sideChatDrafts.set(sideChatId, draft);
    chatDrafts.set(sideChatId, draft.text);
  };
  const deliveryDegraded = chatDeliveryDegraded(sessionWatch?.connectivity.value?.state);
  const statusRow = snapshot.statuses.rows.find((row) => row.chatId === sideChatId);
  const indicator = displayStatusFor(effectiveChat, statusRow, now);
  const turnStartedAt = startedAtOf(statusRow);
  const markdownSurface = useMemo(
    () => ({
      workspaceRoot: effectiveChat.cwd,
      // A side chat's file link opens an editor keyed to the SIDE CHAT (one
      // tab per (chat, path)), docked into this pane's strip — the desktop's
      // `activate_session_link` binding the editor to the side chat's own
      // checkout.
      openWorkspaceFile: (path: string, line: number | null, column: number | null) => {
        rightPaneStore.addFileSurface(
          chatId,
          path,
          sideChatId,
          line !== null ? { line, column } : null,
        );
      },
    }),
    [effectiveChat.cwd, chatId, sideChatId],
  );

  return (
    <div className="side-chat-surface" ref={hostRef}>
      <div className="side-chat-transcript">
        <ChatTranscriptOutlet store={store} departing={false}>
          {(activeStore) => (
            <TranscriptView
              client={session.client}
              docId={sideChatId}
              deviceId={deviceId}
              store={activeStore}
              markdownSurface={markdownSurface}
              onContextUsage={setContextUsage}
              onRetryDelivery={onRetryDelivery}
              onJumpChange={onJumpChange}
              indicator={indicator}
              turnStartedAt={turnStartedAt}
              onOpenSubagent={onOpenSubagent}
              deliveryDegraded={deliveryDegraded}
            />
          )}
        </ChatTranscriptOutlet>
        {jumpState !== null && jumpState.shown && (
          <div className="side-chat-jump-anchor">
            <JumpPill onClick={jumpState.jump} />
          </div>
        )}
      </div>
      <div className="side-chat-composer">
        <Composer
          session={session}
          chat={effectiveChat}
          catalog={session.catalog}
          transcript={store}
          availableWidth={availableWidth}
          dockFrame={SIDE_CHAT_DOCK_FRAME}
          seedStaged={sideChatDrafts.get(sideChatId).staged}
          onDraftChange={onDraftChange}
          footerSlot={
            hasWindow(contextUsage) ? <ContextUsageIndicator usage={contextUsage} /> : null
          }
        />
      </div>
    </div>
  );
}

/** The side chat's display status — the row's live, staleness-gated dot. */
function displayStatusFor(chat: Chat, statusRow: ChatStatus | undefined, now: number): ChatIndicator {
  return displayStatus(chat, statusRow, now);
}

/** The session row's `started_at` in epoch ms, for the working trailer. */
function startedAtOf(statusRow: ChatStatus | undefined): number | null {
  const started = statusRow?.startedAt ?? null;
  if (started === null) {
    return null;
  }
  const parsed = Date.parse(started);
  return Number.isFinite(parsed) ? parsed : null;
}

let registered = false;

/** The stub + real bodies this ticket wires; later tickets re-register. */
function registerDefaults(): void {
  if (registered) {
    return;
  }
  registered = true;

  registerRightSurface({
    kind: "picker",
    title: () => "Picker",
    icon: () => "plus",
    isClosable: () => false,
    render: (_s, ctx) => <SurfacePicker chatId={ctx.chatId} />,
  });

  registerRightSurface({
    kind: "file",
    title: titleOf("File"),
    detail: (s, ctx) => facts(s, ctx)?.detail ?? null,
    // The tab strip's IconName slot is monochrome by design; the
    // polychrome file-type icon lives in the surface's breadcrumb toolbar
    // (`FileIcon`, ticket 24's manifest).
    icon: () => "document",
    render: (s, ctx) => (s.kind === "file" ? <FileSurface chatId={ctx.chatId} surfaceId={s.id} /> : null),
  });

  registerRightSurface({
    kind: "diff",
    title: titleOf("Diffs"),
    // `git-branch` when that `Changes` `is_history()`, else `list`.
    icon: (s, ctx) => (facts(s, ctx)?.isHistory === true ? "gitBranch" : "list"),
    toolbar: (s, ctx) => {
      if (s.kind !== "diff") {
        return null;
      }
      const meta = rightPaneStore.diffMetaOf(s.id);
      if (meta !== null && meta.flavor === "history") {
        return <HistoryToolbar chatId={ctx.chatId} surfaceId={s.id} />;
      }
      if (meta !== null && meta.flavor === "commit") {
        return <CommitDiffToolbar chatId={ctx.chatId} surfaceId={s.id} />;
      }
      return <ChangesToolbar chatId={ctx.chatId} surfaceId={s.id} />;
    },
    render: (s, ctx) => {
      if (s.kind !== "diff") {
        return null;
      }
      const meta = rightPaneStore.diffMetaOf(s.id);
      if (meta !== null && meta.flavor === "history") {
        return <HistoryPane chatId={ctx.chatId} surfaceId={s.id} />;
      }
      return <ChangesSurface chatId={ctx.chatId} surfaceId={s.id} />;
    },
  });

  registerRightSurface({
    kind: "terminal",
    title: titleOf("Terminal"),
    icon: () => "terminal",
    render: (s, ctx) => (s.kind === "terminal" ? <TerminalSurface surfaceId={s.id} chatId={ctx.chatId} /> : null),
  });

  registerRightSurface({
    kind: "subagent",
    title: titleOf("Subagent"),
    icon: () => "bot",
    render: (s, ctx) => (s.kind === "subagent" ? <SubagentSurface surfaceId={s.id} chatId={ctx.chatId} /> : null),
  });

  // `RightSurface::SideChat(_)` → icons::CHAT_ROUND_LINE (shell.rs:9106).
  registerRightSurface({
    kind: "sidechat",
    title: titleOf("Side chat"),
    icon: () => "chatRoundLine",
    render: (s, ctx) => (s.kind === "sidechat" ? <SideChatSurface surfaceId={s.id} chatId={ctx.chatId} /> : null),
  });
}

registerDefaults();
