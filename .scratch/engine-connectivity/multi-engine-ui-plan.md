# Multi-engine UI parity plan (zeron PR #526 → roboco)

**Source of truth for UX:** [zeronsh/zeron PR #526](https://github.com/zeronsh/zeron/pull/526) — especially post-fleet commits (not WorkOS auth). Roboco keeps **pairing + `baseUrl` fleet keys**; zeron uses relay **`setActiveDevice`**. Port **patterns**, not transport.

**Problem:** Connection works, but engine choice is inconsistent: settings-only `SettingsEngineIndicator` / devices “Switch here”, duplicate device rows, composer still reads “This device”, merged fleet duplicates projects/devices, add-space pins wrong engine.

**Goal:** One **resolved canvas target** (`resolveNewChatTarget`) drives session routing, composer chips, and send gating; settings engine pill only retargets **settings pages**, not the whole app; pickers use **Select engine** semantics everywhere.

## Reference commits (zeron → roboco tickets)

| Commit | Topic | Ticket |
|--------|--------|--------|
| `b20e5bfd` | `new-chat-target` + canvas routing | **86** |
| `4928e1b2` | `targetForDevicePick` / `targetForProjectPick` | **86**, **88** |
| `4510bab2` | Engine chip labels + registry presence | **88** |
| `5cd23bd7` | `fleetSpaceRows` / `fleetDeviceRows` dedup | **87** |
| `3e14656f` / `460b7c89` | Add-space `#sessionForDevice` | **89** |
| `f180fcb1` / `d57b27fd` | Send blocked when target unresolved | **90** |
| `97f86114` | Settings engine name from host device row | **91** |
| `779cc2e0` | Devices page: one list, no duplicate engine card | **92** |
| `631a8e03` / `6e4f363` | Harness/model on selected engine | **93** |

## Priority

1. **P0** — 86, 87 (routing + deduped fleet data)
2. **P1** — 88, 89, 90 (composer + add-space + send gates)
3. **P2** — 91, 92 (settings labels + devices IA)
4. **P3** — 93 (catalog validation)

## Acceptance (two live engines)

Use two independently running engines with distinct `baseUrl`s in one browser profile:

- Canvas device chip switches **execution engine**; project chip follows owner.
- New chat / add-space RPCs hit **owning** engine without visiting Settings.
- Sidebar shows each project/device **once**.
- Settings indicator switches Accounts/Agents/Remote only; composer does not require Settings to change engine.

## Agent workflow

Tickets live in `.scratch/web-parity/issues/86-*.md` … `93-*.md`. Status `ready-for-agent`. Subagents: research zeron file at commit SHA listed in each ticket before implementing.

## Completion (2026-10-08)

Executed via the implement-feature orchestration (4 waves, one isolated
worktree/branch per ticket, per-ticket candidate → review round →
integration). All eight tickets are `ready-for-human` on `main`:

| Ticket | Commits | Zeron refs |
|---|---|---|
| 86 | closed via 88's wiring (module/routing pre-landed `9101f8c5`) | `b20e5bfd`, `4928e1b2` |
| 87 | `2ed91164`, `129f15db`, `e46bcb72` | `5cd23bd7` |
| 88 | `301bef26`, `846764ac` | `4510bab2`, `4928e1b2` |
| 89 | `2cf5e877`, `e4c7fd1e` | `3e14656f`, `460b7c89` |
| 90 | `efd8968d`, `ee66a18f` | `f180fcb1`, `d57b27fd` |
| 91 | `9b4cc198`, `3ead651b` | `97f86114` |
| 92 | `647502de`, `2b569077` | `779cc2e0` |
| 93 | `34629422`, `451d3f6e` | `631a8e03`, `6e4f363` |

Integration fixes: `53e89958` ports zeron `4510bab2`'s non-composer
label sweep (deferred from 88, caught by the final spec review);
recorded deviations — roboco keeps the devices This device/Other
devices split (92's landed design) and the DeviceSwitcher device label
(91's ruling).

Final verification on the integration branch: full app vitest
**2405/2405** (165 files; base 2324 + 81 from these tickets), `pnpm -r
build` green (tsc + vite, all web packages), focused suites per ticket
green, per-ticket review rounds green. engine-client's
conformance/smoke vitest was NOT run: the package is untouched by this
feature and CI path-filters that job to `engine-client/**` changes
(its cargo example build exceeds the 600s hook timeout in a cold
worktree); codec/fake-server suites passed.

**Remaining human check:** the plan's two-live-engine acceptance run
(two engines with distinct baseUrls in one browser profile — canvas
chip switches engine, RPCs hit the owning engine, one row each,
indicator retargets settings only). All four bullets have
unit/mounted-seam evidence in the ticket records; the live run needs
real engines and a browser, which this environment does not provide.
