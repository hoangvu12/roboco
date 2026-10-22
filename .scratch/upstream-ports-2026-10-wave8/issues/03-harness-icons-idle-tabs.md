# 03 — Harness icons in idle side chat and subagent tabs (upstream #725, `75b72602a`)

**What to build:** Port `75b72602a` (#725): idle side-chat tabs and
subagent tabs show the harness icon (the running indicator's icon
treatment applied while idle).

**Why now:** single file (`crates/ui/src/shell.rs`, +46/−4); roboco
holds side chats and subagent tabs from the perf-wave ports, so the
surfaces exist.

**Steps:**
1. `git show 75b72602a` — shell.rs only.
2. Port the icon resolution and tab rendering hunks onto roboco's
   `shell.rs` tab strip, adapting to roboco's harness-lookup names if
   they drifted.

**Excluded upstream hunks:** none.

**Blocked by:** None.

**Status:** done (ported in 63495938b)

**Verification budget:** `cargo check -p roboco-ui` + shell tab tests.
Source: `.scratch/upstream-drift/2026-10-22.md` § smalls.

- [x] Icons render in idle side-chat/subagent tabs
- [x] Commit records upstream SHA `75b72602a` (#725)

## Comments

- Ported in commit 63495938b. SubagentTab gains `harness:
  Option<roboco_proto::HarnessId>`, resolved at spawn time from the chat
  config, then open side chats' selected rows, then the parent subagent
  tab (nested chips). The tab strip renders the harness icon (brand
  color when present) for idle SideChat/Subagent tabs; working chats
  keep the mini spinner. Adaptation: roboco's tab strip inlines the
  leading-slot icon (wave-4 #587 restructure) instead of upstream's
  surface_chrome::tab_chip_icon, so the fallback branch maps
  harness_icon onto the inlined `icon()`. Verified: cargo check -p
  roboco-ui clean; shell:: tests 214 passed.
