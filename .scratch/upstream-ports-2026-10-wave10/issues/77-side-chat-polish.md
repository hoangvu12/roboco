# 77 — Side-chat polish on Android: fork feedback, spawn-card details, subagent tap haptic (I3, N6, G2) — decision needed

**Lane:** 5 Parked. **Effort:** S total if accepted.

Wave-9 23 keeps "the side chats + subagents surfaces in the current
shape". These three items are feedback and detail on that shape, not a
surface change, so they need the user's call:

1. **I3: "Fork into Side Chat" gives no feedback while it works.** The
   iPhone shows a "Forking…" toast at once and plays a success haptic
   when the fork opens. Android only disables the ⋯ row
   (`sidechats/SideChatFork.kt:58-75`; toaster already injected). Upstream
   `Session/SessionViewController.swift:835-845`. S.
2. **N6: subagent spawn-card details (mobile core).** The spawned model
   as a faint trailing label (`claude-haiku-9` → "Haiku 9"), a red icon on
   a failed spawn, a chevron on openable cards, and TalkBack state
   (`Subagent: <title>`, Working / Failed / Done). Upstream
   `crates/mobile/src/layout/tools.rs:61,153,1143-1200` + test
   `spawn_models_read_as_names`; Roboco `tools.rs:1108-1156`,
   `transcript/widgets/SubagentLink.kt:57`. S.
3. **G2 nit: the subagent card tap haptic** is a selection tick on
   Android and a light impact on the iPhone
   (`SubagentLink.kt:54` vs `RowView.swift:333`). One line.

Desktop's fleet fork feedback is ticket 23 (A46), which is not parked.

**Blocked by:** user decision.

**Status:** needs-triage

**Source:** `pass3-ios-1.md` G3; `pass2-features-android.md` N6;
`pass3-ios-2.md` G2.

- [ ] User decision recorded (all / some / none). Accepted items become an Android ticket.

## Comments
