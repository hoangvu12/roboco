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
