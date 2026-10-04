import { useEffect, useRef, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { pairEngine } from "../state/fleet";
import { webDeviceLabel } from "../lib/engine-store";
import { describeRedeemError } from "../lib/pairing-errors";

type PairPhase = { kind: "idle" } | { kind: "redeeming" } | { kind: "error"; message: string };

function hashParam(name: string): string | null {
  const raw = new URLSearchParams(window.location.hash.slice(1)).get(name);
  if (raw === null) {
    return null;
  }
  try {
    return decodeURIComponent(raw);
  } catch {
    return raw;
  }
}

/**
 * The pairing landing: the engine's pairing link points here with the
 * token in the fragment (never sent to the engine as a URL part). The
 * token auto-redeems; `#invite=` carries a URL-encoded Tailcat invite the
 * same way (avoids copy corruption). A paste field covers manual pairing
 * and the re-pair flow after a revoked Session. Pairing goes through the
 * fleet layer so a damaged configuration refuses here too, and the registry
 * starts supervising the new engine immediately.
 */
export function PairPage() {
  const [token] = useState(() => hashParam("token"));
  const [tailcatInvite] = useState(() => hashParam("invite"));
  const autoPair =
    token !== null
      ? ({ kind: "https-token" } as const)
      : tailcatInvite !== null
        ? ({ kind: "tailcat-invite", invite: tailcatInvite } as const)
        : null;
  const started = useRef(false);
  const [phase, setPhase] = useState<PairPhase>(autoPair !== null ? { kind: "redeeming" } : { kind: "idle" });
  const [url, setUrl] = useState("");
  const navigate = useNavigate();

  useEffect(() => {
    if (autoPair === null || started.current) {
      return;
    }
    started.current = true;
    void (async () => {
      try {
        if (autoPair.kind === "https-token") {
          await pairEngine(window.location.href, webDeviceLabel());
        } else {
          await pairEngine(autoPair.invite, webDeviceLabel());
        }
        void navigate({ to: "/", replace: true });
      } catch (error) {
        setPhase({ kind: "error", message: describeRedeemError(error) });
      }
    })();
  }, [autoPair, navigate]);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (phase.kind === "redeeming" || url.trim().length === 0) {
      return;
    }
    setPhase({ kind: "redeeming" });
    try {
      await pairEngine(url.trim(), webDeviceLabel());
      setUrl("");
      void navigate({ to: "/", replace: true });
    } catch (error) {
      setPhase({ kind: "error", message: describeRedeemError(error) });
    }
  }

  return (
    <main className="pair-page">
      <h1>Pair this browser</h1>
      {phase.kind === "redeeming" ? <p className="pair-status">Redeeming the pairing link…</p> : null}
      {phase.kind === "error" ? <p className="form-error">{phase.message}</p> : null}
      <form className="pair-form" onSubmit={submit}>
        <label className="add-engine-label" htmlFor="pair-url">
          Pairing link or Tailcat invite
        </label>
        <input
          id="pair-url"
          className="input"
          type="text"
          placeholder="https://…/pair#token=… or roboco-tailcat:…"
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          autoComplete="off"
          spellCheck={false}
        />
        <button className="btn btn-solid" type="submit" disabled={phase.kind === "redeeming" || url.trim().length === 0}>
          {phase.kind === "redeeming" ? "Pairing…" : "Pair engine"}
        </button>
      </form>
      <p className="pair-hint">
        Paste an HTTPS pairing link, or a <code>roboco-tailcat:…</code> invite from any engine.
        Tailcat invites are redeemed through this site automatically — no local helper required.
      </p>
    </main>
  );
}
