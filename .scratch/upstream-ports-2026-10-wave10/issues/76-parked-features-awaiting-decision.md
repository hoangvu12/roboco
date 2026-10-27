# 76 — Parked: PR board, native Codex voice, native Luau visuals, Cursor SDK CI bot (decision needed)

**Lane:** 5 Parked. **Effort:** n/a until decided.

Not planned in detail, by instruction. Each item needs a user yes/no.
On a yes, it gets its own ticket(s).

| Item | Upstream | Size | Notes |
|---|---|---|---|
| **PR board + PR side panes** | `afca583c1` (#484), `66524017f` (#866), `d84ef73af`, `a408a8140` repository menu, `ea3dcc811` PR-view destinations | L+L | Carries wave-7 21. `engine/src/source_control.rs` +2710 (SSH-alias resolution, review threads), `pull_requests.rs`, `pull_request_pane.rs`, `chat_pull_requests.rs`, PR settings keys. PR-link "open in-app" needs the browser, which is being removed; a yes means default-browser links only. |
| **Native Codex voice** | `4a5fb8b44` (#819), `8930cd103` (#834) | L | New `audio`/`orb`/`veil`/`voice-session`/`voice-media` crates, `codex/realtime*`, `shell/voice_stage.rs`. Android dropped voice (ADR 0009). The full-id reply bit is superseded by ticket 29's `agent_messages`. |
| **Native Luau visuals** | `8a0fded89` (#900) | L | `crates/visual*`, `ui/src/visuals.rs`, `show_visual` MCP tool, `AgentEvent::Visual`, a mobile-core `visual/` slice. Ticket 75 adds Luau *syntax highlighting* only. |
| **Cursor SDK compat job + daily bump bot** | `1d00f2960` | S | `.github/workflows/cursor-compatibility.yml`, `cursor-sdk-update.yml`, release gate, `scripts/{test-cursor-sdk-auth.mjs,update-cursor-sdk.py,test-update-cursor-sdk.py}`. Left out by the wave-3 CI policy (a spec rule, not a user decline). On a yes it belongs in `.scratch/ci-speed/`. |

**Blocked by:** a user decision per row.

**Status:** needs-triage

**Source:** `2026-10-28.md` §F; `part-06.md` (#484, #819, #834, #900, #866);
`part-01.md` `1d00f2960`.

- [ ] User decision recorded per row (yes → new ticket; no → wontfix note here)

## Comments
