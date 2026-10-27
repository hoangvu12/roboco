# 06 — Web preview panel: loopback framing, honest off-machine note

**What to build:** The web panel, built on what already exists: `web/packages/app/src/state/preview.ts` (`usePreviews` — the per-chat `WatchPreviews` subscription with its 2 s resubscribe loop, currently state-layer only, unrendered) and `web/packages/app/src/lib/preview.ts` (the loopback framing gate: a browser whose engine endpoint is loopback shares the engine's loopback namespace, so an iframe at `http://{device}.{service}.localhost:7331` renders the dev server, WS/HMR included — SSH port-forwards count, which is why the gate keys on the endpoint). Ship the panel component: when `previewProxyReachable(engineBaseUrl)` is true, rows open an iframe of `service.url(snapshot.proxy_port)` (sandbox attributes decided in-ticket against the app's existing iframe usage); when false, render the existing honest note for off-machine browsers ("Open this engine preview from a device that shares its loopback, or SSH-forward the engine port and the proxy port (7331)"). Wire it where the desktop puts it — beside the transcript for the active chat. No off-machine relay, no ServiceWorker bridge, no WebRTC (Phase D, out of scope per spec).

**Why now:** the state layer is complete and tested (`tests/preview.test.ts`); only the component is missing. Cheap, user-visible, and it documents the boundary honestly.

**Blocked by:** none (independent of 03/04/05 — works against any engine whose endpoint is loopback).

**Status:** implemented
**Parent:** `.scratch/agent-previews/spec.md`

**Verification budget:** `pnpm --filter @roboco/app test` (vitest: panel state, gate behavior), `pnpm -r build` typecheck.

- [x] The panel renders the live preview list for the active chat (subscription, resubscribe, empty states matching the desktop vocabulary)
- [x] Loopback endpoints (including SSH port-forwards) frame previews in-app with HMR/WebSocket intact
- [x] Off-machine endpoints show the honest note, not an error or a broken iframe
- [x] No component ships a remote/relay path (out of scope)

## Comments

**2026-10-26 — implemented.**

**What landed** (`web/packages/app`):

- `src/components/preview-panel.tsx` (new): `PreviewPanel` (the registry's context-bound surface body) + `PreviewPanelBody` (the testable core — `session` as a prop, the Composer's pattern). The list mirrors the desktop `preview_body` (subtitle “Running locally/on your device”, 56px rows keyed by `service.id`, name over `localhost:port` / `MacBook · localhost:port`, trailing Open pill, the three empty states through `lib/preview.ts`'s `previewEmptyCopy`); a row click frames `previewUrl(service, snapshot.proxyPort)` in an iframe (toolbar: back-to-list, name, URL, reload, open-in-tab; frame keeps its URL through discovery dropouts; `key` remount = reload). Sandbox decided in-ticket — there is **no prior iframe usage in `web/packages/app`** (this is the app's first): `allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox allow-downloads allow-modals`, no `allow-top-navigation*` (rationale in the component doc comment: the preview origin is always cross-origin to the app by construction, so `allow-same-origin` preserves only the dev server's own origin — localStorage/cookies/HMR state — never access to the parent; top-navigation stays blocked so dev-server JS can never navigate the app shell away).
- Wiring: `state/right-pane.ts` gains `{ kind: "preview" }` (id-less, one per pane — `addPreviewSurface` re-activates instead of duplicating, since the panel owns the one `WatchPreviews` subscription; `describe` titles the chip “Previews”); `components/surface-registry.tsx` registers it (globe icon — the desktop `RightSurface::Browser` icon) and `components/surface-picker.tsx` adds the “Previews” row in the desktop's Browser slot (first, picker + `+` menu). Placement is the right pane beside the transcript — where the desktop hosts its Browser surface; the desktop's arbitrary-address browsing stays absent. `styles/app.css` gains the `.preview-*` section (row/empty/note geometry from view.rs; toolbar on the shared `surface-toolbar` chrome row).
- `lib/preview.ts`: `previewOffMachineNote()` carries the ticket's note wording verbatim; the header's off-machine paragraph now cites the agent-previews spec's Phase D instead of the old ticket-15 wording.
- Tests: `tests/preview-panel.test.ts` (new, jsdom, createElement + fake `EngineClient` driving the real `usePreviews`): subscription params/rows, empty-state vocabulary, remote labels + error stand-down, unmount cancel, reachable → sandboxed iframe at `http://{device}.{service}.localhost:7331` (sandbox contents asserted, top-navigation asserted absent) + back-to-list, SSH-forwarded `127.0.0.1:19999` reachable, off-machine → note verbatim + disabled rows + no iframe, null session → note; the 2s resubscribe loop and the unknown-method single degrade. `tests/preview.test.ts` pins the note copy. `tests/right-pane.test.ts` covers the one-per-pane store semantics.

**Evidence** (commands + results):

- `pnpm --filter @roboco/app test` (from `web/`): **2542 passed, 6 failed — the same 6 pre-existing failures as the clean base tree at f9bdc6402** (`tests/account-usage.test.ts` ×2, `tests/composer-draft.test.ts` ×2, `tests/limited-banner.test.ts` ×2, plus 2 pre-existing unhandled-rejection errors from `limited-banner`/`transcript-completion-notice-control`; jsdom popover-mount issues, unrelated to previews; identical before my diff). +13 new tests (8+10+2 file counts: preview 7→8, preview-panel +10, right-pane 40→42), all passing. Targeted: `npx vitest run tests/preview.test.ts tests/preview-panel.test.ts tests/right-pane.test.ts` → 60/60 green.
- `pnpm -r build` (from `web/`): proto ✓, engine-client ✓, app ✓ (tsc --noEmit + vite build; only the pre-existing >500 kB chunk warning).

**Checkbox notes:**

- *HMR/WebSocket “intact”* is demonstrated by construction, not by a live round trip in this environment (no engine+dev-server run here): the iframe loads the engine proxy's origin directly with no app-side mediation, the sandbox preserves scripts + the frame's own origin, and WebSocket upgrades through the proxy are the engine's existing tested passthrough (`crates/preview`). The framing itself (src/sandbox/rendering) is asserted in the component test.
- Every criterion was otherwise exercised by the tests above; no criterion left unmet.

**Residuals/gaps (none blocking):**

- A dev server that itself sends `X-Frame-Options: DENY`/`SAMEORIGIN` (rare; some framework defaults) will refuse to frame in any browser — dev-server configuration territory, not actionable panel-side.
- WebKit-family browsers without `*.localhost` synthesis (spec's platform-browser research) resolve the frame's hostname via the system resolver; on such setups the frame shows a resolve failure. The spec accepts this (the web client's framing target is Chromium/Firefox, which synthesize).
- The framed view's in-panel state (which service is framed) resets when the surface is backgrounded (another tab picked) because the pane host mounts only the active surface — the same lifecycle every right-pane surface has; the list and subscription resume on remount.

**Final commit:** `e9964c2c3` (`feat(web): preview panel — loopback framing, honest off-machine note`, ticket 06; this record rides on top).

- 2026-10-26 (orchestrator): merged `ap/06-web-preview-panel` into `feat/agent-previews` (merge `da91b46ab`, implementation `e9964c2c3`). Verification re-run at the merged tip: `pnpm --filter @roboco/app test` 2542 passed / 6 failed — the 6 (account-usage ×2, composer-draft ×2, limited-banner ×2) independently reproduced as pre-existing on the pristine base `f9bdc6402` (detached-worktree run: identical 6/78 in those files); `pnpm -r build` (web/) — proto, engine-client, app all build. No dependents; ticket complete.
