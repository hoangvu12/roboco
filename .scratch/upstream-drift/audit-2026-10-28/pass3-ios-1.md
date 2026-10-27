# Pass 3 — iPhone slices of `ios-1.tsv` (24 commits) vs Roboco Android

Read-only. Upstream `upstream/main` = `31d26eeb6`; Roboco = `HEAD` (`7df761c7f`).
For each commit I read the `apps/ios` hunks, plus any `crates/mobile*`, `crates/text` and
`crates/markdown` hunks. Each iPhone behaviour is checked against
`apps/android/app/src/main/java/sh/roboco/android/**` (written `A/` below) and Roboco's
`crates/mobile` / `crates/mobile-client`.

Status words:
- **PRESENT**: on Android today.
- **KNOWN**: missing, and already in `2026-10-28.md` §B/§C, `pass2-features-android.md` (N1–N7) or a wave-9 ticket.
- **NEW**: missing and not recorded anywhere.
- **DECLINED**: a recorded user decision.
- **N/A**: iOS-only mechanics, cloud, or dropped by ADR 0009.

Summary: 3 NEW gaps (G1–G3). Everything else is PRESENT, KNOWN, DECLINED or N/A.
One correction to pass 2: `crates/mobile/src/caption.rs` is the **voice-call caption veil**
(from #819), not #1062's sender caption. It belongs with voice (dropped), not KNOWN #1062.

---

## 64a26d359 #1022 — Questions: answered record + close without answering

| iPhone behaviour | Android | Evidence |
|---|---|---|
| Corner × "Close without answering" (36pt glass capsule, 8pt in), submits no answers (agent reads "dismissed") | KNOWN §B | No close in `A/composer/QuestionCard.kt` |
| Header/question wrap clear of the close (−36pt trailing) | KNOWN (rides the above) | — |
| Resolved question row shows what was answered (`rows.rs`) | KNOWN §B | `crates/mobile/src/layout/rows.rs:444-446` still "Answered a question" |

## d8a14b35b #1068 — messages sent to other chats read as one line

| iPhone behaviour | Android | Evidence |
|---|---|---|
| Outgoing `send_message` reads "Sent to [mark] Chat" in one line (`outgoing_row`, `chat_caption`) | KNOWN §B/§C (A15) | No caption code in `crates/mobile/src/layout/rows.rs` |
| Caption link a11y "Open chat" | KNOWN | — |

## f65c5bc60 #1062 — agent sender caption over the message

| iPhone behaviour | Android | Evidence |
|---|---|---|
| "Message from [mark] Chat" caption, right-aligned over the bubble and never wider than it; a long title fades | KNOWN §C | — |
| Only the body under the routing header goes in the bubble | KNOWN | — |
| Tap the caption: on a phone the sender chat is pushed over this one (Back returns here); on a tablet it takes the detail column; a tap on the chat's own caption does nothing | KNOWN | — |
| Live title/provider from the workspace (a rename updates it) | KNOWN | — |

## 2ea4e3a05 #1014 — tablet composer level with the sidebar toolbar

| iPhone behaviour | Android | Evidence |
|---|---|---|
| Composer rests centred on the sidebar toolbar's measured mid-Y. Otherwise it sits 8pt above the safe area | KNOWN (wave-9 21) | `A/shell/SidebarToolbar.kt`, `A/shell/SplitRoot.kt` |

## 3c97a05cf #1010 — terminal glyphs; subagents chip icon
- Terminal box/block glyph painter: DECLINED (wave-9 23).
- Subagents chip icon: lives in `CompanionStrip`, which was declined. Nothing to port. Wave-9 21 still lists it, so that ticket's third bullet is moot.

## dfbb6876c #1007 — iOS terminals over WebRTC
- All of it is the terminal transport, the `TerminalProbe` debug screen, or the `network_path_changed`/`warm_direct` FFI: DECLINED, and cloud.

## fd7d268ca #988 — terminals, side chats and subagents beside every session

| iPhone behaviour | Android | Evidence |
|---|---|---|
| CompanionStrip beside the status pill; ⋯ "Terminal"; ⌘J; ⌘⌥0; companions sheet and iPad inspector; bar badge with the running-agent count | DECLINED (wave-9 23) | — |
| Subagent card: tap with a press wash, light haptic, opens the viewer | PRESENT | `A/transcript/widgets/SubagentLink.kt:40-57` (Select haptic) |
| Subagent card a11y "Subagent: title", value Working/Failed/Done | KNOWN N6 | `SubagentLink.kt:57` ("Open subagent: …") |
| ⋯ New Side Chat / Fork Into Side Chat | PRESENT (Roboco's #899 shape) | `A/session/SessionMenu.kt`, `A/sidechats/SideChatFork.kt` |
| **Fork feedback: a "Forking…" toast at once, and a success haptic when the fork opens** | **NEW (G3)**, user call | `A/sidechats/SideChatFork.kt:58-75`: silent until it opens; a toast only on failure |
| A side chat titled "Side chat" with the parent as subtitle | PRESENT (variant) | `A/sidechats/SideChatDraft.kt:24-29` |
| Unused fresh side chat deleted on leave | SUPERSEDED. Android's draft writes nothing until the first send | `A/sidechats/SideChatDraft.kt:31-40` |
| Fork error copy | PRESENT | `SideChatFork.kt:27-32` |

## 987bb2f87 #978 — Muse brand mark + all-providers debug list
- Muse mark/colour: DECLINED (Muse). The debug `-all-providers` list is N/A.

## 6f2721fab #981 — keep the session through fold/unfold

| iPhone behaviour | Android | Evidence |
|---|---|---|
| The session page moves between shells as it is: same page, scroll position and draft, no reload under the fold animation | KNOWN §C ("fold continuity", M) | `A/shell/CarryOver.kt:4-27` reopens the chat by id |
| After a reflow for a new width (rotation, fold), the transcript lands on the tail at once instead of spring-gliding | KNOWN §C (S) | — |
| Voice bar item axis behaviour / split nav-bar hide | N/A (voice; UIKit) | — |

## 5b8edb2de #980 — Providers doesn't crash with no device
- iOS-only UIKit abort. Android's accounts page with no paired engine is safe (pass 2). N/A.

## 4cd159175 #971 — no tab bar comes back under the bottom bar

| iPhone behaviour | Android | Evidence |
|---|---|---|
| The shell is a plain container: popping Settings or a session never brings a tab pill back over the bottom bar | KNOWN (wave-9 19) | `A/shell/GlassTabBar.kt` (tab bar still the design) |
| Toast anchors over the new bottom bar | KNOWN (rides 19) | `A/design/ToastPlacement.kt` |

## f30a8641b #967 — Log in from error cards, one-row bottom bar, session delete

| iPhone behaviour | Android | Evidence |
|---|---|---|
| Error card: red wash + lighter red text, "Log in" capsule with a light haptic | PRESENT (PR #34) | `A/transcript/widgets/ActionButton.kt:30-46` |
| "Log in" opens the provider's accounts sheet (medium/large) and **starts sign-in at once** | KNOWN (wave-9 18 + N3) | `TranscriptActions.perform("sign-in")` only pushes Accounts |
| Session **Delete** in the list long-press menu and the ⋯ menu; "Delete session?" alert with "“Title” will be permanently deleted. This can't be undone."; leaves the page (tablet → draft) | KNOWN (wave-9 18) | The core already has `crates/mobile/src/client_ffi/workspace.rs:134` `delete_session`; no Android caller |
| Archive no longer styled destructive | KNOWN (18) | `A/session/SessionMenu.kt:132-134` `destructive = true` |
| Title + "project @ device" move from the bar into the ⋯ menu header (two lines) | KNOWN (18) | `A/session/SessionHeader.kt` |
| One-row bottom bar: Settings · Search (filters the list in place) · New session (prominent); Settings pushed over the list; the bar belongs to the front page only | KNOWN (19) | — |

## cc7da5c2a #966 — Cloud, provider switching, phone model picker

| iPhone behaviour | Android | Evidence |
|---|---|---|
| Model chip (session **and** new-session canvas) opens the tabbed picker sheet: per-provider pages, logo switcher, search once long, "No matches", current-model tint, loading bars, selection/light haptics | KNOWN (wave-9 20) | `A/composer/SessionChipMenus.kt:242-250`, `A/newsession/CheckoutModelEffortMenus.kt:244-266` (plain menus) |
| Other providers' tabs between turns only (`provider_switch`); the notes "You can switch provider when this turn ends." / "…needs a newer Zeron…" / "Your next message continues this chat with X."; a provider change resets effort and options | KNOWN (pass 2 notes on ticket 20) | No `provider_switch` in `crates/mobile-client/src/session/composer.rs:24-36` |
| **A chat with no model of its own: the chip names the host's default model (built-in list at once, then the host's first model), and the picker ticks it** | **NEW (G2)** | `A/session/SessionChips.kt:118` (`modelLabel ?: harnessLabel` → "Claude Code"); `A/composer/SessionChipMenus.kt:306-311` (tick only on `sel.model`); `crates/mobile-client/src/workspace/view.rs:680-681` (label only when a model is set) |
| Provider-switch seam row "Switched from [logo] to [logo]" | KNOWN N1 | — |
| Settings → Providers: per-computer agent on/off | KNOWN N2 | — |
| Add account / device sign-in / paste code | KNOWN N3 | — |
| Onboarding (Make it yours / Your agents / Stay in the loop / You're all set), softened wallpaper behind it | KNOWN N4 | — |
| Toast: filled capsule action, 2-line label | KNOWN N7 | — |
| `brand:<harness>` icon names in rows | PRESENT once N1 lands (Android paints brand marks: `A/design/BrandMarks.kt`) | — |
| Cloud credits notice, Starting on Cloud HUD, Run on → Cloud, Start from, New Cloud Project…, Billing/Cloud settings, sign-in rework | N/A (cloud) | — |

## c526d6f7c #942 — iPhone Duo: fold/unfold, uneven safe areas

| iPhone behaviour | Android | Evidence |
|---|---|---|
| One split shell everywhere. It shows the sidebar when the window is regular in both directions and collapses live otherwise | PRESENT (variant: width only, 840 dp) | `A/shell/RootKind.kt:42-53`, `A/shell/AppRoot.kt:118,139` |
| **A phone in landscape keeps the tab shell even when it is wide enough (compact height pins compact width)** | **NEW (G1)** | `chooseRoot` looks only at width: a Pixel 7 in landscape (~915 dp) gets the tablet split |
| Narrow windows (<700pt) get a 280pt sidebar so an unfolded Duo (669pt) still splits | N/A at Android's 840 dp breakpoint (pass 1, part-06:72). See G1's product note | `A/shell/TabletShellState.kt:39` |
| Composer pinned 8pt above the keyboard; at rest on the safe area (8pt up alone, level beside the sidebar) | PRESENT / KNOWN (#1014) | `A/session/SessionBottomStack.kt` |
| Transcript, header, minimize and new-session hero laid out between **uneven** side insets | KNOWN §C (S) | — |
| Voice stage inset fixes | N/A (voice) | — |

## c02739da3 #912 — zatex LaTeX math
- Math rendering, the Math settings (Render Math / Math Font / Math Size) and the `crates/text` inline boxes: DECLINED (zatex).
- The markdown nesting cap + panic fallback hidden in this commit: KNOWN N5.

## 26c8e8404 #909 — drag sessions between sections (iPhone half)

| iPhone behaviour | Android | Evidence |
|---|---|---|
| Long-press drags a session between Pinned, user sections and Recent, placed between neighbours (`pin_session_at`/`place_session`) | KNOWN §C (#909, no ticket) | Core has `move_pin`/`assign_section` only (`crates/mobile/src/client_ffi/workspace.rs:148,184`); Android has in-Pinned reorder only (`A/sessions/PinReorder.kt`) |
| A folded destination opens on drop; selection haptic; a drag within Recent is refused; search results and project groups are not drop targets | KNOWN (same item) | — |

## 8a0fded89 #900 — native Luau visuals
- `VisualView`, `crates/mobile/src/visual/*`, `WidgetKind::Visual`, the visual loading row: parked, §F decision-gated. Not re-reported.

## edb23db4b #875 — Group by Project

| iPhone behaviour | Android | Evidence |
|---|---|---|
| Settings toggle "Group by Project", subtitle "Sessions outside your sections, under their project" | PRESENT | `A/settings/SessionsSettingsSection.kt:26,73` |
| ⋯ options toggle (rebuilt on each open) | PRESENT | `A/sessions/SessionsMenus.kt:106-110` |
| Groups by repository `groupKey` in recency order; project-less sessions as "Home · device" | PRESENT | `crates/mobile-client/src/workspace/view.rs:1157` |
| Collapse All also folds the project groups | PRESENT | `A/sessions/FrontPageModel.kt:148-153` |

## e799aecc1 #777 — sends stranded by suspension

| iPhone behaviour | Android | Evidence |
|---|---|---|
| "Sending to host…" trailer pill while a send is in flight; working never comes from a pending send | PRESENT ("Sending…", engine-local wording) | `A/session/StatusPill.kt:111`, `A/session/StatusBanner.kt:124-125`, `crates/mobile-client/src/session/composer.rs:5-8` |
| A 25 s background task after Send, so the room outbox reaches the edge | N/A. Roboco has no client outbox (`crates/mobile-client/src/session/commands.rs:4`); a send is one engine RPC, and Android's freezer grace plus `A/background/RunWatchController.kt` cover the moment | — |

## 4a5fb8b44 #819 — session plumbing / packaging (iOS half = voice)
- Voice accessory, VoiceBarItem in the session and sidebar toolbars, the Voice settings row, `zeron://voice`, Live Activity, `caption.rs`/`orb.rs`/`voice.rs`: N/A (voice dropped by ADR 0009; native Codex voice is §F).

## 8e6da9590 #826 — no fixture conversation when an open fails

| iPhone behaviour | Android | Evidence |
|---|---|---|
| A failed open never shows a placeholder conversation | PRESENT | `A/shell/PhoneAppRouter.kt:113,126` |
| Phone: dismiss sheets, IME down, Sessions, pop to root, then the toast "Couldn't open session. Try again." | PRESENT | `A/shell/AppRouter.kt:6`, `A/shell/PhoneAppRouter.kt:182-196` |
| Tablet: the draft page, sidebar back to Sessions with the search cleared, sidebar shown, then the toast | PRESENT | `A/shell/TabletAppRouter.kt:353-367` |
| Tapping a list row whose open fails → same error | PRESENT | `A/sessions/SessionListNavigation.kt:113` |

## 73bd3c842 #775 — mention images and files as chips

| iPhone behaviour | Android | Evidence |
|---|---|---|
| Sent prompts paint file/folder, skill/command and attachment mentions as pills with an icon well | PRESENT (core) | `crates/mobile/src/layout/rows.rs:608` `user_chips` |
| Tap an image chip → its upload full-size in the viewer | PRESENT | `rows.rs:593`; `A/transcript/LinkRoute.kt:17,60` |
| Chipped attachments leave the strip; non-image files get a name pill | PRESENT | `rows.rs:172,895`; `A/transcript/widgets/TranscriptIcon.kt:44-55` |
| Copy reads chips as plain labels | PRESENT (core `copy_text`) | — |

## dbb639be3 #799 — group projects by git identity (iPhone half)

| iPhone behaviour | Android | Evidence |
|---|---|---|
| Project menu: one row per repository (`groupKey`), host names as the subtitle, the project tile icon | PRESENT | `A/newsession/NewSessionProjectMenus.kt:82-87,174-180` |
| Project chip shows `groupName`, plus a separate host chip | PRESENT | `A/newsession/NewSessionChips.kt:27,121` |
| Host chip "Run on" lists only this project's checkouts (path subtitle when a host has several, else Online/Offline). With no project, every host | PRESENT | `NewSessionProjectMenus.kt:230-247`, `A/newsession/NewSessionModel.kt:174-183` |
| Picking a project keeps the host if it has a checkout, else this device, else the first online one | PRESENT | `NewSessionProjectMenus.kt:107` `pickProjectTarget` |

## df0cd2980 #630 — Pi native RPC (question prefill/multiline)

| iPhone behaviour | Android | Evidence |
|---|---|---|
| Multiline questions get a 140pt free-text editor instead of "Other…"; always count as answered; prefill seeds the answer | PRESENT (UI) / KNOWN §B (the wire doesn't carry the fields) | `A/composer/QuestionCard.kt:197,373-395`, `A/composer/QuestionPanelState.kt:118-124,157` |
| Duplicate question ids never crash | PRESENT | `QuestionPanelState.kt:157` (`putIfAbsent`) |

---

## New Android gaps

### G1 — A phone turned to landscape switches to the tablet split (from #942)
- **Effect:** The iPhone keeps its normal one-column phone layout in landscape. iOS treats a window that is short in height as phone-sized whatever its width. Android picks the layout from width alone (≥ 840 dp). A large phone in landscape (Pixel 7 ≈ 915 dp wide, ~400 dp tall) therefore flips into the tablet layout: a sidebar plus a squeezed session column with very little height. Rotating back flips it again, with the carry-over running each time.
- **Upstream:** `apps/ios/Zeron/Shell/SplitRootController.swift:65-80` (`syncCollapse`: compact vertical class → compact horizontal class). Rationale at `:65-67`.
- **Android files to touch:** `A/shell/RootKind.kt:42-53` (`chooseRoot` takes the height too; e.g. height < 480 dp, Material's compact-height class → `RootKind.Phone`); `A/shell/AppRoot.kt:118,139` (pass `maxHeight`); `A/shell/CarryOver.kt` needs no change. Add a `chooseRoot` unit test.
- **Product note (user call, not a gap by itself):** iOS splits whenever the width class is regular. That includes iPad portrait and an unfolded Duo at 669pt, with a 280pt sidebar under 700pt (`SplitRootController.swift:91-97`). Android's 840 dp rule keeps tablets in portrait (Pixel Tablet ≈ 800 dp) and portrait foldables on the phone shell. Pass 1 judged the 280pt rule low value at 840 dp. The landscape-phone case above is the clear parity bug.
- **Effort:** S.

### G2 — A chat with no chosen model shows the agent's name, not the model it runs (from #966)
- **Effect:** On the iPhone, a chat created without an explicit model (the default) has a model chip that names the default model the computer runs, e.g. "Opus 5". The built-in list answers first and the computer's catalog replaces it. The picker ticks that model. Android shows the agent name ("Claude Code") on the chip, and nothing is ticked in the model menu. This hits every chat started from Android's New Session without a model pick, because `NewSessionCreate.kt:103` sends `model = d.model`, which can be null.
- **Upstream:** `apps/ios/Zeron/Session/CoreSessionSource.swift:105` (chip label fallback), `:319` (picker tick `row.model ?? defaultModel(for:)`), `:352-370` (`defaultModel(for:)`: built-in first, the host's `listModels(...).first` replaces it, cached by `device|harness`).
- **Android files to touch:** `A/session/SessionChips.kt:114-122` (label fallback), `A/composer/SessionChipMenus.kt:306-311` (tick fallback), `A/session/SessionChips.kt` filler or `crates/mobile-client/src/workspace/view.rs:680` (or a small cached default-model lookup through the existing `listModels`). It fits naturally into wave-9 20 (the picker): add it to that ticket.
- **Effort:** S.

### G3 — "Fork into Side Chat" gives no feedback while it works (from #988; user call under ticket 23)
- **Effect:** On the iPhone, "Fork Into Side Chat" shows a "Forking…" toast at once and plays a success haptic when the fork opens. On Android the tap only disables the ⋯ row. Nothing is visible until the side chat opens, which can take a while on a slow engine. Only failure toasts.
- **Upstream:** `apps/ios/Zeron/Session/SessionViewController.swift:835-845` (`fork()`).
- **Android files to touch:** `A/sidechats/SideChatFork.kt:58-75` (toast on start, `Haptic.Success` before `onForked`), plus `SideChatForkCopy`. The toaster is already injected.
- **Note:** this is #988's ⋯-menu path. Ticket 23 keeps "the side chats + subagents surfaces in the current shape", so this needs a yes from the user. It is feedback polish, not a surface change.
- **Effort:** S.

## Housekeeping found on the way
- Wave-9 21's third bullet (#1010 subagents chip icon) has nothing to port. The icon lives in the declined CompanionStrip.
- Pass 2's module table lists `caption.rs` as KNOWN (#1062). It is the voice-call caption veil (`4a5fb8b44` #819) → voice, dropped.
- Wave-9 18's Delete can use the existing core call `crates/mobile/src/client_ffi/workspace.rs:134` `delete_session`. No FFI work is needed.
