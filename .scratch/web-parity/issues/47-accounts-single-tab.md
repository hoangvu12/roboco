# 47 — Accounts: Add account opens exactly one browser tab

**What to build:** Clicking "Add account" for Codex on Windows opens
exactly one auth tab instead of two. The engine learns to tell clients
"the CLI already opened the browser" via a flag on the `AgentLoginStart`
reply, and both clients (desktop `cx.open_url`, web `window.open`) skip
their own open when that flag is set. Independently, the web's open
becomes popup-blocker-proof by pre-opening a blank tab synchronously
inside the click handler and navigating it once the RPC resolves. The
user said they won't test much — scope stays tight: correct flow, Claude
and Cursor untouched, Reopen links kept.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/settings-remote-access-accounts.md` S3.a–S3.d (mechanism trace, root cause, fix sketches, gap rows).

**Desktop reference (for lookups only):** `crates/engine/src/agent_accounts.rs` (dispatch 522-532, claude `start_claude_login` 534-564, codex `start_codex_login` 581-659 — the double-tab comment 614-619, the unix-only `BROWSER`-noop suppression 620-623, the spawned reply at ~657, `scan_openai_url` 1832-1837, `ensure_noop_browser` doc "Unix only" 1839-1842, cursor `start_cursor_login` 665-710); `crates/harness/src/codex/mod.rs` (login_command, isolated `CODEX_HOME`, 92-98); `crates/harness/src/cursor/shim.mjs` (login mode 129-148; `openBrowser: false` at 133-135); `crates/proto/src/entities.rs` (`AgentLoginStart` 797-814, `AgentLoginMode` 807-814); `crates/ui/src/settings/accounts.rs` (`start_login` 491-541 — `device_target` 492, params 497, the client's own open at 508, paste-code 510-519, browser + `spawn_poll` 520-528, poll loop 590-666, Reopen link 932-949/977-982).

**Desktop / engine files to touch:**
| File | Change | Owns |
| --- | --- | --- |
| `crates/proto/src/entities.rs` | edit | `AgentLoginStart` (797-814) gains `cli_opens_browser: bool` |
| `crates/engine/src/agent_accounts.rs` | edit | set the flag in `start_codex_login`'s reply (~657): true exactly when the CLI's own browser open was NOT suppressed; claude (~534-564) and cursor (~708) replies set it false |
| `crates/ui/src/settings/accounts.rs` | edit | gate the client's open: `if !start.cli_opens_browser { cx.open_url(&start.url); }` (508) |
| `web/packages/proto/src/generated/*` | regenerate | `cargo run -p wiregen` — `AgentLoginStart.ts` gains `cliOpensBrowser: boolean`; the CI gate `web-codegen.yml` (`wiregen --check` + `pnpm -r build`) enforces freshness |

**Web files to touch:**
| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/routes/settings-accounts.tsx` | edit | `addAccount` (138-158): pre-open the blank tab synchronously, navigate after the RPC, skip when `start.cliOpensBrowser` |

## 1. Context a fresh session needs

- Surface map (research §0, the row this ticket lives in), verbatim:

| Surface | Desktop | Web |
| --- | --- | --- |
| Settings → Accounts | `crates/ui/src/settings/accounts.rs` | `routes/settings-accounts.tsx` |

- Vocabulary: chat, space, engine, device, pairing, **Session** (capital,
  the credential). "Add account" is a per-device CLI login, not a Roboco
  account — Roboco has no accounts.
- **Today's flow, engine side** (`agent_accounts.rs:522-532` dispatch):
  - **Claude Code** → `start_claude_login` builds the PKCE URL in-process,
    no child process, `mode: PasteCode` (`:534-564`). No browser open on
    the engine side.
  - **Codex** → `start_codex_login` spawns `codex login` with an isolated
    `CODEX_HOME` (`:581-659`; `crates/harness/src/codex/mod.rs:92-98`),
    scans the auth URL off the CLI's output (`:653`,
    `scan_openai_url` `:1832-1837`), returns `mode: Browser` (`:657`).
    The CLI **opens the authorization tab itself** via the `webbrowser`
    crate — the engine's own comment at `:614-619` documents the
    double-tab history and the fix: point `BROWSER` at a no-op script so
    the CLI's open stays quiet. That suppression is **`#[cfg(unix)]`
    only** (`:620-623`); the helper's doc says "Unix only — `webbrowser`
    only consults `BROWSER` on unix; elsewhere the CLI's own open is left
    as-is" (`:1839-1842`).
  - **Cursor** → `start_cursor_login` runs the roboco shim in login mode
    with `openBrowser: false` (`:665-710`;
    `crates/harness/src/cursor/shim.mjs:129-148`, option at `:133-135`) —
    no CLI-side open, `mode: Browser` (`:708`).
- **Today's flow, client side**: desktop `Add account` → `StartAgentLogin`
  (routed through `device_target`, `accounts.rs:492`, params `:497`) → on
  reply the app opens the page itself: `cx.open_url(&start.url)`
  (`accounts.rs:508`) → paste-code dialog (`:510-519`) or browser-poll
  dialog + `spawn_poll` (`:520-528`). Web `addAccount` → `startAgentLogin`
  (`lib/accounts.ts:67-76`) → **`window.open(start.url, "_blank",
  "noopener,noreferrer")` (`settings-accounts.tsx:147`)** → dialog;
  paste-code submit (`:160-183`, `completeAgentLogin`
  `lib/accounts.ts:78-89`) or browser poll (`:84-114`, `pollAgentLogin`
  1.5s loop `lib/accounts.ts:265-300`).
- **Root cause** (research S3.b): two opens, one per side, both land in
  the user's browser when the engine runs on the user's own machine (the
  S1 setup — web paired to the local Windows engine):
  1. engine machine, codex only: the spawned CLI opens the tab —
     unsuppressed on Windows (`agent_accounts.rs:620-623`,
     `:1839-1842`);
  2. client: the web page's `window.open` (`settings-accounts.tsx:147`) —
     the desktop does the same second open (`accounts.rs:508`), so **the
     desktop on Windows double-opens too**. Pre-existing engine-side
     Windows gap the web inherited, not a web regression. Claude is
     immune (no child), Cursor is immune (`openBrowser: false`).
- **No web-only double-open exists** — do not hunt for one in React:
  `addAccount` is an event handler (no effect double-fire), and React
  StrictMode (`main.tsx:12-17`) does not double-invoke event handlers.
  The `window.open` at `:147` is the only open call site in the web app
  besides markdown links (`components/markdown.tsx:357`).
- Secondary web-only robustness fact: `window.open` runs after an
  `await` of the RPC, so it has lost the user-gesture context — popup
  blockers may swallow it (`settings-accounts.tsx:144-147`); if that
  happens the CLI's tab is the only one, masking the bug intermittently.
- The wire: `AgentLoginStart` is `#[serde(rename_all = "camelCase")]`
  with a TS derive (`crates/proto/src/entities.rs:799-805`); the TS type
  `web/packages/proto/src/generated/AgentLoginStart.ts` is generated by
  wiregen (`cargo run -p wiregen`; freshness gate `-- --check`, CI
  `web-codegen.yml`). Engine and web client ship together (the engine
  serves the bundle), so no version-skew handling is needed.

## 2. Spec

### 2.1 Engine: the `cliOpensBrowser` flag on `AgentLoginStart`

**Wire shape** — `AgentLoginStart` gains one field:

```rust
pub struct AgentLoginStart {
    pub login_id: String,
    pub url: String,
    pub mode: AgentLoginMode,
    /// True when the spawned CLI opens the authorization page itself
    /// (the engine could not suppress it) — clients must not open it too.
    #[serde(default)]
    pub cli_opens_browser: bool,
}
```

(`#[serde(rename_all = "camelCase")]` already on the struct serializes it
as `cliOpensBrowser`; the `#[serde(default)]` keeps hand-rolled JSON
fixtures parsing. After the change, regenerate
`web/packages/proto/src/generated/AgentLoginStart.ts` via
`cargo run -p wiregen`.)

**Semantics** — "the engine knows the authorization page will (or did)
open in a browser on the engine's machine, and the client's own open
would duplicate it." Set true only where the CLI's open is known
unsuppressed:

| flow | flag | why |
| --- | --- | --- |
| codex, unix, `BROWSER`-noop applied | `false` | the CLI's open was suppressed — the client's open is the one tab |
| codex, unix, noop write failed | `true` | suppression unavailable — the CLI will open |
| codex, Windows (any non-unix) | `true` | `webbrowser` ignores `BROWSER` off-unix (`agent_accounts.rs:1839-1842`) — the CLI opens |
| claude | `false` | no child; the client's open is the one tab (`:534-564`) |
| cursor | `false` | shim runs with `openBrowser: false` (`shim.mjs:133-135`) |

**Implementation** — in `start_codex_login`, thread the suppression
outcome into the reply: set a local `cli_opens_browser = !suppressed`
exactly where the `#[cfg(unix)]` branch sets the `BROWSER` env
(`agent_accounts.rs:620-623`), so the flag and the env can never drift
apart. Claude's and Cursor's replies set the field to `false` explicitly.

**Data** — no RPC method changes; only the `StartAgentLogin` reply
gains a field.

### 2.2 Desktop: gate the client's open

**Change** — in `start_login`'s reply handler (`accounts.rs:502-529`):

```rust
if !start.cli_opens_browser {
    cx.open_url(&start.url);
}
```

replacing the unconditional `cx.open_url(&start.url)` at `accounts.rs:508`.
The paste-code/browser dialog branching (`:509-528`), the poll loop, and
the click-only "Reopen the authorization page" link (`:932-949`) are
untouched — that link is the user's manual escape whenever the automatic
open didn't land anywhere useful (remote/headless engine).

### 2.3 Web: gate + popup-gesture hardening in `addAccount`

**Current flow** (`settings-accounts.tsx:138-158`):

1. Click "Add account" → `setLogin({ kind: "starting", harness })`.
2. `await startAgentLogin(client, harness, target)` (`lib/accounts.ts:67-76`).
3. `window.open(start.url, "_blank", "noopener,noreferrer")` (`:147`) —
   after the await, gesture lost.
4. Dialog: paste-code submit (`:160-183`) or browser poll (`:84-114`,
   1.5s `pollAgentLogin` loop, `lib/accounts.ts:265-300`).
5. Reopen anchors: `:482-484` (paste-code), `:521-523` (browser).

**Target flow** (same handler, `addAccount`):

1. Click "Add account" → **synchronously, before any await**:
   `const tab = window.open("about:blank", "_blank");` — the call runs
   inside the user gesture, so popup blockers cannot eat it. Note: no
   `noopener` here — the handle is needed to navigate the tab; sever the
   relationship instead (step 4).
2. `setLogin({ kind: "starting", harness })` (unchanged).
3. `await startAgentLogin(client, harness, target)`.
4. On success:
   - If `start.cliOpensBrowser === true` → `tab?.close()` — the CLI's tab
     is the one; keep the dialog with its Reopen link.
   - Else → `tab.location.href = start.url; tab.opener = null;` —
     navigate the pre-opened tab, then sever the opener reference
     (preserving the `noopener` security property the old call had).
   - Then the unchanged `setLogin` branching (paste-code / browser).
5. On RPC failure → `tab?.close()` and the existing error path
   (`setLogin(null)` + `setActionError`, `:153-156`).
6. If the synchronous `window.open` returned `null` (hard blocker):
   fall back to the old post-RPC `window.open(start.url, "_blank",
   "noopener,noreferrer")` when `!start.cliOpensBrowser` — and in every
   case the dialog's Reopen anchor remains the final manual fallback.

**States** — the login state machine (`starting` / `paste-code` /
`browser`) is untouched; only the open side-effect moves and gains a
gate.

**Interactions** — none new. The Reopen anchors stay exactly as they are
(`:482-484`, `:521-523`).

**Motion / Text** — none changed.

**Data** — reads `start.cliOpensBrowser` (the regenerated proto type);
writes nothing new.

### 2.4 Untouched flows (documented so they stay untouched)

- **Claude**: no child process, `mode: PasteCode`; the client opens once —
  immune before and after.
- **Cursor**: shim `openBrowser: false`; the client opens once — immune.
- **Dialog copy** (including the browser-flow wait line "Waiting for the
  browser…"): unchanged. The research suggested an explicit "A tab opened
  on the engine's machine" line until the flag exists; the flag exists
  after this ticket, so skip the rewording (scope stays tight).

## 3. Pure logic to port

None from the research — S3 is a wire flag plus gating at two call
sites, and **no desktop unit test covers the login browser-open
behavior** (the login flows are integration-level: they spawn a real
CLI; the unix suppression itself has no test). Say so rather than
inventing a test. The only checkable web-side unit is the flag's type
flow, which the wiregen-generated `AgentLoginStart.ts` and
`pnpm -r build` typecheck cover; do not add a test that merely asserts a
generated type.

## 4. Gaps this ticket closes

Copied verbatim from research S3.d:

| item | kind | desktop value (file:line) | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- |
| Codex login opens exactly one tab on Windows | WRONG BEHAVIOR (engine) | CLI opens (unsuppressed, `agent_accounts.rs:620-623` unix-only noop) + app opens `accounts.rs:508` | CLI opens + `window.open` `settings-accounts.tsx:147` | engine flag on `AgentLoginStart`; clients gate their open on it |
| Cursor/claude single-tab | OK | `openBrowser:false` `shim.mjs:133-135`; claude URL built in-process `agent_accounts.rs:534-564` | same engine reply; one `window.open` | none |
| Popup-gesture safety for `window.open` after RPC | N/A | `cx.open_url` has no gating | `settings-accounts.tsx:144-147` | pre-open a blank tab in the click handler, navigate it post-RPC |
| Login dialog "Reopen …" link | OK | `accounts.rs:932-949` | `settings-accounts.tsx:482-484, 521-523` | none — parity |

## 5. Do not

- Do not hunt for a web-only double-open in React — none exists
  (event handler, no effect double-fire; StrictMode
  `main.tsx:12-17` does not double-invoke handlers). The second tab is
  spawned by the engine machine's CLI.
- Do not port the unix `ensure_noop_browser` script or `BROWSER` env
  games to Windows — `webbrowser` ignores `BROWSER` off-unix
  (`agent_accounts.rs:1839-1842`); the flag is the Windows lever.
- Do not change the Claude or Cursor login flows — they are immune
  (no child / `openBrowser: false`).
- Do not remove or restyle the dialog's "Reopen" links
  (`settings-accounts.tsx:482-484`, `:521-523`; `accounts.rs:932-949`) —
  they are the fallback for remote/headless engines and confirmed parity.
- Do not drop the `noopener` security property on the final navigation —
  the blank-tab handle is needed, so sever `tab.opener` after navigating
  instead.
- Do not hand-edit `web/packages/proto/src/generated/*` — regenerate via
  `cargo run -p wiregen` (CI `web-codegen.yml` gates freshness).
- Do not touch the settings IA / drawer removal (ticket 45) or the
  Remote access refresh (ticket 46).
- Do not restore removed cloud/sync concepts; do not add a native folder
  picker.

## 6. Acceptance

- [ ] Add account (codex) on Windows → exactly one browser tab opens
      (engine and client on the same machine: the CLI's tab, not two).
- [ ] Add account (codex) on the web client against the local Windows
      engine → exactly one tab (same check through the browser).
- [ ] Add account (claude) → one tab, paste-code flow unchanged; Add
      account (cursor) → one tab.
- [ ] With the browser's popup blocker enabled, the client's tab still
      opens (pre-opened synchronously inside the click).
- [ ] Remote-engine case (`cliOpensBrowser` true, engine not on the
      user's machine): no automatic client tab; the dialog's Reopen link
      opens the page on demand.
- [ ] Desktop/engine green: `cargo check -p roboco-proto -p
      roboco-engine -p roboco-ui`, `cargo test -p roboco-engine
      agent_accounts`, and `cargo run -p wiregen -- --check` (the
      regenerated `AgentLoginStart.ts` is in the diff).
- [ ] Web green: `pnpm -r build` (typecheck picks up `cliOpensBrowser`)
      and package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists (no CSS
      touched).

## Comments

### Implementer note (2026-09-19)

Implemented as specced, no deviations:

- `crates/proto/src/entities.rs`: `AgentLoginStart` gained
  `#[serde(default)] cli_opens_browser: bool` with the spec's doc comment
  (serializes as `cliOpensBrowser`; `default` keeps old JSON fixtures
  parsing).
- `crates/engine/src/agent_accounts.rs`: in `start_codex_login` the flag is
  computed in the same expression that sets `BROWSER` (a `match` on
  `ensure_noop_browser(...)` — `Some` applies the env and yields `false`,
  `None` yields `true`), so flag and env cannot drift; the non-unix arm is
  a paired `#[cfg(not(unix))] let cli_opens_browser = true;` matching the
  file's existing cfg-pair style (`write_file_atomic` :1991-1997). Claude
  and cursor replies set `cli_opens_browser: false` explicitly. The two
  adjacent comments (double-tab history :615-622, URL-scan :649-651) were
  touched only to stop describing the pre-flag behavior.
- `crates/ui/src/settings/accounts.rs:508`: the client open is now
  `if !start.cli_opens_browser { cx.open_url(&start.url); }`; dialog
  branching, poll loop, and the Reopen link untouched.
- `web/packages/app/src/routes/settings-accounts.tsx` `addAccount`:
  `window.open("about:blank", "_blank")` runs synchronously first (no
  `noopener` — handle needed), then post-RPC: `cliOpensBrowser` →
  `tab?.close()`; else navigate `tab.location.href = start.url` and sever
  `tab.opener = null` (noopener property preserved); `tab === null` →
  old `window.open(start.url, "_blank", "noopener,noreferrer")` fallback;
  RPC failure → `tab?.close()` + existing error path. State machine and
  Reopen anchors untouched. No CSS touched.
- Wire types regenerated via `pnpm codegen` (only
  `AgentLoginStart.ts` changed — gained `cliOpensBrowser: boolean`); no
  other consumers found (the m5c integration test and the web test fakes
  assert unrelated fields / are untyped stubs).

Verification (worktree, Windows): `cargo check -p roboco-engine
-p roboco-proto` green; `cargo check -p roboco-ui` green (warnings all
pre-existing, none in touched files); `cargo test -p roboco-engine
agent_accounts` 15/15; `cargo test -p roboco-engine --test
m5c_accounts_uploads_titles` 12/13 — the one failure
(`cursor_login_flow_spawns_shim_and_auto_activates`, os error 193
spawning the `.mjs` shim) reproduces identically with this branch's
changes stashed, i.e. pre-existing on this machine, unrelated to the
ticket; `pnpm codegen:check` exit 0 ("wire types are fresh");
`pnpm -r build` exit 0; `pnpm test` in `web/packages/app` 72 files /
1165 tests green. Per §3, no new tests were invented for the flag logic
(spec says the type flow is covered by wiregen + build typecheck).

