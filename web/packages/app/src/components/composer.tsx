import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type ClipboardEvent as ReactClipboardEvent,
  type KeyboardEvent as ReactKeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type ReactNode,
} from "react";
import { Icon } from "@roboco/icons";
import type { Chat, HarnessDescriptor, Model } from "@roboco/proto";
import { MESSAGE_QUEUE_ATTACHMENTS_V1, MESSAGE_QUEUE_V1 } from "@roboco/proto";
import type { EngineSession } from "../state/engine-session";
import { useEngineStatus, useNow, useWatchSnapshot } from "../state/hooks";
import { PickerCatalog } from "../state/picker-catalog";
import { ESCAPE_PRIORITY, registerEscapeSurface } from "../state/escape";
import { effectiveIndicator } from "../lib/view";
import { chatDrafts, composerDefaults, draftFromChat, rememberedModelFor } from "../lib/composer-draft";
import { offeredHarnesses } from "../lib/model-rows";
import { clampReasoning } from "../lib/traits-summary";
import {
  ATTACHMENT_ONLY_TEXT,
  formatByName,
  formatToMime,
  stageFile,
  uploadAttachments,
  type StagedAttachment,
} from "../lib/attachments";
import {
  describeSendError,
  mintMessageId,
  persistChatConfig,
  queueMessage,
  sendInterrupt,
  sendRun,
  type DraftConfig,
} from "../lib/composer-actions";
import {
  ACTIONS_ROW_HEIGHT,
  attachmentStripHeight,
  COMPOSER_MAX_WIDTH,
  COMPOSER_WIDTH_EPSILON,
  COMPACT_TOTAL_HEIGHT,
  composerFlip,
  composerTotalHeight,
  composerWidthChanged,
  flipMorphDone,
  flipMorphHeight,
  flipMorphProgress,
  flipMorphStep,
  inputDragScrollDelta,
  inputOverflowEdges,
  INPUT_LINE_HEIGHT,
  morphClusterDy,
  morphClusterInset,
  morphTextPad,
  collapseTextGlide,
  PILL_BORDER_V,
  RESIZE_SETTLE_MS,
  ROUTE_SNAP_MS,
  TEXTAREA_PAD_V,
  type FlipMorph,
} from "../lib/composer-flip";
import {
  beginInterrupt,
  composerHasContent,
  messageEnterBindings,
  modifiedSubmitTarget,
  platformModifierCombo,
  retainLiveInterrupts,
  sendBlocked,
  sendButtonMode,
  shouldPublishOptimisticEcho,
} from "../lib/composer-send";
import { echoStore } from "../state/transcript-store";
import { useUiSettings } from "../state/ui-settings";
import { isMacPlatform } from "../state/shortcuts";
import { seedAttachment } from "../state/attachment-cache";
import { ComposerPickers } from "./composer-pickers";
import { AttachmentStrip } from "./attachments/attachment-strip";

/**
 * The composer — the desktop's `crates/ui/src/composer.rs` ported to React:
 * the centred 768px column (failure notice, queue-degraded caption, the
 * queue tray tucked behind the pill, the 26px-radius pill itself, and the
 * 24px session-footer slot), the width-driven compact↔expanded flip with
 * hysteresis and a 180ms height morph (text glides, controls stay pinned to
 * the stationary bottom edge), auto-grow that clamps the textarea BOX to
 * 76–260 and the PILL to 124–308, and the Send / Queue / Stop send path
 * (never "Steer" — spec decision 3: a busy chat gets `QueueMessage` with
 * `holdForTurnEnd: true`).
 */

/** The failure notice; `key` scopes it to one chat (null = global). */
interface FailureNotice {
  readonly message: string;
  readonly key: string | null;
}

/** The animated geometry one layout pass resolves (see the evaluate pass). */
interface PillLayout {
  readonly pillHeight: number;
  readonly boxHeight: number;
  readonly textPad: number;
  readonly clusterInset: number;
  readonly clusterDy: number;
  readonly textGlide: number;
  readonly morphing: boolean;
}

const REST_LAYOUT: PillLayout = {
  pillHeight: COMPACT_TOTAL_HEIGHT,
  boxHeight: COMPACT_TOTAL_HEIGHT - PILL_BORDER_V,
  textPad: 12,
  clusterInset: 8,
  clusterDy: 0,
  textGlide: 0,
  morphing: false,
};

interface ComposerProps {
  readonly session: EngineSession;
  readonly chat: Chat;
  readonly catalog: PickerCatalog;
  /**
   * The measured conversation-column width, clamped to 768 — the desktop's
   * `set_available_width` feed. Null before the first measurement.
   */
  readonly availableWidth: number | null;
  /**
   * The queue panel (ticket 16 owns the body), rendered in the column's
   * tray slot — tucked 18px behind the pill per `QUEUE_COMPOSER_OVERLAP`.
   */
  readonly queueSlot?: ReactNode;
  /** The session footer row (the 24px slot under the pill). */
  readonly footerSlot?: ReactNode;
  /**
   * The queued row currently being edited in this composer — `null` when
   * the composer is free. When set, the textarea seeds with the row's text
   * and a submit commits the row through `onEditFinish` (the lease
   * protocol itself is ticket 16's).
   */
  readonly editingMessage?: { id: string; text: string } | null;
  readonly onEditFinish?: (outcome: { action: "commit" | "cancel" | "releaseUnchanged"; text: string }) => void;
  /** Escape while editing a queued row (the container binding, composer.rs:7473). */
  readonly onEditCancel?: () => void;
  /**
   * Mod+Enter with a truly empty composer activates the most recently
   * queued row (composer.rs:6023). The action itself lives with the queue
   * store (ticket 16); this ticket wires the call site only.
   */
  readonly activateLatestQueued?: () => void;
}

export function Composer({
  session,
  chat,
  catalog,
  availableWidth,
  queueSlot,
  footerSlot,
  editingMessage,
  onEditFinish,
  onEditCancel,
  activateLatestQueued,
}: ComposerProps) {
  const snapshot = useWatchSnapshot(session);
  const engineStatus = useEngineStatus(session);
  const now = useNow(10_000);
  // `ComposerSendBehavior` — which Enter submits. Default "enter": bare
  // Enter sends, Mod+Enter is `ModifiedSubmit`.
  const sendBehavior = useUiSettings().composerSendBehavior;
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  // The text-width mirror: a hidden `white-space: pre` twin whose offsetWidth
  // is the unwrapped width of the widest line — the desktop's
  // `measured_text_width` (composer.rs:1996). Measuring the TEXT WIDTH (never
  // the post-flip scrollHeight, which differs per mode and would feed back
  // into the decision) is what makes the flip layout-stable.
  const measureRef = useRef<HTMLDivElement | null>(null);
  // The paperclip lives in the actions cluster (composer.rs), so the strip
  // hands its picker up here rather than drawing its own attach button.
  const attachRef = useRef<(() => void) | null>(null);
  const lastChatIdRef = useRef(chat.id);
  // Focus returns to the draft after the native file dialog closes (both
  // Attach and Cancel — the web's cancelled input fires no event, so the
  // window regaining focus is the signal, composer.rs::open_file_picker).
  const focusPendingRef = useRef(false);
  // Interrupts in flight, idempotent per chat (composer.rs:6707-6741).
  const interruptingRef = useRef<Set<string>>(new Set());
  // The pickers' open state — the pill's mouse-down focus defers to open
  // menus (composer.rs:7701-7709).
  const [pickersOpen, setPickersOpen] = useState(false);

  // Live status: the desktop's `run_live` is Working OR AwaitingInput.
  const statusRow = snapshot?.statuses.rows.find((row) => row.chatId === chat.id);
  const indicator = effectiveIndicator(statusRow, now);
  const runLive = indicator === "working" || indicator === "awaitingInput";

  // The catalog re-renders this component too (the draft-seeding effects
  // read live lists; the pickers child subscribes on its own).
  const harnesses = useSyncExternalStore(
    useCallback((listener: () => void) => catalog.subscribe(listener), [catalog]),
    useCallback(() => catalog.getHarnesses(), [catalog]),
    useCallback(() => catalog.getHarnesses(), [catalog]),
  );

  const [text, setText] = useState(() => chatDrafts.get(chat.id));
  // The flip decision reads the live text through a ref (the evaluate pass
  // stays identity-stable so the ResizeObserver never re-binds).
  const textRef = useRef(text);
  textRef.current = text;
  const [draft, setDraft] = useState<DraftConfig>(() =>
    draftFromChat(chat, harnesses.rows, catalog.getModels(chat.config?.harness ?? "claude-code").rows),
  );
  const models = useSyncExternalStore(
    useCallback((listener: () => void) => catalog.subscribeModels(draft.harness, listener), [catalog, draft.harness]),
    useCallback(() => catalog.getModels(draft.harness), [catalog, draft.harness]),
    useCallback(() => catalog.getModels(draft.harness), [catalog, draft.harness]),
  );
  const [expanded, setExpanded] = useState(false);
  const [busy, setBusy] = useState(false);
  // Staged attachments per chat id — survives a chat switch (the strip
  // moves with the chat). Empty for a chat the user has never staged on.
  const [stagedByChat, setStagedByChat] = useState<Record<string, readonly StagedAttachment[]>>({});
  // Whole-send upload progress (0..1) for the strip's progress bar. Null
  // when nothing is uploading.
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);
  // The failure notice (composer.rs:7309-7411). Chat-scoped failures
  // survive navigation and only render under their own chat.
  const [failure, setFailure] = useState<FailureNotice | null>(null);
  const staged = stagedByChat[chat.id] ?? [];

  // ── Per-chat drafts (composer.rs `drafts: HashMap<chat_key, String>`) ──
  // Swap on navigation: save the outgoing chat's text, load the incoming
  // one's. The route snap armed here keeps the first flip after a switch
  // un-animated (composer.rs:7290, ROUTE_SNAP_MS).
  const routeSnapUntilRef = useRef<number | null>(null);
  useEffect(() => {
    if (lastChatIdRef.current === chat.id) {
      return;
    }
    chatDrafts.set(lastChatIdRef.current, textRef.current);
    lastChatIdRef.current = chat.id;
    // A programmatic draft swap is a new document: the full value assignment
    // resets the browser's own undo stack (the desktop's `set_text` clears
    // its stacks — the accepted undo-coalescing divergence).
    setText(chatDrafts.get(chat.id));
    setExpanded(false);
    setUploadProgress(null);
    setFailure(null);
    routeSnapUntilRef.current = performance.now() + ROUTE_SNAP_MS;
  }, [chat.id]);

  // When the edit row changes (the chat page started/cancelled editing a
  // queued row), seed the textarea with the row's text so the user can type
  // a replacement; the pre-edit draft is restored when the lease closes
  // (composer.rs `clear_queue_edit_local`'s `queue_edit_draft` hand-back).
  const lastEditingIdRef = useRef<string | null>(null);
  const preEditTextRef = useRef<string | null>(null);
  useEffect(() => {
    const id = editingMessage?.id ?? null;
    if (id === lastEditingIdRef.current) {
      return;
    }
    lastEditingIdRef.current = id;
    if (editingMessage !== null && editingMessage !== undefined) {
      preEditTextRef.current = textRef.current;
      setText(editingMessage.text);
    } else if (preEditTextRef.current !== null) {
      setText(preEditTextRef.current);
      preEditTextRef.current = null;
    }
  }, [editingMessage]);

  // Reconcile the draft with chat.config + the loaded catalog (locked once
  // persisted; sticky defaults otherwise — pickers.rs:713-796).
  useEffect(() => {
    const persisted = chat.config;
    if (persisted !== null) {
      setDraft((current) =>
        current.harness === persisted.harness &&
        current.model === persisted.model &&
        current.reasoning === persisted.reasoning &&
        current.sandbox === persisted.sandbox
          ? current
          : {
              harness: persisted.harness,
              model: persisted.model,
              reasoning: persisted.reasoning,
              sandbox: persisted.sandbox,
              modelOptions: { ...(persisted.modelOptions ?? {}) },
            },
      );
      return;
    }
    if (harnesses.rows.length === 0) {
      return;
    }
    setDraft((current) => {
      if (harnesses.rows.some((h: HarnessDescriptor) => h.id === current.harness)) {
        return current;
      }
      const remembered = composerDefaults.getSnapshot().harness;
      const offered = offeredHarnesses(harnesses.rows);
      const next =
        remembered !== null && harnesses.rows.some((h: HarnessDescriptor) => h.id === remembered)
          ? remembered
          : offered[0]?.id ?? harnesses.rows[0]?.id;
      if (next === undefined) {
        return current;
      }
      return {
        harness: next,
        model: null,
        reasoning: null,
        sandbox: "workspace-write",
        modelOptions: {},
      };
    });
  }, [chat.config, harnesses.rows]);

  // Once a harness is picked, ensure the model catalog is loaded and seed
  // the draft with the remembered model (pickers.rs:748-796).
  useEffect(() => {
    if (!harnesses.loaded) {
      return;
    }
    if (harnesses.rows.length === 0) {
      return;
    }
    void catalog.loadModels(draft.harness);
  }, [catalog, draft.harness, harnesses.loaded, harnesses.rows.length]);

  useEffect(() => {
    if (models.rows.length === 0) {
      return;
    }
    setDraft((current) => {
      if (current.model !== null && models.rows.some((m: Model) => m.id === current.model)) {
        const clamped = clampReasoning(current.reasoning, current.model === null ? [] : ladderFor(models.rows, current.model));
        return clamped === current.reasoning
          ? current
          : { ...current, reasoning: clamped };
      }
      const remembered = rememberedModelFor(current.harness);
      const seeded =
        remembered !== null && models.rows.some((m: Model) => m.id === remembered.id)
          ? remembered.id
          : models.rows[0]?.id;
      if (seeded === undefined) {
        return current;
      }
      const model = models.rows.find((m: Model) => m.id === seeded);
      return {
        ...current,
        model: seeded,
        reasoning: clampReasoning(current.reasoning, model?.reasoningLevels ?? []),
      };
    });
  }, [models.rows]);

  // ── The width-driven flip + height morph ───────────────────────────────
  //
  // One layout pass per effect run / textarea resize: measure the unwrapped
  // text width against the compact-mode wrap capacity (learned while
  // compact, shifted by the container delta while expanded — never the
  // post-flip measured width), decide the flip, then advance the pill's
  // height toward the live target through the morph state machine. The
  // rAF loop re-runs the pass while a morph is in flight.
  const [layout, setLayout] = useState<PillLayout>(REST_LAYOUT);
  const [tick, setTick] = useState(0);
  const [reducedMotion, setReducedMotion] = useState(prefersReducedMotion);
  useEffect(() => {
    const query = window.matchMedia("(prefers-reduced-motion: reduce)");
    const onChange = () => setReducedMotion(query.matches);
    onChange();
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);

  const epochRef = useRef(0);
  const flipEpochRef = useRef(0);
  const compactCapacityRef = useRef(0);
  const expandedAnchorRef = useRef(0);
  const lastSeenWidthRef = useRef(0);
  const widthChangedAtRef = useRef<number | null>(null);
  const settleTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const heightMorphRef = useRef<FlipMorph | null>(null);
  const flipMorphRef = useRef<FlipMorph | null>(null);
  const lastTargetRef = useRef(0);
  const lastRenderedRef = useRef(0);
  const rafRef = useRef<number | null>(null);
  const availableWidthRef = useRef<number | null>(null);
  const evaluateRef = useRef<() => void>(NOOP);
  const expandedRef = useRef(expanded);
  expandedRef.current = expanded;
  const stagedCountRef = useRef(staged.length);
  stagedCountRef.current = staged.length;
  const reducedMotionRef = useRef(reducedMotion);
  reducedMotionRef.current = reducedMotion;
  const availableWidthRef2 = useRef(availableWidth);
  availableWidthRef2.current = availableWidth;

  evaluateRef.current = () => {
    const el = textareaRef.current;
    const mirror = measureRef.current;
    if (el === null || mirror === null) {
      return;
    }
    const nowMs = performance.now();
    epochRef.current += 1;
    const epoch = epochRef.current;
    // Content measurement: the textarea carries no vertical padding of its
    // own (the box does), so an `auto` height reads the wrapped content.
    el.style.height = "auto";
    const wrappedLines = Math.max(1, Math.round(el.scrollHeight / INPUT_LINE_HEIGHT));
    const contentHeight = wrappedLines * INPUT_LINE_HEIGHT;
    const textWidth = mirror.offsetWidth;
    const hasNewline = textRef.current.includes("\n");
    const lastWidth = el.offsetWidth;
    // Only measurements taken *after* the last flip may drive the next one
    // (at most one flip per layout pass — a flip invalidates the widths).
    const measured = epoch > flipEpochRef.current && lastWidth > 0;
    if (measured) {
      // A same-mode width change is an interactive window/pane resize:
      // defer collapse until sizes settle. The last-seen reset on a
      // committed flip means the mode change's width jump is NOT read as
      // one.
      if (
        lastSeenWidthRef.current > 0 &&
        Math.abs(lastWidth - lastSeenWidthRef.current) > COMPOSER_WIDTH_EPSILON
      ) {
        widthChangedAtRef.current = nowMs;
      }
      lastSeenWidthRef.current = lastWidth;
      if (expandedRef.current) {
        if (expandedAnchorRef.current <= 0) {
          expandedAnchorRef.current = lastWidth;
        }
      } else {
        // The compact pill's content box is the layout-stable capacity
        // both thresholds measure against (composer.rs:7229-7232; the
        // web's textarea content width already excludes its paddings, so
        // no extra inset is subtracted).
        compactCapacityRef.current = lastWidth;
      }
    }
    const resizing =
      widthChangedAtRef.current !== null && nowMs - widthChangedAtRef.current < RESIZE_SETTLE_MS;
    if (resizing && settleTimerRef.current === null) {
      // Re-evaluate once the settle window has passed (composer.rs:7237).
      settleTimerRef.current = setTimeout(() => {
        settleTimerRef.current = null;
        evaluateRef.current();
      }, RESIZE_SETTLE_MS + 20);
    }
    // Layout-stable compact capacity: measured directly while compact;
    // while expanded, the learned value shifted by the container resize.
    const capacity = !expandedRef.current
      ? lastWidth > 0
        ? lastWidth
        : Number.POSITIVE_INFINITY
      : compactCapacityRef.current > 0
        ? expandedAnchorRef.current > 0 && lastWidth > 0
          ? compactCapacityRef.current + (lastWidth - expandedAnchorRef.current)
          : compactCapacityRef.current
        : Number.POSITIVE_INFINITY;
    const nextMode = composerFlip(expandedRef.current, textWidth, capacity, hasNewline, resizing);
    const committed = nextMode !== expandedRef.current && measured;
    const mode = committed ? nextMode : expandedRef.current;
    if (committed) {
      flipEpochRef.current = epoch;
      expandedAnchorRef.current = 0;
      lastSeenWidthRef.current = 0;
      setExpanded(nextMode);
    }
    // `strip_width_hint` (composer.rs:7511): the pill's content width, in
    // both modes.
    const stripWidthHint = (availableWidthRef2.current ?? COMPOSER_MAX_WIDTH) - 2 * 16 - 2;
    const stripH = attachmentStripHeight(stagedCountRef.current, stripWidthHint);
    const baseHeight = mode ? composerTotalHeight(contentHeight) : COMPACT_TOTAL_HEIGHT;
    const target = baseHeight + stripH;
    const routeSnap = routeSnapUntilRef.current !== null && nowMs < routeSnapUntilRef.current;
    // Two morphs, as on the desktop: the HEIGHT morph animates the pill
    // toward the live target (auto-grow retargets mid-flight), the FLIP
    // morph drives the inner geometry handoff (paddings, insets, glide).
    heightMorphRef.current = flipMorphStep(
      heightMorphRef.current,
      Math.abs(target - lastTargetRef.current) > 0.5,
      lastRenderedRef.current,
      nowMs,
      reducedMotionRef.current,
      routeSnap,
    );
    flipMorphRef.current = flipMorphStep(
      flipMorphRef.current,
      committed,
      lastRenderedRef.current,
      nowMs,
      reducedMotionRef.current,
      routeSnap,
    );
    lastTargetRef.current = target;
    const heightMorph = heightMorphRef.current;
    const pillHeight = heightMorph !== null ? flipMorphHeight(heightMorph, target, nowMs) : target;
    const flipMorph = flipMorphRef.current;
    const morphT =
      flipMorph !== null && !flipMorphDone(flipMorph, nowMs) ? flipMorphProgress(flipMorph, nowMs) : 1;
    lastRenderedRef.current = pillHeight;
    // The expanded textarea box follows the animated pill height; the
    // textarea itself fills the box less its paddings (composer.rs:7606).
    const boxHeight = Math.max(pillHeight - stripH - PILL_BORDER_V - ACTIONS_ROW_HEIGHT, 0);
    const textPad = morphTextPad(morphT);
    const inputHeight = mode ? Math.max(boxHeight - textPad - 4, 0) : INPUT_LINE_HEIGHT;
    el.style.height = `${inputHeight}px`;
    el.style.overflowY = mode && contentHeight > inputHeight ? "auto" : "hidden";
    // The scroll fade mask: only SETTLED overflow at an edge gets the ramp —
    // the settled viewport is the committed target's, not the animating
    // box's (`input_overflow_edges`, composer.rs:181-192).
    const settledViewport = Math.max(baseHeight - PILL_BORDER_V - ACTIONS_ROW_HEIGHT - TEXTAREA_PAD_V, 0);
    const [fadeTop, fadeBottom] = inputOverflowEdges(contentHeight, settledViewport, inputHeight, el.scrollTop);
    el.dataset["fadeTop"] = mode && fadeTop ? "true" : "false";
    el.dataset["fadeBottom"] = mode && fadeBottom ? "true" : "false";
    const morphing =
      (heightMorph !== null && !flipMorphDone(heightMorph, nowMs)) ||
      (flipMorph !== null && !flipMorphDone(flipMorph, nowMs));
    setLayout({
      pillHeight,
      boxHeight,
      textPad,
      clusterInset: morphClusterInset(mode, morphT),
      clusterDy: morphClusterDy(morphT),
      // Collapse-morph text glide: the decaying offset walks the compact
      // text down from its expanded resting place (composer.rs:7793-7800).
      textGlide:
        !mode && flipMorph !== null && !flipMorphDone(flipMorph, nowMs)
          ? collapseTextGlide(flipMorph.from, morphT)
          : 0,
      morphing,
    });
  };

  useLayoutEffect(() => {
    evaluateRef.current();
  }, [text, expanded, availableWidth, staged.length, tick]);

  // The rAF loop: keep frames coming while a morph is in flight (the
  // desktop's `window.request_animation_frame`, shell.rs `motion_active`).
  useEffect(() => {
    if (!layout.morphing) {
      return;
    }
    rafRef.current = requestAnimationFrame(() => setTick((value) => value + 1));
    return () => {
      if (rafRef.current !== null) {
        cancelAnimationFrame(rafRef.current);
        rafRef.current = null;
      }
    };
  }, [layout.morphing, tick]);

  // The desktop re-evaluates per layout pass — on the web only a
  // conversation-column resize moves the textarea's width mid-keystroke.
  useEffect(() => {
    const el = textareaRef.current;
    if (el === null) {
      return;
    }
    const observer = new ResizeObserver(() => evaluateRef.current());
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // The shell's available-width feed (composer.rs::set_available_width):
  // only an epsilon-exceeding move of the CLAMPED width re-evaluates.
  useEffect(() => {
    const width = Math.min(Math.max(availableWidth ?? 0, 0), COMPOSER_MAX_WIDTH);
    if (!composerWidthChanged(availableWidthRef.current, width)) {
      return;
    }
    availableWidthRef.current = width;
    evaluateRef.current();
  }, [availableWidth]);

  useEffect(
    () => () => {
      if (settleTimerRef.current !== null) {
        clearTimeout(settleTimerRef.current);
      }
      if (rafRef.current !== null) {
        cancelAnimationFrame(rafRef.current);
      }
    },
    [],
  );

  // ── Drag-selection autoscroll (composer.rs:270-284) ────────────────────
  // A native textarea does not autoscroll on drag past its edge: drive
  // `el.scrollTop` from a pointermove listener while the primary button is
  // down, at the 16ms cadence, by the edge-proportional capped delta.
  useEffect(() => {
    const el = textareaRef.current;
    if (el === null) {
      return;
    }
    let timer: ReturnType<typeof setInterval> | null = null;
    const onPointerMove = (event: PointerEvent): void => {
      if (event.buttons !== 1) {
        if (timer !== null) {
          clearInterval(timer);
          timer = null;
        }
        return;
      }
      const bounds = el.getBoundingClientRect();
      if (event.clientY >= bounds.top && event.clientY <= bounds.bottom) {
        if (timer !== null) {
          clearInterval(timer);
          timer = null;
        }
        return;
      }
      const delta = inputDragScrollDelta(event.clientY, bounds.top, bounds.bottom, INPUT_LINE_HEIGHT);
      if (timer !== null || delta === 0) {
        return;
      }
      timer = setInterval(() => {
        el.scrollTop = Math.min(Math.max(el.scrollTop - delta, 0), el.scrollHeight);
      }, 16);
    };
    const stop = (): void => {
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    };
    el.addEventListener("pointermove", onPointerMove);
    el.addEventListener("pointerup", stop);
    el.addEventListener("pointercancel", stop);
    return () => {
      stop();
      el.removeEventListener("pointermove", onPointerMove);
      el.removeEventListener("pointerup", stop);
      el.removeEventListener("pointercancel", stop);
    };
  }, []);

  // ── Interrupt release (composer.rs:5771-5781) ──────────────────────────
  // The pending set releases only when the chat settles.
  useEffect(() => {
    retainLiveInterrupts(interruptingRef.current, (chatId) => {
      const row = snapshot?.statuses.rows.find((entry) => entry.chatId === chatId);
      const live = effectiveIndicator(row, now);
      return live === "working" || live === "awaitingInput";
    });
  }, [snapshot, now]);

  const applyDraft = useCallback((next: DraftConfig) => {
    setDraft(next);
  }, []);

  // The composer's mid-session model / reasoning / options changes persist
  // through `Mutate setChatConfig` (pickers.rs:1474) — a picker change, never
  // a send.
  const persistDraft = useCallback(
    (next: DraftConfig) => {
      void persistChatConfig(session.client, chat.id, next).catch((error: unknown) => {
        setFailure({ message: describeSendError(error), key: chat.id });
      });
    },
    [session.client, chat.id],
  );

  // ── The queue capability gate (composer.rs:6096-6110) ──────────────────
  // MESSAGE_QUEUE_V1 (and MESSAGE_QUEUE_ATTACHMENTS_V1 when the send
  // carries attachments) checked on the engine before taking the draft. On
  // failure, do not send: raise the verbatim notice and leave the draft.
  // (The desktop also checks the chat's HOST device; the web's engine-local
  // pairing has no host registry yet — the engine check stands in until
  // ticket 31's fleet.)
  const engineSupports = useCallback(
    (capability: string): boolean => {
      const capabilities = session.client.engineInfo?.capabilities ?? [];
      return capabilities.includes(capability);
    },
    [session.client],
  );

  // ── Interrupt (`interrupt_selected`, composer.rs:6707-6737) ────────────
  const interrupt = useCallback(async (): Promise<void> => {
    if (!beginInterrupt(interruptingRef.current, chat.id)) {
      return;
    }
    try {
      await sendInterrupt(session.client, chat.id);
    } catch (error) {
      interruptingRef.current.delete(chat.id);
      setFailure({ message: `Stop failed: ${describeSendError(error)}`, key: chat.id });
    }
  }, [chat.id, session.client]);

  // ── The send path (`Composer::send`, composer.rs:6032-6705) ────────────
  const send = useCallback(
    async (typed: string, queue: boolean): Promise<void> => {
      // Existing busy chats always queue; compatibility was checked before
      // taking the draft (the capability gate below).
      if (queue) {
        const capability = staged.length > 0 ? MESSAGE_QUEUE_ATTACHMENTS_V1 : MESSAGE_QUEUE_V1;
        if (!engineSupports(capability)) {
          setFailure({
            message: "Update the chat's engine to queue messages during a response.",
            key: chat.id,
          });
          return;
        }
      }
      const trimmed = typed.trim();
      if (chat.cwd === null || chat.cwd === undefined || chat.cwd.trim().length === 0) {
        setFailure({ message: "This chat has no working directory yet — pick a space first.", key: chat.id });
        return;
      }
      // Snapshot-and-clear NOW (`takeAttachments`): the strip empties the
      // instant you hit send; a failure hands the files back by id.
      const taken = staged;
      setStagedByChat((current) => {
        const next = { ...current };
        delete next[chat.id];
        return next;
      });
      // `typed` keeps the user's own words for the failure hand-back below
      // (restoring a folded prompt would paste the trailer as literal text).
      const messageId = mintMessageId();
      // The optimistic echo goes up BEFORE the wire call — gated off for a
      // queued send, whose queue row IS its representation until dispatch
      // (`should_publish_optimistic_echo`).
      if (shouldPublishOptimisticEcho(queue)) {
        echoStore.pushEcho({
          messageId,
          chatId: chat.id,
          startedAtMs: Date.now(),
          text: trimmed,
          attachmentPaths: [],
        });
      }
      setText("");
      chatDrafts.clear(chat.id);
      setFailure(null);
      setBusy(true);
      setUploadProgress(taken.length > 0 ? 0 : null);
      const onProgress = (uploaded: number, total: number): void => {
        setUploadProgress(total <= 0 ? 1 : uploaded / total);
      };
      try {
        if (queue) {
          // Queue rows keep a clean body (the host rebuilds the attachment
          // transport when it promotes the row); the bytes upload first on
          // the web's legacy blocking path.
          const uploaded = taken.length > 0 ? await uploadAttachments(session.client, taken, onProgress) : [];
          const body = trimmed.length > 0 ? trimmed : ATTACHMENT_ONLY_TEXT;
          await queueMessage(
            session.client,
            chat.id,
            body,
            uploaded.map((entry) => entry.path),
          );
        } else {
          const sendResult = await sendRun(
            session.client,
            chat.id,
            draft,
            trimmed,
            chat.cwd,
            { mintMessageId: () => messageId },
            taken.length > 0 ? { stagedAttachments: taken, uploadProgress: onProgress } : {},
          );
          // Refresh the echo in place with the attachment-folded prompt so
          // its state never flickers (composer.rs:6401-6423).
          if (echoStore.get(messageId) !== null) {
            echoStore.removeEcho(messageId);
            echoStore.pushEcho({
              messageId,
              chatId: chat.id,
              startedAtMs: Date.now(),
              text: sendResult.finalPrompt,
              attachmentPaths: [...sendResult.attachmentPaths],
            });
          }
          // Seed the attachment cache so the just-sent bubble renders from
          // local bytes without a ReadAttachmentChunk round-trip.
          const deviceId = session.client.engineInfo?.deviceId ?? null;
          if (deviceId !== null) {
            taken.forEach((att, ix) => {
              const path = sendResult.attachmentPaths[ix];
              if (path === undefined) {
                return;
              }
              seedAttachment(deviceId, path, {
                name: att.name,
                mime: formatToMime(att.format),
                bytes: att.bytes,
              });
            });
          }
        }
        setUploadProgress(null);
      } catch (error) {
        // Failure: red notice, echo removed, prompt back in the draft,
        // staged files back in the stash (merged by id so anything staged
        // during the send survives).
        echoStore.removeEcho(messageId);
        setText(typed);
        chatDrafts.set(chat.id, typed);
        setStagedByChat((current) => {
          const fresh = current[chat.id] ?? [];
          const merged = [...taken.filter((att) => !fresh.some((f) => f.id === att.id)), ...fresh];
          const next = { ...current };
          if (merged.length === 0) {
            delete next[chat.id];
          } else {
            next[chat.id] = merged;
          }
          return next;
        });
        setFailure({ message: `Send failed: ${describeSendError(error)}`, key: chat.id });
        setUploadProgress(null);
      } finally {
        setBusy(false);
      }
    },
    [chat.id, chat.cwd, draft, session.client, staged, engineSupports],
  );

  // ── The submit dispatch (`on_submit`, composer.rs:5980-6007) ───────────
  const submit = useCallback(async () => {
    if (busy) {
      return;
    }
    // A queued-row edit: the submit commits the row through the lease and
    // is DONE — it never also fires a new message (`commit_queue_edit`
    // returns "handled").
    const editing = editingMessage ?? null;
    if (editing !== null) {
      const trimmed = text.trim();
      if (trimmed.length === 0) {
        onEditFinish?.({ action: "releaseUnchanged", text: trimmed });
        return;
      }
      const textChanged = trimmed !== (editing.text ?? "").trim();
      onEditFinish?.(
        textChanged ? { action: "commit", text: trimmed } : { action: "releaseUnchanged", text: trimmed },
      );
      return;
    }

    const content = composerHasContent(text, staged.length, 0);
    const mode = sendButtonMode(runLive, content);
    if (mode === "stop") {
      void interrupt();
      return;
    }
    if (!content) {
      return;
    }
    if (
      sendBlocked({
        queueEditFinishing: busy,
        requestTargetDisconnected: session.client.state !== "connected",
        reviewCommentFlushPending: false,
        newChatNoAgents: false,
      })
    ) {
      // A blocked send is a no-op — no failure, no wire call
      // (composer.rs:6002, `_ if self.send_blocked(cx) => {}`).
      return;
    }
    await send(text, mode === "queue");
  }, [busy, text, staged, runLive, editingMessage, onEditFinish, session.client, interrupt, send]);

  // ── Enter policy (`message_enter_bindings`, composer.rs:1347-1390) ─────
  // Exactly two bindings; Shift+Enter is always a native newline. While an
  // IME composition is active, Enter is never a submit.
  const modifierCombo = platformModifierCombo(isMacPlatform());
  const bindings = useMemo(
    () => messageEnterBindings(sendBehavior, modifierCombo),
    [sendBehavior, modifierCombo],
  );
  const onKeyDown = (event: ReactKeyboardEvent<HTMLTextAreaElement>): void => {
    if (event.nativeEvent.isComposing) {
      return;
    }
    if (event.key !== "Enter") {
      return;
    }
    const mod = event.metaKey || event.ctrlKey;
    const bareEnter = !mod && !event.altKey && !event.shiftKey;
    if (mod && !event.altKey) {
      // Mod+Enter: `ModifiedSubmit` — submits content, activates the most
      // recently queued row on a truly empty composer, never Stop.
      event.preventDefault();
      const content = composerHasContent(text, staged.length, 0);
      if (modifiedSubmitTarget(content) === "submitContent") {
        void submit();
      } else {
        activateLatestQueued?.();
      }
      return;
    }
    if (
      bareEnter &&
      bindings.some((binding) => binding.keystroke === "enter" && binding.action === "submit")
    ) {
      event.preventDefault();
      void submit();
    }
    // Everything else — Shift+Enter, Alt+Enter, bare Enter under
    // "modEnter" — is a newline, native.
  };

  // Escape while editing a queued row cancels the edit (the container
  // binding, composer.rs:7473-7483): registered on the shell's escape
  // ladder, above the desktop's shell surfaces and the bubble-phase
  // interrupt, so the key is consumed once.
  useEffect(() => {
    if (editingMessage === null || editingMessage === undefined) {
      return;
    }
    return registerEscapeSurface(ESCAPE_PRIORITY.composerQueueEdit, () => {
      onEditCancel?.();
      return true;
    });
  }, [editingMessage, onEditCancel]);

  // ── Paste of image data (composer.rs's clipboard) ──────────────────────
  // Clipboard images beat text and stage as attachments; non-image files
  // are skipped silently; only a clipboard with no files falls through to
  // the native text paste.
  const onPaste = useCallback(
    (event: ReactClipboardEvent<HTMLTextAreaElement>): void => {
      const items = event.clipboardData?.items;
      if (items === undefined) {
        return;
      }
      const files: File[] = [];
      for (const item of Array.from(items)) {
        if (item.kind !== "file") {
          continue;
        }
        const file = item.getAsFile();
        if (file !== null) {
          files.push(file);
        }
      }
      if (files.length === 0) {
        return;
      }
      event.preventDefault();
      void (async () => {
        const stagedNext: StagedAttachment[] = [];
        for (const file of files) {
          if (formatByName(file.name) === null && !file.type.startsWith("image/")) {
            // Non-image files are skipped silently.
            continue;
          }
          try {
            stagedNext.push(await stageFile(file));
          } catch {
            // Undecodable bytes are skipped just as silently.
          }
        }
        if (stagedNext.length > 0) {
          setStagedByChat((current) => ({
            ...current,
            [chat.id]: [...(current[chat.id] ?? []), ...stagedNext],
          }));
        }
      })();
    },
    [chat.id],
  );

  // ── The send button (§2.11) ─────────────────────────────────────────────
  const hasContent = composerHasContent(text, staged.length, 0);
  const editingActive = editingMessage !== null && editingMessage !== undefined;
  const mode: "send" | "queue" | "stop" = editingActive
    ? "send"
    : sendButtonMode(runLive, hasContent);
  const blocked =
    mode !== "stop" &&
    sendBlocked({
      queueEditFinishing: busy,
      requestTargetDisconnected: session.client.state !== "connected",
      reviewCommentFlushPending: false,
      newChatNoAgents: false,
    });

  // ── The queue-degraded caption (§2.3) ───────────────────────────────────
  // The web has no WatchConnectivity stream yet (research 14 §5); the
  // engine's connection state stands in. It clears itself the moment the
  // path heals.
  const engineState = engineStatus?.state ?? "connecting";
  const queueDegraded = engineState !== "connected";
  const queueOffline = engineState !== "reconnecting";
  const queueNotice = queueDegraded
    ? queueOffline
      ? "Offline — messages will send when you're back online."
      : "Messages will send once the connection recovers."
    : null;

  // The chat-scoped failure filter (composer.rs:7311-7315).
  const failureVisible =
    failure !== null && (failure.key === null || failure.key === chat.id) ? failure.message : null;

  const onStage = useCallback(
    (next: readonly StagedAttachment[]) => {
      setStagedByChat((current) => ({
        ...current,
        [chat.id]: [...(current[chat.id] ?? []), ...next],
      }));
    },
    [chat.id],
  );
  const onRemove = useCallback(
    (id: string) => {
      setStagedByChat((current) => {
        const list = current[chat.id] ?? [];
        const next = list.filter((att) => att.id !== id);
        const merged = { ...current };
        if (next.length === 0) {
          delete merged[chat.id];
        } else {
          merged[chat.id] = next;
        }
        return merged;
      });
    },
    [chat.id],
  );
  const onStageError = useCallback((message: string) => {
    setFailure({ message, key: null });
  }, []);

  // The attach button drives the strip's hidden input; focus returns to the
  // draft when the dialog closes (both pick and cancel).
  const onAttachClick = useCallback(() => {
    focusPendingRef.current = true;
    attachRef.current?.();
  }, []);
  useEffect(() => {
    const onWindowFocus = (): void => {
      if (focusPendingRef.current) {
        focusPendingRef.current = false;
        textareaRef.current?.focus();
      }
    };
    window.addEventListener("focus", onWindowFocus);
    return () => window.removeEventListener("focus", onWindowFocus);
  }, []);

  // The pill's mouse-down focus (composer.rs:7701-7709): padding and action
  // controls are part of the text composer — unless an open menu keeps its
  // own keyboard/search focus.
  const onPillMouseDown = useCallback(
    (event: ReactMouseEvent<HTMLDivElement>): void => {
      if (pickersOpen) {
        return;
      }
      if (event.button === 0 && document.activeElement !== textareaRef.current) {
        textareaRef.current?.focus();
      }
    },
    [pickersOpen],
  );

  const sendAriaLabel = mode === "stop" ? "Stop" : mode === "queue" ? "Queue" : "Send";

  return (
    <div
      className={`composer ${expanded ? "composer-expanded" : "composer-compact"}`}
      data-working={runLive ? "true" : undefined}
      data-editing={editingActive ? "true" : undefined}
    >
      {failureVisible !== null && (
        <div
          className={`composer-failure ${failure?.message === "Engine not connected" ? "composer-failure-amber" : ""}`}
          id="composer-failure"
          role="status"
          onClick={() => setFailure(null)}
        >
          <Icon name="dangerTriangle" size={14} className="composer-failure-icon" />
          <div className="composer-failure-text">{failureVisible}</div>
        </div>
      )}
      {queueNotice !== null && (
        <div
          className="composer-queue-notice"
          id="composer-queue-notice"
          data-offline={queueOffline ? "true" : "false"}
        >
          <span className="composer-queue-dot" />
          <div className="composer-queue-text">{queueNotice}</div>
        </div>
      )}
      {queueSlot !== undefined && queueSlot !== null && (
        <div className="composer-queue-tray">{queueSlot}</div>
      )}
      <div className="composer-surface" id="composer-surface">
        {/*
          The pill. ONE DOM shape for both modes (the textarea never
          remounts — the caret survives the flip); the compact row and the
          expanded column are the same tree re-laid-out by `[data-mode]`
          CSS, with the animated numbers inline.
        */}
        <div
          className="composer-pill"
          data-mode={expanded ? "expanded" : "compact"}
          style={{ height: `${layout.pillHeight}px` }}
          onMouseDown={onPillMouseDown}
        >
          <AttachmentStrip
            chatId={chat.id}
            staged={staged}
            uploadProgress={uploadProgress}
            onStage={onStage}
            onRemove={onRemove}
            onError={onStageError}
            pickerRef={attachRef}
          />
          <div className="composer-body">
            <div
              className="composer-input-box"
              style={
                expanded
                  ? { height: `${layout.boxHeight}px`, paddingTop: `${layout.textPad}px` }
                  : { top: `${-layout.textGlide}px` }
              }
            >
              <textarea
                ref={textareaRef}
                className="composer-input"
                rows={1}
                value={text}
                placeholder="Do anything…"
                onChange={(event) => setText(event.target.value)}
                onKeyDown={onKeyDown}
                onPaste={onPaste}
                spellCheck={false}
                autoComplete="off"
                aria-label="Do anything…"
              />
            </div>
            <div
              className="composer-actions"
              style={
                expanded
                  ? { bottom: `${-layout.clusterDy}px`, paddingRight: `${layout.clusterInset}px` }
                  : { top: `${-layout.clusterDy}px`, paddingRight: `${layout.clusterInset}px` }
              }
            >
              <div className="composer-utility">
                <ComposerPickers
                  catalog={catalog}
                  draft={draft}
                  chatConfig={chat.config}
                  onDraft={applyDraft}
                  onPersist={persistDraft}
                  escapeFocusTarget={() => textareaRef.current}
                  onOpenChange={setPickersOpen}
                />
                <button type="button" className="composer-attach" aria-label="Attach" onClick={onAttachClick}>
                  <Icon name="paperclip" size={16} />
                </button>
              </div>
              {/*
                A 28px filled circle — up-arrow to send or queue, a dark
                rounded square on the same light circle to stop
                (`render_send_button`, composer.rs:7106). No label, no
                tooltip; blocked sends dim to 0.35 with no click handler;
                Stop is never blocked.
              */}
              <button
                type="button"
                className={`composer-send ${mode === "stop" ? "composer-send-stop" : ""}`}
                onClick={() => (mode === "stop" ? void interrupt() : void submit())}
                disabled={blocked}
                aria-label={sendAriaLabel}
              >
                {mode === "stop" ? (
                  <span className="composer-stop-square" />
                ) : (
                  <Icon name="arrowUp" size={14} />
                )}
              </button>
            </div>
          </div>
          {/*
            The text-width mirror: `white-space: pre` + `width: max-content`
            make its offsetWidth the unwrapped widest-line width. The font
            stack MUST match `.composer-input` so the number matches what
            the textarea wraps at.
          */}
          <div ref={measureRef} className="composer-input-measure" aria-hidden="true">
            {text}
          </div>
        </div>
      </div>
      {footerSlot}
    </div>
  );
}

function NOOP(): void {}

/** The seeded model's ladder for the draft-seeding effect (`trait_ladder`). */
function ladderFor(models: readonly Model[], modelId: string): readonly Model["reasoningLevels"][number][] {
  return models.find((model) => model.id === modelId)?.reasoningLevels ?? [];
}

/** `prefers-reduced-motion` at first paint (snap every morph). */
function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}
