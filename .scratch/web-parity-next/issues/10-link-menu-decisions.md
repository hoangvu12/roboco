# 10 — Link-menu and web-only-affordance decisions

**What to build:** Nothing yet — this ticket records three parity
decisions the deep research prepared options for. Each needs a maintainer
call (check the box or override in `## Comments`), then a follow-up
implementation ticket (or an exclusion note in code) is filed.

**Blocked by:** None.

**Status:** needs-triage

**Research:** `.scratch/web-parity-next/research.md` (wiring items 4-5,
cosmetic 15).

## Decision 1 — workspace-file link menu: "Open with default app" / "Show in folder"

Desktop (markdown/link_interaction.rs:443-458): the rows mount only when
the link resolves against a workspace root **whose owner lives on this
device** (`file.local`, workspace_links.rs:91-93); resolved-but-remote gets
Open/Copy only. Web menu: "Open link" / "Copy link address"
(markdown.tsx:455-476) — `copyAddress` already copies the resolved path, so
web Copy ≡ desktop "Copy file path".

**Options:**
- (a) New engine RPC (`OpenPath`/`RevealPath`). The full method table
  (crates/rpc/src/lib.rs:31-228) has **no** OS open/reveal; closest
  primitives are `OpenTerminal` (PTY at a cwd) and `RunProjectAction`
  (command inside a PTY). A real reveal RPC touches rpc methods, engine
  dispatch, proto, `wiregen --check` CI, and `@roboco/engine-client` —
  **engine-scope work, not web-scope**; a web ticket must not carry it.
- (b) Recorded exclusion: a browser cannot open/reveal OS windows, and an
  engine RPC would pop the file manager on the *engine's* screen — matching
  user intent only when browser and engine share a machine.

**Recommendation: (b)** — record the exclusion with the local-ownership
rationale (remote browsing makes reveal wrong-machine). If localhost use
ever wants it, file a separate engine ticket for `RevealPath`.

## Decision 2 — the external-link menu's "Open links in Roboco" row

Desktop (link_interaction.rs:493-521): last row of the *external-link* menu
only, a checked toggle for `open_web_links_in_roboco`, immediate save. The
web has no embedded browser (documented exclusion, recorded at
`.scratch/web-parity/issues/03-client-settings-store.md:136,343`).

**Recommendation: record the exclusion for both the row and the setting.**
The web's only external destination is a new browser tab
(markdown.tsx:384-386) — a destination toggle would toggle nothing. Don't
invent a row.

## Decision 3 — the web-only terminal toggle chip on the new-thread row

The desktop's canvas target row shows device + project chips only
(pickers.rs:3132-3192); the web adds a third chip — `NewThreadTerminalAction`
(new-thread-selectors.tsx:146, component :162-193) toggling the canvas
terminal drawer, recorded in-file as a sanctioned deviation
(:153-160, upstream 23e258ff/#474's per-space drawer with the row as "the
natural action spot").

**Recommendation: keep (sanctioned web addition)** — record it in the
parity checklist so future audits stop flagging it.

## Acceptance checklist

- [ ] Decision 1 recorded (exclusion note in markdown.tsx's menu comment
      unless overridden)
- [ ] Decision 2 recorded (same file, one line)
- [ ] Decision 3 recorded as sanctioned
- [ ] Follow-up tickets filed only where a decision overrides a
      recommendation
