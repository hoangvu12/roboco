import { useCallback, useEffect, useState } from "react";
import type { HarnessDescriptor, HarnessId, Model, TitleSettings } from "@roboco/proto";
import { MenuRow } from "./ui/MenuRows";
import { TitlePickerRow, CompactAction } from "./settings-widgets";
import { useEngineSession } from "../state/session-provider";
import {
  descriptorEnabled,
  getTitleSettings,
  listHarnesses,
  listModels,
  setTitleSettings as saveTitleSettings,
  supportsTitles,
} from "../lib/harnesses";

/**
 * Thread naming — the web port of the desktop's `settings/thread_naming.rs`
 * (upstream b782d043, ticket 26): which agent and model title new threads,
 * defaulting to the session's own agent. The card is Settings → General's
 * fourth row: the row title, the follows-session description line, the
 * pickers, and the Reset action that returns to the session agent.
 *
 * The desktop's control is the composer's own model picker in title-bound
 * mode (a single chip with the full harness/model catalog); the web keeps
 * its own settings grammar for the same control semantics — the same
 * two-row picker shape the Agents page's session-titles card uses
 * (`TitlePickerRow`, harness then model), with the Reset action the
 * desktop's card carries. The card and the Agents page's card both exist
 * on the desktop too (harnesses.rs `render_titles` + thread_naming.rs),
 * editing the same engine-local `GetTitleSettings`/`SetTitleSettings`
 * pair.
 */

type Loadable<T> =
  | { kind: "loading" }
  | { kind: "ready"; value: T }
  | { kind: "error"; message: string };

type TitleModels = Loadable<readonly Model[]>;

export function ThreadNamingCard() {
  const session = useEngineSession();
  const client = session?.client ?? null;
  const [settings, setSettings] = useState<Loadable<TitleSettings>>({ kind: "loading" });
  const [models, setModels] = useState<TitleModels>({ kind: "loading" });
  const [harnesses, setHarnesses] = useState<readonly HarnessDescriptor[]>([]);
  /** null closed; false = the agent picker, true = the model picker. */
  const [menu, setMenu] = useState<boolean | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Mount load: the title pair, then the title-capable harness catalog for
  // the agent picker. No engine yet (still dialing) leaves the loading
  // state standing — the card heals on the next visit, like the desktop's
  // Idle-through-connect behavior.
  useEffect(() => {
    if (client === null) {
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const pair = await getTitleSettings(client);
        if (cancelled) {
          return;
        }
        setSettings({ kind: "ready", value: pair });
        setError(null);
      } catch (cause) {
        if (!cancelled) {
          setSettings({ kind: "error", message: describe(cause) });
        }
        return;
      }
      try {
        const catalog = await listHarnesses(client);
        if (!cancelled) {
          setHarnesses(catalog);
        }
      } catch {
        // The catalog is best-effort: the pickers show only the Automatic
        // row until it lands; the card never fails on it.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [client]);

  // The model list follows the chosen harness (the desktop's picker loads
  // it the same way — `list_models` on the title harness).
  useEffect(() => {
    if (client === null || settings.kind !== "ready" || settings.value.harness === null) {
      setModels({ kind: "loading" });
      return;
    }
    const harness = settings.value.harness;
    let cancelled = false;
    void (async () => {
      try {
        const list = await listModels(client, harness);
        if (!cancelled) {
          setModels({ kind: "ready", value: list });
        }
      } catch (cause) {
        if (!cancelled) {
          setModels({ kind: "error", message: describe(cause) });
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [client, settings]);

  /** Save a choice; the reply is the authoritative pair (engine-validated). */
  const choose = useCallback(
    (choice: TitleSettings) => {
      if (client === null) {
        return;
      }
      setMenu(null);
      setSaving(true);
      setError(null);
      void (async () => {
        try {
          const saved = await saveTitleSettings(client, choice);
          setSettings({ kind: "ready", value: saved });
        } catch (cause) {
          setError(describe(cause));
        } finally {
          setSaving(false);
        }
      })();
    },
    [client],
  );

  const body = () => {
    if (settings.kind === "loading") {
      return <p className="settings-titles-loading">Loading thread naming…</p>;
    }
    if (settings.kind === "error") {
      return <p className="settings-titles-loading">{settings.message}</p>;
    }
    const value = settings.value;
    const harness = value.harness;
    const followsSession = harness === null;
    const harnessName = (id: HarnessId): string => {
      const descriptor = harnesses.find((entry) => entry.id === id);
      return descriptor?.name ?? id;
    };
    const harnessLabel = followsSession ? "Session agent" : harnessName(harness);
    const modelLabel =
      value.model === null
        ? "Cheapest model"
        : models.kind === "ready"
          ? (models.value.find((model) => model.id === value.model)?.label ?? value.model)
          : value.model;
    const harnessChoices: readonly { readonly label: string; readonly value: TitleSettings }[] = [
      { label: "Session agent", value: { harness: null, model: null } },
      ...harnesses
        .filter(
          (descriptor) =>
            descriptorEnabled(descriptor) &&
            descriptor.installed &&
            supportsTitles(descriptor.id) &&
            descriptor.id !== "mock",
        )
        .map((descriptor) => ({
          label: descriptor.name,
          value: { harness: descriptor.id, model: null } satisfies TitleSettings,
        })),
    ];
    const modelChoices: readonly { readonly label: string; readonly value: TitleSettings }[] = [
      { label: "Cheapest model", value: { harness: value.harness, model: null } },
      ...(models.kind === "ready"
        ? models.value.map((model) => ({
            label: model.label,
            value: { harness: value.harness, model: model.id } satisfies TitleSettings,
          }))
        : []),
    ];
    const choiceRow = (choice: { readonly label: string; readonly value: TitleSettings }) => (
      <MenuRow
        key={choice.label}
        fadeKey={choice.label}
        selected={
          choice.value.harness === value.harness && choice.value.model === value.model
        }
        onClick={() => choose(choice.value)}
      >
        {choice.label}
      </MenuRow>
    );
    return (
      <>
        <div className="settings-row thread-naming-control">
          <div className="settings-row-main">
            <span className="settings-row-title">Thread naming</span>
            <span className="settings-row-meta">
              {followsSession
                ? "Each thread is named by its own agent."
                : "A small model keeps titles fast and cheap."}
            </span>
          </div>
          {/* `text_action(Quiet)` — the Reset the desktop's card carries while
              a specific agent is chosen (thread_naming.rs:116-129). */}
          {!followsSession && (
            <CompactAction
              aria-label="Name threads with the session agent"
              onClick={() => choose({ harness: null, model: null })}
            >
              Reset
            </CompactAction>
          )}
        </div>
        <TitlePickerRow
          label="Name threads with"
          display={harnessLabel}
          interactive={!saving}
          open={menu === false}
          onOpenChange={(next) => setMenu(next ? false : null)}
        >
          {harnessChoices.map(choiceRow)}
        </TitlePickerRow>
        <TitlePickerRow
          label="Title model"
          display={modelLabel}
          interactive={!saving && !followsSession}
          open={menu === true}
          onOpenChange={(next) => setMenu(next ? true : null)}
        >
          {modelChoices.map(choiceRow)}
        </TitlePickerRow>
      </>
    );
  };

  return (
    <section className="settings-card settings-titles-card">
      {body()}
      {error !== null && (
        <p className="error-strip" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}

function describe(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}
