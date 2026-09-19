import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { flushSync } from "react-dom";
import { Link, useNavigate, useRouterState } from "@tanstack/react-router";
import { motion } from "@roboco/theme";
import { Icon, harnessBrandIcon } from "@roboco/icons";
import { encodeScopedId, methods, parseScopedId } from "@roboco/engine-client";
import { MESSAGE_QUEUE_ACTIONS_V1 } from "@roboco/proto";
import type { Chat, QueuedMessage } from "@roboco/proto";
import type { ChangeRequestSummary, ContextUsage } from "@roboco/proto";
import { useEngineSession } from "../state/session-provider";
import { engineRegistry, engineStatesOf, useFleetRegistry, useFleetSnapshot } from "../state/fleet";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { useTitlebar } from "../state/chrome";
import { emitShortcut } from "../state/shortcuts";
import { chatPageRow, type ChatRow } from "../lib/view";
import { JumpPill, StatusStrip, TranscriptView, type JumpButtonState } from "../components/transcript";
import type { SubagentOpen } from "../components/tool-group";
import { remaskNewThreadBackground } from "../components/new-thread-background";
import { resolvePaneWidth, rightPaneStore, useRightPane } from "../state/right-pane";
import { Composer } from "../components/composer";
import { QueuePanel } from "../components/queue-panel";
import { ComposerFooter } from "../components/composer-footer";
import { useNewThreadTarget } from "../components/composer/new-thread-selectors";
import { NewThreadCanvas } from "./index-page";
import {
  bottomClearance,
  conversationWidth,
  evalWidthTween,
  sidebarTarget,
  useSidebarLayout,
  useViewportWidth,
} from "../state/layout";
import { useIsPhone } from "../state/media";
import { navEntryForPath } from "../state/nav-history";
import {
  bottomStackMeasurementMatches,
  dockFrameEquals,
  dockFrameSettled,
  DockState,
  heroLayerMounted,
  type DockFrame,
} from "../lib/composer-dock";
import { COMPOSER_MAX_WIDTH } from "../lib/composer-flip";
import { ChangeRequestStore, type ChangeRequestTarget, changeRequestForChat } from "../state/change-requests-store";
import { QueueStore } from "../state/queue-store";
import { QueueStoreProvider } from "../state/queue-store-context";
import { sidebarNotice } from "../state/notice";
import { markChatSeen } from "../lib/chat-actions";
import { availableQueuePrimaryAction } from "../lib/queue-row-logic";
import { ATTACHMENT_ONLY_TEXT, uploadAttachments, type StagedAttachment } from "../lib/attachments";
import { TerminalDock } from "../terminal/terminal-dock";
import { drawerTerminalStore } from "../terminal/store";
import type { MarkdownSurface } from "../components/markdown";
import { echoStore, TranscriptStore, chatDeliveryDegraded, type TranscriptCache } from "../state/transcript-store";

/** `motion::RESIZE` — the 200ms curve the sidebar glide and the hero's tween ride. */
const SIDEBAR_GLIDE_MS =
  motion.specs.find((spec) => spec.name === "resize")?.durationMs ?? 200;

/**
 * The chat transcript's offline cache handle: `(engineKey, rawChatId)`
 * resolved off the scoped URL id, backed by the fleet registry's cache.
 */
function transcriptCacheFor(engineKey: string, scopedChatId: string): TranscriptCache | undefined {
  try {
    const raw = parseScopedId(scopedChatId).rawId;
    const cache = engineRegistry.cache;
    return {
      load: () => cache.loadTranscript(engineKey, raw),
      save: (entries) => cache.saveTranscript(engineKey, raw, entries),
    };
  } catch {
    return undefined;
  }
}

/**
 * The conversation page — the desktop's `render_main` (shell.rs:5806-6154).
 *
 * BOTH conversation routes render THIS component (`/` and `/chat/$chatId`,
 * see `router.tsx`): TanStack's `Match` memoizes the component element on
 * `route.options.component`, so the same `ConversationPage` reference on
 * both routes keeps ONE fiber alive across the route boundary — the web
 * peer of the desktop's "one composer entity, re-anchored in prepaint,
 * never remounted". The caret, the selection, the draft and the popup state
 * all travel with the pixels. The page re-renders on navigation through its
 * own router-state subscription; the chat id comes from the pathname
 * (`""` = the new-thread canvas, the boot route).
 *
 * The blank canvas is the hero + the SAME persistent composer vertically
 * re-anchored: the composer's wrapper sits in the bottom chrome stack (its
 * layout slot), and the dock (`lib/composer-dock.ts`, the port of
 * `composer_dock.rs`) glides it to `(viewportHeight − height)·0.5 + 8` via a
 * transform, anchoring by the TOP of the surface. One retargetable clock
 * owns everything: the glide (0.420/470 s critically damped), the pill
 * height (`dockHeight`), the four staged chrome channels, the hero's
 * `dissolve`, the 0.320 s panel handoff, and the width glide that snaps
 * inside the handoff's invisible interval. Reduced motion snaps everything.
 * The phone layer (≤ 768px) mounts the same canvas — hero, selectors, dock
 * re-anchoring — per ticket 53's amendment to spec decision 5.
 */
export function ConversationPage() {
  // `chatId === ""` is the new-thread canvas; anything else names a chat.
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  const navEntry = navEntryForPath(pathname);
  const chatId = navEntry !== null && navEntry.kind === "chat" ? navEntry.chatId : "";
  const hasSelection = chatId !== "";
  const session = useEngineSession();
  // The MERGED fleet snapshot: the chat row lookup by its scoped URL id
  // spans every engine; `session` above is the chat's owning engine (the
  // routed session), so calls and watches (transcript/queue/change
  // requests) already target the right connection.
  const snapshot = useFleetSnapshot();
  const registry = useFleetRegistry();
  const status = session === null ? null : session.client.status;
  const now = useNow(10_000);
  const navigate = useNavigate();

  // The routed engine's live `WatchConnectivity` posture (ticket 30's
  // per-engine slot in the session's watch cache) — `chat_delivery_degraded`'s
  // web arm: while the chat's delivery path is degraded, the transcript's
  // pending-send overlay holds "pending" (Queued), never a false "Not
  // delivered" past the 120s grace. No new stream: the page re-renders on
  // this engine's watch changes already (the fleet registry).
  const sessionWatch = useWatchSnapshot(session);
  const deliveryDegraded = chatDeliveryDegraded(sessionWatch?.connectivity.value?.state);

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
  const chat = !snapshot.chats.loaded
    ? undefined
    : snapshot.chats.rows.find((row) => row.id === chatId) ?? undefined;
  const branch = chat?.branch ?? null;
  const checkoutId = chat?.checkoutId ?? null;
  const cwd = chat?.cwd ?? null;

  // ── The canvas target + the stub chat (the new-thread route) ───────────
  // The canvas has no chat row; the composer still needs a `Chat`-shaped
  // target — the remembered device/project picks resolved through
  // `effective_device_id` (state.rs:1314-1320). The stub's id is the DRAFT
  // key `""` (the desktop's `current_key` for the new-thread canvas), so
  // the canvas draft survives every round trip.
  const target = useNewThreadTarget();
  const stubChat = useMemo<Chat>(
    () => ({
      id: chatId,
      deviceId: target.effectiveDeviceId ?? "",
      title: null,
      archived: false,
      cwd: target.space?.path ?? null,
      branch: null,
      checkoutId: null,
      config: null,
      lastMessagePreview: null,
      lastMessageAt: null,
      createdAt: new Date(0).toISOString(),
      spaceId: target.space?.id ?? null,
    }),
    [chatId, target.effectiveDeviceId, target.space?.id, target.space?.path],
  );
  // While a freshly minted chat's row is still landing, the stub stands in
  // (same id, so the composer never re-swaps its draft).
  const effectiveChat = chat ?? stubChat;

  // The markdown host hooks (transcript.rs:5341-5349 `workspace_root` +
  // `LinkOutcome::Internal`): the chat's cwd resolves agent-authored file
  // links, and an internal click opens the file's right-pane tab.
  const markdownSurface = useMemo<MarkdownSurface>(
    () => ({
      workspaceRoot: cwd,
      openWorkspaceFile: (path) => {
        rightPaneStore.addFileSurface(chatId, path);
        if (!rightPaneStore.stateFor(chatId).open) {
          rightPaneStore.toggle(chatId);
        }
      },
    }),
    [chatId, cwd],
  );

  // The chat's ONE transcript store: the transcript view and the composer's
  // question wizard both read it, so an open chat carries a single
  // `WatchDocMessages` stream. The canvas has none; a DEPARTING transcript
  // (undocking back to the canvas) keeps the source store until the route
  // finishes its exit (`finish_route_exit`, shell.rs:5901-5904).
  const transcriptStore = useMemo(() => {
    if (session === null || chatId === "") {
      return null;
    }
    return new TranscriptStore(session.client, chatId, {
      // §2.3's per-chat offline cache: last-seen entries for chats the user
      // has actually opened, keyed `(engine, raw chat id)`.
      cache: transcriptCacheFor(session.engine.baseUrl, chatId),
    });
  }, [session, chatId]);
  // The LIVE store: the departing transcript (undocking back to the canvas)
  // keeps painting from the source chat's stream until the route finishes
  // its exit (`finish_route_exit`, shell.rs:5901-5904). `dispose` is
  // idempotent, so the exit and a later replacement can both call it.
  const storeRef = useRef<TranscriptStore | null>(null);
  const previousStoreRef = useRef<TranscriptStore | null>(null);
  if (transcriptStore !== null) {
    storeRef.current = transcriptStore;
  }
  // A chat switch replaces the stream: the old chat's store is disposed once
  // the new one has committed.
  useEffect(() => {
    if (transcriptStore === null) {
      return;
    }
    const previous = previousStoreRef.current;
    if (previous !== null && previous !== transcriptStore) {
      previous.dispose();
    }
    previousStoreRef.current = transcriptStore;
  }, [transcriptStore]);

  useEffect(() => () => {
    storeRef.current?.dispose();
    storeRef.current = null;
  }, []);

  // A spawn chip's "Open subagent" registers the right-pane tab under this
  // chat (`add_subagent_surface`, shell.rs:2682) — the pane opens on it.
  const onOpenSubagent = useCallback(
    (payload: SubagentOpen) => {
      rightPaneStore.addSubagentSurface(chatId, payload);
    },
    [chatId],
  );

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
    if (session === null || deviceId === null || chatId === "") {
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
    !snapshot.chats.loaded
      ? undefined
      : chatPageRow(
          chatId,
          snapshot.chats.rows,
          snapshot.spaces.rows,
          snapshot.statuses.rows,
          now,
          snapshot.devices.rows,
          engineStatesOf(registry),
          // The same loaded-only dangling gate as the sidebar (ticket 43).
          snapshot.spaces.loaded,
        );

  // ── The dock: one retargetable clock for the route choreography ────────
  const viewport = useViewportWidth();
  const viewportHeight = useViewportHeight();
  const sidebar = useSidebarLayout();
  // The shared media hook (ticket 49): `(max-width: 768px)` resolved through
  // matchMedia — the same query the stylesheet keys, so JS and CSS flip in
  // the same paint (the old `viewport <= PHONE_MAX_WIDTH` innerWidth compare could
  // disagree with the media query by rounding).
  const phone = useIsPhone();
  // The phone sidebar is a fixed overlay out of flow (the same M3/M2
  // correction `app-shell.tsx` applies to the titlebar's geometry), so the
  // hero's sidebar term reads 0 at ≤768 — `heroWidth` is the full phone
  // canvas width, never `viewport − dragged` (375/304 would paint a 71px
  // hero).
  const sidebarNow = phone ? 0 : sidebarTarget(sidebar);
  const [reducedMotion, setReducedMotion] = useState(prefersReducedMotion);
  useEffect(() => {
    const query = window.matchMedia("(prefers-reduced-motion: reduce)");
    const onChange = () => setReducedMotion(query.matches);
    onChange();
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);
  // Ticket 53's recorded choice (§2.2, option 1): the dock re-anchors at
  // phone exactly as at ≥769 — the glide, the chrome channels and the
  // dissolve all run, anchored at `(vh − h)·0.5 + 8`; reduced motion still
  // snaps everything through `reducedMotion`.
  const dockReduced = reducedMotion;

  // ── The sidebar slide (ticket 34) ─────────────────────────────────────
  // `toggle_sidebar` (shell.rs:1947-1957) arms a oneshot 200ms tween from
  // the PAINTED width at the flip — a mid-animation reversal restarts from
  // what is painted — and the desktop's `sidebar_now()` (3797-3801)
  // evaluates it per frame, feeding `hero_width = viewport −
  // sidebar_now()` (shell.rs:5897). The web's column already glides on the
  // exact CSS transition (`app.css:1159`); this loop is the missing half —
  // feeding the ANIMATED value to the hero (and its mask) instead of the
  // target. `heroWidth` stays a prop-driven inline style with NO CSS width
  // transition: one clock (this loop) must own the width, or the mask
  // cannot follow it.
  const sidebarTweenRef = useRef<{ from: number; to: number; startedAt: number } | null>(null);
  const previousSidebarRef = useRef(sidebar);
  // The animated sidebar width while a tween is live, else null — settled,
  // where the target formula below IS the endpoint.
  const [animatedSidebar, setAnimatedSidebar] = useState<number | null>(null);
  const [sidebarPump, setSidebarPump] = useState(0);

  // Arm on a flip. A LAYOUT effect: the flip commit would otherwise paint
  // the endpoint for one frame (heroWidth falls back to the target before
  // the loop's first frame); writing `from` here re-renders before paint,
  // so the flip frame paints the hero exactly where it already is.
  useLayoutEffect(() => {
    const previous = previousSidebarRef.current;
    previousSidebarRef.current = sidebar;
    if (previous.collapsed === sidebar.collapsed) {
      // Not a flip: a seam drag took the clock over mid-tween (the column
      // tracks the pointer exactly under `data-rb-resizing`) — drop the
      // tween so the hero follows the drag instead of a stale target.
      if (sidebarTweenRef.current !== null) {
        sidebarTweenRef.current = null;
        setAnimatedSidebar(null);
      }
      return;
    }
    sidebarTweenRef.current = null;
    if (reducedMotion || phone || sidebarTarget(previous) === sidebarTarget(sidebar)) {
      // `evalWidthTween`'s contract: under reduced motion the caller writes
      // the endpoint — the CSS has already snapped, and the settled formula
      // below IS the endpoint. At phone the sidebar is out of flow, so there
      // is no painted column width to tween — the hero's sidebar term is
      // pinned to 0 by the `sidebarNow` phone arm above.
      setAnimatedSidebar(null);
      return;
    }
    // `from` = the PAINTED width at the flip (a live tween's last frame,
    // else the previous target) plus the live edge-bounce offset — the
    // desktop's `sidebar_now()` captures both before `toggle_sidebar`
    // clears the bounce.
    const painted = animatedSidebar ?? sidebarTarget(previous);
    const from = painted + readSidebarEdgeBounceOffset();
    const to = sidebarTarget(sidebar);
    sidebarTweenRef.current = { from, to, startedAt: performance.now() };
    setAnimatedSidebar(from);
    setSidebarPump((pump) => pump + 1);
    // `animatedSidebar`/`reducedMotion`/`phone` are read from this render's
    // closure on purpose: only a flip re-arms, never their own changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sidebar]);

  // The frame pump: one write per ANIMATION frame while the tween is live —
  // the web peer of the desktop's `motion_active` → rAF render loop. The
  // setState is flushed SYNCHRONOUSLY inside the callback, not scheduled:
  // the column's CSS transition is evaluated at style time, so the commit
  // must land inside this same rAF phase for the hero to ride the SAME
  // frame's clock — the web peer of the desktop evaluating `sidebar_now()`
  // in render. A scheduled commit lands after paint and trails the column
  // by a frame. The same loop then re-runs the hero's remask, so the cutout
  // hole tracks the pill's viewport-space rect every frame (mask.rs:49-51):
  // the pill glides with the column (`margin-inline: auto`) even though its
  // size does not change.
  useEffect(() => {
    if (sidebarTweenRef.current === null) {
      return;
    }
    const raf = requestAnimationFrame(() => {
      const tween = sidebarTweenRef.current;
      if (tween === null) {
        return;
      }
      if (reducedMotion) {
        // `evalWidthTween`'s contract: the endpoint, directly — the CSS has
        // already snapped.
        sidebarTweenRef.current = null;
        setAnimatedSidebar(null);
        return;
      }
      const elapsed = performance.now() - tween.startedAt;
      const value = evalWidthTween(tween.from, tween.to, elapsed);
      flushSync(() => {
        setAnimatedSidebar(value);
      });
      remaskNewThreadBackground();
      if (elapsed >= SIDEBAR_GLIDE_MS) {
        // Done: hand the width back to the settled formula (the same
        // number — `to`), so a later drag can never read a stale one.
        sidebarTweenRef.current = null;
        setAnimatedSidebar(null);
        return;
      }
      setSidebarPump((pump) => pump + 1);
    });
    return () => cancelAnimationFrame(raf);
    // `reducedMotion` rides along so a mid-tween flip to reduced motion
    // snaps instead of running the clock out.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sidebarPump, reducedMotion]);

  const dockRef = useRef(new DockState());
  const [dockFrame, setDockFrameState] = useState<DockFrame>(() => dockFrameSettled(false));
  const [dockPump, setDockPump] = useState(0);
  const wrapperRef = useRef<HTMLDivElement | null>(null);
  const prepaintMovingRef = useRef(false);
  const dockTickedRef = useRef(false);

  // ── The pane's synchronous width — the handoff's arming input ───────────
  // `right_now` (shell.rs:3820-3826): the pane's width computed from STATE,
  // never measured — `eval_tween(right_tween, right_target)` is the TARGET
  // at a navigation commit (the chat switch clears the tweens, shell.rs
  // 1837-1862, "snap, no tween — the panels belong to the destination
  // chat"), plus the seam's edge bounce while the pane sits at a drag
  // bound. The pane store subscription matters even though the handoff
  // only arms on a docked flip: `previous` must carry the pane width that
  // was painted while docked, or the undock arm below would read width
  // 0→0 and never fire. The OLD input — the ResizeObserver-measured
  // `columnWidth` — was one commit stale by construction: on the flip
  // commit it still held the canvas width, so the docked flip arrived
  // WITHOUT its width change and the next (fresh) sample saw the width
  // change WITHOUT the flip — the handoff never armed (§2.1).
  const paneState = useRightPane(chatId);
  const paneOpen = hasSelection && paneState.open;
  const paneNowWidth = paneOpen
    ? resolvePaneWidth(paneState, viewport, phone ? 0 : (animatedSidebar ?? sidebarNow)) +
      (paneState.expanded ? 0 : readPaneEdgeBounceOffset())
    : 0;

  // ── observePane → transcriptWidth → tick — the desktop's paint order ────
  // KNOWN LIMITATION (sanctioned per tickets 35/36): this render body mutates
  // `dockRef.current` (observePane/transcriptWidth/tick) — React-concurrent-
  // unsafe if this pass were discarded. The tick is guarded to be idempotent
  // (`frame.docked !== hasSelection`), and no concurrent features are enabled,
  // so this is safe under current non-concurrent usage.
  // The shell samples the pane BEFORE `render_main`'s dock tick (observe_pane
  // at shell.rs:7901-7906, transcript_width at :7915-7919, the tick at
  // :5882-5885 inside render_main): the tick reads `pane.progress` to arm
  // the panel_return/panel_departure clocks, so the sample must land first —
  // the ordering ticket 35's merger note flagged (its render-phase tick
  // preceded the layout effect's observePane, leaving #panelDeparture
  // unreachable). One timestamp for all three, the desktop's `frame_time`.
  const frameNowMs = performance.now();
  const paneHandoffLive = dockRef.current.observePane(hasSelection, paneNowWidth, !dockReduced, frameNowMs);
  // `transcript_width` (shell.rs:7915-7919): the retained value feeds the
  // transcript wrapper's width. Called BEFORE the tick so the capture
  // condition (`!docked && frame.docked`) still sees the pre-flip frame —
  // what gets retained is the SOURCE column's width. The target is the
  // conversation's stable content width (`conversation_width(viewport,
  // sidebar_target, right_target_width)`, shell.rs:7910-7914 — the
  // takeover-stable leg never co-occurs with a route flip, which clears
  // the tween).
  const mainContentWidth = conversationWidth(viewport, sidebarTarget(sidebar), paneNowWidth);
  const retainedTranscriptWidth = dockRef.current.transcriptWidth(
    mainContentWidth,
    hasSelection,
    paneHandoffLive,
  );

  // ── The same-render dock tick (ticket 35, shell.rs:5882-5885) ────────────
  // The desktop decides the hero layer's mount from the frame ticked in the
  // SAME render — `tick` precedes the layer at shell.rs:5883. The web's
  // route change used to land one render BEFORE the per-commit layout
  // effect's tick below, so the navigation render read the previous
  // render's settled frame: `heroVisible` went false, the hero unmounted
  // for that commit (its artwork state with it), and the tick's re-render
  // remounted it — the route-change double flash. The route-change tick
  // now runs HERE, in the render body, and publishes through a render-phase
  // update (the sanctioned derive-state-during-render shape: React discards
  // this pass and re-renders immediately, so no commit ever paints with the
  // hero unmounted). The mount decision below reads the freshly ticked
  // MUTABLE frame; `dockFrame` state keeps driving the visuals.
  if (dockRef.current.frame.docked !== hasSelection) {
    const ticked = dockRef.current.tick(hasSelection, dockReduced, frameNowMs);
    setDockFrameState((prev) => (dockFrameEquals(prev, ticked) ? prev : ticked));
  }

  // ── Bottom chrome stack bookkeeping ─────────────────────────────────────
  // `bottom_stack` measured live (the desktop's paint-time canvas) PLUS
  // `dock_clearance_correction` — the shell reserves the DESTINATION
  // footprint, never the animated height, so the transcript's bottom fade
  // band and clearance pad never pump mid-route. The same measurement
  // records `bottom_stack_has_composer` for `transcript_geometry_ready`
  // (shell.rs:837): one frame of disagreement hides the transcript so it
  // never flashes under unmeasured chrome.
  const chatColumnRef = useRef<HTMLDivElement | null>(null);
  const bottomStackRef = useRef<HTMLDivElement | null>(null);
  const [columnWidth, setColumnWidth] = useState<number | null>(null);
  const dockCorrectionRef = useRef(0);
  const [measuredHasComposer, setMeasuredHasComposer] = useState(false);
  const expectedHasComposer = session !== null && hasSelection;
  const transcriptGeometryReady = bottomStackMeasurementMatches(measuredHasComposer, expectedHasComposer);

  useEffect(() => {
    const column = chatColumnRef.current;
    if (column === null || typeof ResizeObserver === "undefined") {
      return;
    }
    const observer = new ResizeObserver(() => {
      setColumnWidth(column.getBoundingClientRect().width);
    });
    observer.observe(column);
    setColumnWidth(column.getBoundingClientRect().width);
    return () => observer.disconnect();
    // The early returns above gate the refs; re-arm once the tree lands.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chatId, row?.chat.id, session]);

  // The dock's per-commit pass: re-anchor the composer's wrapper, publish
  // the stack measurement. Route changes tick in the render body (the
  // same-render tick above, ticket 35 — after the render-body `observePane`
  // sample, the desktop's paint order); the pane sample runs there too, per
  // render, so the handoff's clock advances on the pump's re-renders. The
  // measured `columnWidth` here feeds only the composer's width target.
  // Steady-state frames advance ONLY on the pump's animation frames —
  // ticking per commit would spin the layout-effect/setState pair
  // synchronously and freeze the glide. A layout effect so the transform
  // lands in the same commit as the frame — the web peer of
  // prepaint-before-paint.
  useLayoutEffect(() => {
    const dock = dockRef.current;
    const nowMs = performance.now();
    // The clock initializes on the first pass (the desktop's first tick
    // snaps the settled hero state and stamps `last_frame`); route changes
    // are already ticked by the render above, and the docked check stays as
    // the safety net. Steady frames advance on the pump.
    if (dock.frame.docked !== hasSelection || !dockTickedRef.current) {
      dockTickedRef.current = true;
      const next = dock.tick(hasSelection, dockReduced, nowMs);
      setDockFrameState((prev) => (dockFrameEquals(prev, next) ? prev : next));
    }

    const wrapper = wrapperRef.current;
    const stack = bottomStackRef.current;
    if (wrapper !== null && stack !== null) {
      const stackRect = stack.getBoundingClientRect();
      // The wrapper's NATURAL slot (transform excluded — offsets are layout
      // values): the desktop's prepaint reads the layout bounds.
      const bounds = {
        left: stackRect.left + wrapper.offsetLeft,
        top: stackRect.top + wrapper.offsetTop,
        height: wrapper.offsetHeight,
      };
      const { dx, dy, moving } = dock.prepaint(bounds, viewportHeight, dockReduced, nowMs);
      wrapper.style.transform = `translate(${dx}px, ${dy}px)`;
      prepaintMovingRef.current = moving;
    } else if (wrapper !== null) {
      wrapper.style.transform = "";
      prepaintMovingRef.current = false;
    }

    const column = chatColumnRef.current;
    if (stack !== null && column !== null) {
      const height = stack.getBoundingClientRect().height + dockCorrectionRef.current;
      column.style.setProperty("--rb-bottom-stack", `${height}px`);
      bottomClearance.set(height);
      setMeasuredHasComposer(session !== null && hasSelection);
    }
  });

  // The frame pump: one tick per ANIMATION FRAME while anything is in
  // flight (the desktop's `request_animation_frame` in prepaint/tick). The
  // setState lands in the rAF callback, so the tick's dt is the real frame
  // gap and the re-render's layout effect re-anchors without ticking again.
  // The pane-progress leg is shell.rs:7907-7909's `motion_active` peer
  // (`if panel_handoff { motion_active.set(true) }`): the handoff's 0.320 s
  // clock can outlive the glide AND the choreography, so `dockFrame.active`
  // alone would stop the pump early and strand the composer mid-fade (the
  // §2.4.2 warning) — the gate re-reads the live progress on every pump
  // bump.
  useEffect(() => {
    if (!dockFrame.active && !prepaintMovingRef.current && dockRef.current.paneProgress() === null) {
      return;
    }
    const raf = requestAnimationFrame(() => {
      const next = dockRef.current.tick(hasSelection, dockReduced, performance.now());
      setDockFrameState((prev) => (dockFrameEquals(prev, next) ? prev : next));
      setDockPump((value) => value + 1);
    });
    return () => cancelAnimationFrame(raf);
    // `hasSelection` rides along so a mid-glide reversal retargets the next
    // frame's tick.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dockFrame.active, dockPump, hasSelection]);

  // The composer's own width: glides on the dock's clock (snapping inside
  // the handoff's invisible interval), clamped to the 768px cap — the
  // desktop's `layout_width(main_content_width.min(COMPOSER_MAX_WIDTH))`,
  // fed back through `set_available_width` so the pill re-wraps mid-glide.
  // `layoutWidth` with a ~0 dt is a no-op, so render-path calls are safe.
  const composerWidthTarget = Math.min(Math.max(columnWidth ?? COMPOSER_MAX_WIDTH, 0), COMPOSER_MAX_WIDTH);
  const composerWidth = dockRef.current.layoutWidth(composerWidthTarget, dockReduced, performance.now());

  // The hero: full conversation canvas (`viewport − sidebar_now`, never
  // rescaled by the right pane), mounted while `!has_selection` or the dock
  // is still dissolving one away, outside the transcript's edge fade.
  // `sidebar_now` is the TWEENED width while the sidebar slide runs
  // (ticket 34) — the settled target otherwise (0 at phone, where the
  // sidebar is an out-of-flow overlay). The mount decision consumes
  // the MUTABLE frame ticked in THIS render (the same-render tick above,
  // ticket 35 — shell.rs:5883's `(!has_selection || dock_frame.active)`),
  // while `dockFrame` state drives the visuals; the phone layer mounts the
  // hero too (ticket 53 amended decision 5).
  const heroVisible = heroLayerMounted(hasSelection, dockRef.current.frame);
  const heroWidth = Math.max(viewport - (animatedSidebar ?? sidebarNow), 0);

  // The transcript outlet: selected chat → transcript; nothing selected →
  // the centered new-thread composition. A DEPARTING transcript (undocking)
  // keeps its pixels as visual history, fixed at the source column width
  // (`transcriptWidth`'s retained value, pinned on `.chat-body` below) and
  // occluded so it is not an interaction surface.
  const departing = !hasSelection && dockFrame.visuals.transcript > 0;
  // `finish_route_exit`: once the route is blank and the exit finished,
  // release the retained store.
  useEffect(() => {
    if (!hasSelection && !departing && storeRef.current !== null) {
      storeRef.current.dispose();
      storeRef.current = null;
    }
  }, [hasSelection, departing]);
  const liveTranscript = hasSelection ? transcriptStore : departing ? storeRef.current : null;

  // ── The chat→chat transcript swap (§2.4.4) ───────────────────────────────
  // The desktop's ONE `Transcript` entity swaps its doc synchronously on a
  // chat switch (shell.rs:5914-5945); the web keeps per-chat stores whose
  // first frame is async, so a swap would paint a blank frame between
  // chats. The previous chat's rows stay mounted — frozen at their last
  // snapshot, the store's watch gone or going — until the newly selected
  // store's first frame lands (the live reset or the offline cache seed,
  // whichever arrives first). The occluding veil below keeps the retained
  // pixels from being an interaction surface for the destination chat
  // (the departing transcript's own rule, shell.rs:5940-5944). The surface
  // swap happens when `loaded` flips: the outlet then hands the view the
  // new store, whose `key={active.docId}` remounts with rows already in
  // place — no blank frame.
  const subscribeTranscriptLoad = useCallback(
    (listener: () => void) =>
      transcriptStore === null ? () => {} : transcriptStore.subscribe(listener),
    [transcriptStore],
  );
  const getTranscriptLoaded = useCallback(
    () => transcriptStore?.getSnapshot().loaded ?? true,
    [transcriptStore],
  );
  const newTranscriptLoaded = useSyncExternalStore(
    subscribeTranscriptLoad,
    getTranscriptLoaded,
    () => true,
  );
  // The last store the outlet PAINTED — written during render like
  // `storeRef` above (idempotent: the swap window keeps writing the same
  // frozen store).
  const lastPaintedTranscriptRef = useRef<TranscriptStore | null>(null);
  const swappingTranscript =
    hasSelection &&
    !newTranscriptLoaded &&
    lastPaintedTranscriptRef.current !== null &&
    lastPaintedTranscriptRef.current !== transcriptStore;
  const transcriptForOutlet = swappingTranscript
    ? lastPaintedTranscriptRef.current
    : liveTranscript;
  if (transcriptForOutlet !== null) {
    lastPaintedTranscriptRef.current = transcriptForOutlet;
  }
  const transcriptOpacity = departing || transcriptGeometryReady ? dockFrame.visuals.transcript : 0;
  const transcriptRise = 8 * (1 - dockFrame.visuals.transcript);

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
    const started = snapshot.statuses.rows.find((row) => row.chatId === chatId)?.startedAt ?? null;
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
    if (session === null || chatId === "") {
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
    if (session === null || session.client.state !== "connected" || chatId === "") {
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

  // `NewThreadTransitionStarted`'s host half: the minted chat exists, the
  // route observation (this navigation) drives the dock —
  // `select_chat`'s commit (shell.rs:1289-1295 just notifies).
  const onNewThreadLaunched = useCallback(
    (mintedId: string) => {
      // §2.3 (composer.rs:6047-6050): the desktop mints the new chat id
      // SCOPED; the web mints raw on the wire (request-routing decodes
      // either form) and scopes HERE, at the navigation — the same call
      // add-space's optimistic space rows make. The merged fleet rows are
      // all scoped, so the URL id then matches `chatPageRow`'s exact
      // compare and the not-found page stays a last resort for genuinely
      // foreign ids.
      const scoped = session === null ? mintedId : encodeScopedId(session.engine.baseUrl, mintedId);
      void navigate({ to: "/chat/$chatId", params: { chatId: scoped } });
    },
    [navigate, session],
  );

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

  if (!snapshot.chats.loaded) {
    return <div className="chat-page" />;
  }
  if (row === undefined && hasSelection) {
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
        The conversation column: the hero, the transcript underlay, the
        spacer, the bottom chrome stack — `render_main`'s `#chat-dropzone`
        children in order. The hero is FIRST and deliberately outside the
        transcript's edge-fade scope (it paints under the overlaid
        titlebar).
      */}
      <div className="chat-column" ref={chatColumnRef}>
        {heroVisible && (
          <NewThreadCanvas
            viewportHeight={viewportHeight}
            heroWidth={heroWidth}
            dissolve={dockFrame.visuals.dissolve}
          />
        )}
        <div
          className="chat-body"
          style={{
            opacity: transcriptOpacity,
            transform: `translateY(${transcriptRise}px)`,
            // `transcript_width`'s retained value (shell.rs:5917-5938's
            // `.w(px(transcript_width))`): while a departing handoff runs,
            // the wrapper is pinned to the SOURCE column's width so the
            // fading rows never reflow into the canvas-wide layout — the
            // exit flash the retention exists to prevent. `inset: 0` plus
            // a width is over-constrained in LTR: left + width win.
            ...(departing && paneHandoffLive ? { width: `${retainedTranscriptWidth}px` } : {}),
          }}
        >
          {transcriptForOutlet !== null && session !== null ? (
            <TranscriptView
              client={session.client}
              docId={chatId}
              deviceId={deviceId}
              store={transcriptForOutlet}
              markdownSurface={markdownSurface}
              onContextUsage={setContextUsage}
              onRetryDelivery={onRetryDelivery}
              onJumpChange={onJumpChange}
              indicator={row?.status ?? "idle"}
              turnStartedAt={turnStartedAt}
              onOpenSubagent={onOpenSubagent}
              deliveryDegraded={deliveryDegraded}
            />
          ) : null}
          {/*
            A departing transcript is visual history, not an active
            interaction surface bound to the newly blank route
            (shell.rs:5940-5944) — and a chat→chat swap's retained rows get
            the same occlusion for the frames they outlive their chat.
          */}
          {(departing || swappingTranscript) && <div className="departing-veil" aria-hidden="true" />}
        </div>
        {/*
          The bottom chrome stack (`render_main`'s flex-none bottom section):
          the reserved status strip (both routes — the canvas shows the idle
          indicator), the persistent composer — one entity, its wrapper in
          this slot, re-anchored by the dock — with the jump pill floating
          over it, the queue edit toolbar, and the terminal drawer LAST
          (`render_terminal_container`, shell.rs:6124 — the dock sits below
          the composer at the column's bottom). The drawer measures into the
          stack like every sibling, so the transcript's bottom clearance and
          fade band track it; its own store is the DRAWER's — the pane's
          Terminal surfaces ride a separate, independent host.
        */}
        <div className="bottom-stack" ref={bottomStackRef}>
          <StatusStrip status={row?.status ?? "idle"} sending={sending} />
          {session !== null && (
            <div
              className="persistent-composer"
              id="persistent-composer"
              ref={wrapperRef}
              style={{
                width: `${composerWidth}px`,
                opacity: `${dockRef.current.opacity()}`,
              }}
            >
              <Composer
                session={session}
                chat={effectiveChat}
                catalog={session.catalog}
                // The LIVE store, never the swap-retained one: the
                // composer's target is the destination chat — the wizard's
                // rows must not offer the previous chat's entries.
                transcript={liveTranscript}
                availableWidth={composerWidth}
                editingMessage={editingRow}
                onEditFinish={onEditFinish}
                onEditCancel={onEditCancel}
                editCommitRef={editCommitRef}
                activateLatestQueued={activateLatestQueued}
                dockFrame={dockFrame}
                onNewThreadLaunched={onNewThreadLaunched}
                dockCorrectionRef={dockCorrectionRef}
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
                  <ComposerFooter chat={effectiveChat} crSummary={crSummary} contextUsage={contextUsage} />
                }
              />
              {hasSelection && <JumpPillAnchor state={jumpState} />}
            </div>
          )}
          <TerminalDock store={drawerTerminalStore} chatId={chatId} />
        </div>
      </div>
    </div>
  );
}

/** The window's inner height — the dock anchors the hero in viewport space. */
function useViewportHeight(): number {
  const [height, setHeight] = useState(() =>
    typeof window === "undefined" ? 800 : window.innerHeight,
  );
  useEffect(() => {
    const onResize = () => setHeight(window.innerHeight);
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);
  return height;
}

/** `prefers-reduced-motion` at first paint. */
function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/**
 * The live edge-bounce offset of a seam's 5px pulse (the `--rb-*-edge-offset`
 * variables `pane-seam.tsx` writes on the document element): the desktop's
 * `sidebar_now()`/`right_now()` include them, so a width sampled mid-bounce
 * carries the painted value.
 */
function readEdgeOffset(varName: string): number {
  if (typeof document === "undefined") {
    return 0;
  }
  const raw = window.getComputedStyle(document.documentElement).getPropertyValue(varName);
  const parsed = Number.parseFloat(raw);
  return Number.isFinite(parsed) ? parsed : 0;
}

/** `sidebar_now()`'s bounce leg — `--rb-sidebar-edge-offset`. */
function readSidebarEdgeBounceOffset(): number {
  return readEdgeOffset("--rb-sidebar-edge-offset");
}

/** `right_now()`'s bounce leg (shell.rs:3822-3825) — `--rb-pane-edge-offset`. */
function readPaneEdgeBounceOffset(): number {
  return readEdgeOffset("--rb-pane-edge-offset");
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
