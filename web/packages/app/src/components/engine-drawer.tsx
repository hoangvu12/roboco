import { useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { RpcError } from "@roboco/engine-client";
import { engineHost, webDeviceLabel, type StoredEngine } from "../lib/engine-store";
import { fleetStore, useFleet } from "../state/fleet";
import { useEngineSession } from "../state/session-provider";
import { useEngineStatus, useWatchSnapshot } from "../state/hooks";
import { chatListRows, mostUrgent } from "../lib/view";
import { connectionState } from "./connection-state";
import { StatusDot } from "./status-dot";

export function EngineDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
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
        <EngineList />
        <AddEngineForm />
      </section>
    </div>
  );
}

function EngineList() {
  const fleet = useFleet();
  const session = useEngineSession();
  if (fleet.engines.length === 0) {
    return <p className="drawer-note">No engines paired yet. Add one below.</p>;
  }
  return (
    <ul className="engine-list">
      {fleet.engines.map((engine) => (
        <EngineRow key={engine.baseUrl} engine={engine} active={engine.baseUrl === fleet.active} hasSession={session !== null && session.engine.baseUrl === engine.baseUrl} />
      ))}
    </ul>
  );
}

interface EngineRowProps {
  readonly engine: StoredEngine;
  readonly active: boolean;
  readonly hasSession: boolean;
}

function EngineRow({ engine, active, hasSession }: EngineRowProps) {
  const navigate = useNavigate();
  const session = useEngineSession();
  const status = useEngineStatus(hasSession ? session : null);
  const snapshot = useWatchSnapshot(hasSession ? session : null);
  const state = connectionState(hasSession ? status : null);
  const urgent = hasSession && snapshot !== null && snapshot.chats.loaded ? mostUrgent(chatListRows(snapshot.chats.rows, snapshot.spaces.rows, snapshot.statuses.rows, Date.now()).map((row) => row.status)) : null;

  function pairAgain(): void {
    void navigate({ to: "/pair" });
  }

  return (
    <li className={`engine-row ${active ? "engine-row-active" : ""} ${state.parked ? "engine-row-parked" : ""}`}>
      <div className="engine-row-main">
        <div className="engine-row-title">
          {urgent !== null && <StatusDot status={urgent} />}
          <span className="engine-row-host">{engineHost(engine.baseUrl)}</span>
          {active && <span className="engine-row-badge">Active</span>}
        </div>
        <div className="engine-row-sub">
          <span className={`engine-row-status conn ${state.className}`}>
            <span className={`dot ${state.dot}`} aria-hidden />
            {state.label}
          </span>
          <span className="engine-row-identity">
            {engine.deviceId !== null ? `Engine ${engine.deviceId.slice(0, 8)}` : "Identity unverified"}
          </span>
        </div>
      </div>
      <div className="engine-row-actions">
        {state.pairable && (
          <button type="button" className="btn btn-ghost engine-row-pair" onClick={pairAgain}>
            Pair again
          </button>
        )}
        {!active && (
          <button
            type="button"
            className="btn btn-ghost"
            onClick={() => {
              fleetStore.setActive(engine.baseUrl);
              void navigate({ to: "/" });
            }}
          >
            Switch
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
    <button type="button" className="btn btn-danger-ghost" onClick={() => fleetStore.remove(engine.baseUrl)}>
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
      await fleetStore.redeemPairingUrl(url.trim(), webDeviceLabel());
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
  return "Pairing failed.";
}
