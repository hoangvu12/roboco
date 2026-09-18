import { useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { RpcError, type EngineEntrySnapshot } from "@roboco/engine-client";
import { engineHost, webDeviceLabel, type StoredEngine } from "../lib/engine-store";
import { forgetEngine, pairEngine, useFleet, useFleetRegistry } from "../state/fleet";
import { useEngineSessions } from "../state/session-provider";
import { chatListRows, mostUrgent } from "../lib/view";
import { StatusDot } from "./status-dot";

/**
 * The user menu's engine list — the fleet's quick registry view, reached
 * from `account-row.tsx`'s "Engines" item (`emitShortcut("open-engines")`).
 *
 * Ticket 31 resolved this drawer's long-deferred fate (§2.6) by KEEPING it,
 * repurposed: with every paired engine connected simultaneously there is no
 * "active" engine to switch to, so the Switch action is GONE; what remains
 * is the fleet's status list — each engine's live connection state, its
 * urgent-chat dot, "Pair again" for a parked engine, "Forget" to unpair —
 * plus the "Add an engine" pairing form. This matches the desktop's own
 * user-menu pattern most closely: Settings → Devices is the per-engine
 * management page; the user menu carries the quick list. All data now
 * comes from the shared fleet registry, not the single-active-engine
 * store.
 */
export function EngineDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const fleet = useFleet();
  if (!open) {
    return null;
  }
  return (
    <div className="drawer-backdrop" onClick={onClose}>
      <section className="drawer panel" role="dialog" aria-label="Engines" onClick={(event) => event.stopPropagation()}>
        <header className="drawer-header">
          <h2>Engines</h2>
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            Close
          </button>
        </header>
        {fleet.configurationError !== null && (
          <p className="drawer-note drawer-note-error">{fleet.configurationError}</p>
        )}
        <EngineList />
        <AddEngineForm />
      </section>
    </div>
  );
}

function EngineList() {
  const fleet = useFleet();
  const registry = useFleetRegistry();
  if (fleet.engines.length === 0) {
    return <p className="drawer-note">No engines paired yet. Add one below.</p>
  }
  const byKey = new Map(registry.engines.map((engine) => [engine.key, engine]));
  return (
    <ul className="engine-list">
      {fleet.engines.map((engine) => (
        <EngineRow
          key={engine.baseUrl}
          engine={engine}
          entry={byKey.get(engine.baseUrl) ?? null}
        />
      ))}
    </ul>
  );
}

interface EngineRowProps {
  readonly engine: StoredEngine;
  readonly entry: EngineEntrySnapshot | null;
}

/** One engine's connection view off its registry entry state. */
function entryConnection(entry: EngineEntrySnapshot | null): {
  className: string;
  dot: string;
  label: string;
  pairable: boolean;
} {
  if (entry === null) {
    return { className: "conn-connecting", dot: "dot-connecting", label: "Starting…", pairable: false };
  }
  switch (entry.state) {
    case "connected":
      return { className: "conn-connected", dot: "dot-connected", label: "Connected", pairable: false };
    case "reconnecting":
      return { className: "conn-reconnecting", dot: "dot-reconnecting", label: "Reconnecting…", pairable: false };
    case "off":
      return {
        className: "conn-parked",
        dot: "dot-parked",
        label: (entry.lastError ?? "").includes("identity") ? "Engine changed" : "Session revoked",
        pairable: true,
      };
  }
}

function EngineRow({ engine, entry }: EngineRowProps) {
  const navigate = useNavigate();
  const sessions = useEngineSessions();
  // The engine's own session: its watch cache supplies the urgent-chat dot.
  const session = sessions.get(engine.baseUrl) ?? null;
  const connection = entryConnection(entry);
  const snapshot = session?.cache.getSnapshot() ?? null;
  const urgent =
    snapshot !== null && snapshot.chats.loaded
      ? mostUrgent(chatListRows(snapshot.chats.rows, snapshot.spaces.rows, snapshot.statuses.rows, Date.now()).map((row) => row.status))
      : null;

  function pairAgain(): void {
    void navigate({ to: "/pair" });
  }

  return (
    <li className={`engine-row ${connection.className === "conn-parked" ? "engine-row-parked" : ""}`}>
      <div className="engine-row-main">
        <div className="engine-row-title">
          {urgent !== null && <StatusDot status={urgent} />}
          <span className="engine-row-host">{engineHost(engine.baseUrl)}</span>
        </div>
        <div className="engine-row-sub">
          <span className={`engine-row-status conn ${connection.className}`}>
            <span className={`dot ${connection.dot}`} aria-hidden />
            {connection.label}
          </span>
          <span className="engine-row-identity">
            {engine.deviceId !== null ? `Engine ${engine.deviceId.slice(0, 8)}` : "Identity unverified"}
          </span>
        </div>
      </div>
      <div className="engine-row-actions">
        {connection.pairable && (
          <button type="button" className="btn btn-ghost engine-row-pair" onClick={pairAgain}>
            Pair again
          </button>
        )}
        <RemoveEngineButton engine={engine} />
      </div>
    </li>
  );
}

function RemoveEngineButton({ engine }: { engine: StoredEngine }) {
  const [confirming, setConfirming] = useState(false);
  if (!confirming) {
    return (
      <button type="button" className="btn btn-ghost" onClick={() => setConfirming(true)}>
        Forget
      </button>
    );
  }
  return (
    <button type="button" className="btn btn-danger-ghost" onClick={() => forgetEngine(engine.baseUrl)}>
      Forget?
    </button>
  );
}

function AddEngineForm() {
  const [url, setUrl] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy || url.trim().length === 0) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      // The fleet registry picks the new engine up through its store
      // subscription and starts supervising immediately — no switch step.
      await pairEngine(url.trim(), webDeviceLabel());
      setUrl("");
    } catch (cause) {
      setError(describeRedeemError(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="add-engine" onSubmit={submit}>
      <label className="add-engine-label" htmlFor="add-engine-url">
        Add an engine by its pairing URL
      </label>
      <input
        id="add-engine-url"
        className="input"
        type="text"
        placeholder="http://engine-host:27699/pair#token=…"
        value={url}
        onChange={(event) => setUrl(event.target.value)}
        autoComplete="off"
        spellCheck={false}
      />
      <button className="btn btn-solid" type="submit" disabled={busy || url.trim().length === 0}>
        {busy ? "Pairing…" : "Add engine"}
      </button>
      {error !== null && <p className="form-error">{error}</p>}
    </form>
  );
}

export function describeRedeemError(error: unknown): string {
  if (error instanceof RpcError) {
    if (error.kind === "failed") {
      return "That pairing link did not work — it may have expired or already have been used.";
    }
    if (error.kind === "transport") {
      return "Could not reach that engine. Check the URL and that the engine is running.";
    }
    return error.message;
  }
  if (error instanceof Error && error.message.length > 0) {
    // The store's configuration-error refusal, and any storage failure.
    return error.message;
  }
  return "Pairing failed.";
}
