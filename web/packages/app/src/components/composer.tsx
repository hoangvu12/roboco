import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { Chat, HarnessDescriptor, Model } from "@roboco/proto";
import type { EngineSession } from "../state/engine-session";
import { useWatchSnapshot } from "../state/hooks";
import { PickerCatalog } from "../state/picker-catalog";
import { sidebarNotice } from "../state/notice";
import { draftFromChat, isHarnessLocked } from "../lib/composer-draft";
import { describeSendError, sendInterrupt, sendRun, sendSteer, type DraftConfig, type DraftConfigUpdate } from "../lib/composer-actions";
import { ComposerPickers } from "./composer-pickers";

/**
 * The composer — the desktop's `crates/ui/src/composer.rs` ported to React:
 * multiline input with a compact (49px) ↔ expanded (124-308px) flip morph,
 * four pickers above, and a send-button that morphs Send → Steer → Stop
 * based on the chat's live status. Steer is offered only when the picked
 * harness advertises `supportsSteering` and the chat is currently working;
 * Stop replaces Send when the textarea is empty during a live run.
 *
 * Wired into the chat page above the terminal dock so the composer never
 * shifts when the terminal opens (the desktop's reserved `statusStripHeight`
 * is mirrored by the chat-page's `min-height: 0` on the transcript column).
 */

/** The flip-morph boundaries (composer.rs compact/expanded). */
const COMPACT_HEIGHT_PX = 49;
const EXPANDED_MIN_PX = 124;
const EXPANDED_MAX_PX = 308;
/** Animation duration (composer.rs flip rules use `motion.collapse` = 180ms ease-out). */
const FLIP_DURATION_MS = 180;

const NAVIGATION_MS_THRESHOLD = 250;

interface ComposerProps {
  readonly session: EngineSession;
  readonly chat: Chat;
  readonly catalog: PickerCatalog;
  /** Called when the chat id changes so the host can flush the textarea on switch. */
  readonly onSwitchChat?: (chatId: string) => void;
  /**
   * The queued row currently being edited in this composer — `null` when
   * the composer is free (a normal send). When this changes to a non-null
   * value, the composer seeds its textarea with `editingMessage.text` and
   * routes send/clear through `onEditFinish` so the host can release the
   * edit lease (commit on send with changes, releaseUnchanged on empty
   * send, cancel on chat switch / explicit release).
   */
  readonly editingMessage?: { id: string; text: string } | null;
  readonly onEditFinish?: (outcome: { action: "commit" | "cancel" | "releaseUnchanged"; text: string }) => void;
}

export function Composer({ session, chat, catalog, onSwitchChat, editingMessage, onEditFinish }: ComposerProps) {
  const snapshot = useWatchSnapshot(session);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const lastChatIdRef = useRef(chat.id);

  // Live status: a working session = a live run in progress.
  const status = snapshot?.statuses.rows.find((row) => row.chatId === chat.id) ?? null;
  const isWorking = status !== null && status.status === "working";
  const supportsSteering = useMemo(() => {
    const harnesses = catalog.getHarnesses().rows;
    const picked = harnesses.find((row: HarnessDescriptor) => row.id === chat.config?.harness);
    return picked?.supportsSteering === true && isWorking;
  }, [catalog, chat.config?.harness, isWorking]);
  const steeringMode = useMemo(() => {
    const harnesses = catalog.getHarnesses().rows;
    const picked = harnesses.find((row: HarnessDescriptor) => row.id === chat.config?.harness);
    return picked?.steeringMode ?? "turn-boundary";
  }, [catalog, chat.config?.harness]);

  const harnesses = catalog.getHarnesses();
  const models = catalog.getModels(chat.config?.harness ?? "claude-code");

  const [text, setText] = useState("");
  const [draft, setDraft] = useState<DraftConfig>(() =>
    draftFromChat(chat, harnesses.rows, models.rows),
  );
  const [flipExpanded, setFlipExpanded] = useState(false);
  const [busy, setBusy] = useState(false);

  // When the chat id changes (and on first mount), reset the composer:
  // a fresh chat's textarea is empty; existing chats replay whatever the
  // user typed last (in v1: empty too, since we don't persist drafts yet).
  useEffect(() => {
    if (lastChatIdRef.current === chat.id) {
      return;
    }
    lastChatIdRef.current = chat.id;
    setText("");
    setFlipExpanded(false);
    onSwitchChat?.(chat.id);
  }, [chat.id, onSwitchChat]);

  // When the edit row changes (the chat page started/cancelled editing a
  // queued row), seed the textarea with the row's text so the user can
  // type a replacement. The host owns the lease; we just mirror its text.
  const lastEditingIdRef = useRef<string | null>(null);
  useEffect(() => {
    const id = editingMessage?.id ?? null;
    if (id === lastEditingIdRef.current) {
      return;
    }
    lastEditingIdRef.current = id;
    if (editingMessage !== null && editingMessage !== undefined) {
      setText(editingMessage.text);
      setFlipExpanded(editingMessage.text.length > 0);
    }
  }, [editingMessage]);

  // Reconcile the draft with chat.config + the loaded catalog: if a chat
  // already has a persisted ChatConfig, use it (locked); if not, default
  // to the first harness/model/reasoning once the catalog lands.
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
      if (harnesses.rows.some((h) => h.id === current.harness)) {
        return current;
      }
      const firstHarness = harnesses.rows[0];
      if (firstHarness === undefined) {
        return current;
      }
      return {
        harness: firstHarness.id,
        model: null,
        reasoning: "medium",
        sandbox: "workspace-write",
        modelOptions: {},
      };
    });
  }, [chat.config, harnesses.rows]);

  // Once a harness is picked, ensure the model catalog is loaded; default
  // to the first model + its first reasoning level if the draft is empty.
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
        return current;
      }
      const first = models.rows[0];
      if (first === undefined) {
        return current;
      }
      return {
        ...current,
        model: first.id,
        reasoning: first.reasoningLevels[0] ?? current.reasoning,
      };
    });
  }, [models.rows]);

  // Auto-resize the textarea between compact and expanded based on content
  // height, with a small hysteresis to avoid flip-flopping near the boundary.
  useLayoutEffect(() => {
    const el = textareaRef.current;
    if (el === null) {
      return;
    }
    el.style.height = "auto";
    const target = Math.min(Math.max(el.scrollHeight, COMPACT_HEIGHT_PX), EXPANDED_MAX_PX);
    const wantExpanded = target > COMPACT_HEIGHT_PX + 1;
    if (wantExpanded !== flipExpanded) {
      setFlipExpanded(wantExpanded);
    }
    el.style.height = `${target}px`;
    el.style.overflowY = el.scrollHeight > EXPANDED_MAX_PX ? "auto" : "hidden";
  }, [text, flipExpanded]);

  const updateDraft = useCallback((update: DraftConfigUpdate) => {
    setDraft((current) => {
      const next: DraftConfig = {
        harness: update.harness ?? current.harness,
        model: update.model !== undefined ? update.model : current.model,
        reasoning: update.reasoning !== undefined ? update.reasoning : current.reasoning,
        sandbox: update.sandbox ?? current.sandbox,
        modelOptions: update.modelOptions !== undefined ? { ...update.modelOptions } : current.modelOptions,
      };
      return next;
    });
  }, []);

  // The submit dispatch: routes to Send / Steer / Interrupt based on the
  // live state. Each branch is independent so a misstep is one branch's
  // fault, not the whole composer's.
  const submit = useCallback(async () => {
    if (busy) {
      return;
    }
    const trimmed = text.trim();
    const editing = editingMessage ?? null;
    if (editing !== null) {
      // Editing a queued row: send replaces the row text in place (commit)
      // and then runs/steers as usual. Empty submit closes the lease without
      // changing the row text (releaseUnchanged).
      if (trimmed.length === 0) {
        setBusy(true);
        try {
          onEditFinish?.({ action: "releaseUnchanged", text: trimmed });
        } finally {
          setBusy(false);
        }
        return;
      }
      setBusy(true);
      try {
        const textChanged = trimmed !== (editing.text ?? "").trim();
        if (textChanged) {
          onEditFinish?.({ action: "commit", text: trimmed });
        } else {
          onEditFinish?.({ action: "releaseUnchanged", text: trimmed });
        }
        if (isWorking && supportsSteering) {
          await sendSteer(session.client, chat.id, trimmed);
        } else if (chat.cwd !== null && chat.cwd !== undefined && chat.cwd.trim().length > 0) {
          await sendRun(session.client, chat.id, draft, trimmed, chat.cwd, { currentConfig: chat.config });
        }
        setText("");
      } catch (error) {
        sidebarNotice.set(`Could not send: ${describeSendError(error)}`);
      } finally {
        setBusy(false);
      }
      return;
    }
    if (isWorking && trimmed.length === 0) {
      // Empty + working → Stop (interrupt).
      setBusy(true);
      try {
        await sendInterrupt(session.client, chat.id);
      } catch (error) {
        sidebarNotice.set(`Could not interrupt: ${describeSendError(error)}`);
      } finally {
        setBusy(false);
      }
      return;
    }
    if (isWorking && trimmed.length > 0 && supportsSteering) {
      // Live run + text + steering-capable harness → Steer.
      setBusy(true);
      try {
        await sendSteer(session.client, chat.id, trimmed);
        setText("");
      } catch (error) {
        sidebarNotice.set(`Could not steer: ${describeSendError(error)}`);
      } finally {
        setBusy(false);
      }
      return;
    }
    if (trimmed.length === 0) {
      return;
    }
    if (chat.cwd === null || chat.cwd === undefined || chat.cwd.trim().length === 0) {
      sidebarNotice.set("This chat has no working directory yet — pick a space first.");
      return;
    }
    setBusy(true);
    try {
      await sendRun(session.client, chat.id, draft, trimmed, chat.cwd, { currentConfig: chat.config });
      setText("");
    } catch (error) {
      sidebarNotice.set(`Could not send: ${describeSendError(error)}`);
    } finally {
      setBusy(false);
    }
  }, [busy, text, isWorking, supportsSteering, session.client, chat.id, chat.cwd, chat.config, draft, editingMessage, onEditFinish]);

  // The composer is disabled until the catalog has at least the harness list.
  const composerReady = harnesses.loaded;

  const onKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>): void => {
    // Mod+Enter (cmd/ctrl) submits. Plain Enter inserts a newline so the
    // textarea behaves like every other web input — Enter-as-newline is the
    // common web idiom, the desktop's `ComposerSendBehavior` default is
    // mod+enter-submit. Esc layers with popovers (the popover catches its
    // own Esc; here we treat Esc as interrupt when working + empty).
    if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
      event.preventDefault();
      void submit();
      return;
    }
    if (event.key === "Escape" && isWorking && text.length === 0) {
      event.preventDefault();
      void submit();
      return;
    }
  };

  // Compose the send button label & variant.
  const sendLabel = editingMessage !== null && editingMessage !== undefined
    ? text.trim().length === 0
      ? "Release"
      : "Commit & send"
    : isWorking
      ? text.length > 0
        ? supportsSteering
          ? "Steer"
          : "Send"
      : "Stop"
    : "Send";
  const sendVariant: "default" | "stop" = !editingMessage && isWorking && text.length === 0 ? "stop" : "default";
  const sendDisabled = busy || !composerReady || (text.trim().length === 0 && !isWorking && !editingMessage);

  const heightPx = flipExpanded ? Math.max(EXPANDED_MIN_PX, textareaRef.current?.scrollHeight ?? EXPANDED_MIN_PX) : COMPACT_HEIGHT_PX;

  return (
    <div
      className={`composer ${flipExpanded ? "composer-expanded" : "composer-compact"} ${isWorking ? "composer-working" : ""} ${editingMessage !== null && editingMessage !== undefined ? "composer-editing" : ""}`}
      data-steering-mode={steeringMode}
    >
      <div className="composer-input-wrap" style={{ animationDuration: `${FLIP_DURATION_MS}ms` }}>
        <textarea
          ref={textareaRef}
          className="composer-input"
          rows={1}
          value={text}
          placeholder={isWorking ? "Steer the live run… (Mod+Enter to send)" : "Message the harness…"}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={onKeyDown}
          spellCheck={false}
          autoComplete="off"
          disabled={!composerReady}
          aria-label={editingMessage !== null && editingMessage !== undefined ? "Edit queued message" : "Compose message"}
          style={{ height: `${heightPx}px` }}
        />
      </div>
      <div className="composer-footer">
        <ComposerPickers
          draft={draft}
          harnesses={harnesses.rows}
          models={models.rows}
          harnessError={harnesses.loaded ? harnesses.error : null}
          modelsError={models.loaded ? models.error : null}
          harnessLocked={isHarnessLocked(chat)}
          onChange={updateDraft}
          onRetryHarnesses={() => void catalog.loadHarnesses()}
          onRetryModels={() => void catalog.loadModels(draft.harness)}
        />
        <button
          type="button"
          className={`composer-send btn-solid ${sendVariant === "stop" ? "composer-send-stop" : ""}`}
          onClick={() => void submit()}
          disabled={sendDisabled}
          title={
            isWorking && text.length === 0
              ? "Interrupt the live run"
              : isWorking && supportsSteering
                ? "Steer the live run (Mod+Enter)"
                : "Send (Mod+Enter)"
          }
        >
          {busy ? "…" : sendLabel}
        </button>
      </div>
    </div>
  );
}

/** The navigation threshold the desktop uses to flip-morph the new-thread ↔ session handoff. */
export const COMPOSER_NAVIGATION_MS_THRESHOLD = NAVIGATION_MS_THRESHOLD;
