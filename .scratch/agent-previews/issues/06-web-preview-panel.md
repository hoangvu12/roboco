# 06 — Web preview panel: loopback framing, honest off-machine note

**What to build:** The web panel, built on what already exists: `web/packages/app/src/state/preview.ts` (`usePreviews` — the per-chat `WatchPreviews` subscription with its 2 s resubscribe loop, currently state-layer only, unrendered) and `web/packages/app/src/lib/preview.ts` (the loopback framing gate: a browser whose engine endpoint is loopback shares the engine's loopback namespace, so an iframe at `http://{device}.{service}.localhost:7331` renders the dev server, WS/HMR included — SSH port-forwards count, which is why the gate keys on the endpoint). Ship the panel component: when `previewProxyReachable(engineBaseUrl)` is true, rows open an iframe of `service.url(snapshot.proxy_port)` (sandbox attributes decided in-ticket against the app's existing iframe usage); when false, render the existing honest note for off-machine browsers ("Open this engine preview from a device that shares its loopback, or SSH-forward the engine port and the proxy port (7331)"). Wire it where the desktop puts it — beside the transcript for the active chat. No off-machine relay, no ServiceWorker bridge, no WebRTC (Phase D, out of scope per spec).

**Why now:** the state layer is complete and tested (`tests/preview.test.ts`); only the component is missing. Cheap, user-visible, and it documents the boundary honestly.

**Blocked by:** none (independent of 03/04/05 — works against any engine whose endpoint is loopback).

**Status:** ready-for-agent
**Parent:** `.scratch/agent-previews/spec.md`

**Verification budget:** `pnpm --filter @roboco/app test` (vitest: panel state, gate behavior), `pnpm -r build` typecheck.

- [ ] The panel renders the live preview list for the active chat (subscription, resubscribe, empty states matching the desktop vocabulary)
- [ ] Loopback endpoints (including SSH port-forwards) frame previews in-app with HMR/WebSocket intact
- [ ] Off-machine endpoints show the honest note, not an error or a broken iframe
- [ ] No component ships a remote/relay path (out of scope)

## Comments
