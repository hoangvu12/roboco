# 05 — Leave authentication callback listeners unprobed (preview)

**What to build:** Port upstream `612df512` (#763): the preview
discovery cycle HTTP-probes every project-owned loopback listener once
to classify dev servers — and agent-CLI login flows bind single-use
loopback OAuth callback listeners, so the probe's request lands on the
callback and breaks sign-in. The fix excludes authentication callback
listeners from probing.

**Roboco gap (verified):** the scanner's only listener filters are
`PREVIEW_PROXY_PORT` + own pid (`crates/preview/src/service.rs` scan
closure ~220-225) plus root association — no callback-route exclusion —
while the registry it should consult exists: `CallbackRoutes`
(`crates/preview/src/login.rs:49`), service fields
`callback_routes`/`callback_tunnels` (`service.rs:97-100`) and the
`callback_routes()` accessor (`service.rs:119-121`). The
probed-listener test substrate the fix extends also exists:
`crates/preview/tests/discovery.rs:107-156` ("A discovered dev server
receives exactly one probe for its lifetime") and the `ProbeMemory`
tests (`service.rs:330-376`).

**Adaptation notes:** `CallbackRoutes` exposes
`register/remove/is_registered/target` — the port likely needs a ports
snapshot passed into the scanner task (the scan closure clones
`projects` but not the routes). Watch the `ProbeMemory` interplay: an
unprobed-but-registered listener must not be re-planned every 2s
cycle. The surface is fully engine-local (ADR 0004; `login.rs:13`
documents the P2P callback surface staying engine-local) — no cloud
coupling, no web surface.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `612df512` (#763) — `crates/preview/src/discovery.rs`
(+75), `crates/preview/src/service.rs` (+1),
`crates/preview/tests/discovery.rs` (+114),
`docs/preview-networking.md` (+16) → identical paths here (preview
crate is un-prefixed). Source: `.scratch/upstream-drift/2026-10-05.md`
§ #763.

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-preview` (discovery tests; covered by
`preview-tests.yml` in CI).

- [ ] Registered callback listeners are never probed
- [ ] ProbeMemory semantics preserved (no re-planning churn for
      skipped listeners)
- [ ] Callback-port exclusion test added to `tests/discovery.rs`
- [ ] `docs/preview-networking.md` updated
- [ ] Port commit records the upstream SHA
