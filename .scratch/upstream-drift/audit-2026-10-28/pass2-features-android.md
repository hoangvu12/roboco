# Pass 2 — iPhone app feature inventory vs Roboco Android (upstream/main `31d26eeb6` vs HEAD `85be4485`)

Read-only, feature-based (not commit-based) second pass. Source: every Swift file under
`upstream/main:apps/ios/Zeron/**` except `Core/Generated`, plus `ZeronTests`/`ZeronUITests` as
behaviour specs, and `upstream/main:crates/{mobile,markdown,text}`. Compared against
`apps/android/app/src/main/java/sh/roboco/android/**`, `crates/mobile`, `crates/mobile-client`.

Method:
1. Per Swift file, list screens, menus (`UIAction`), swipe actions, alerts, a11y labels,
   haptics and doc-commented behaviours. Then look each one up in Android by copy string and
   by behaviour.
2. Mechanical sweeps:
   - All 453 capitalised string literals from the in-scope Swift: 155 have no match in Android
     or the mobile crates. Each miss was triaged (most are cloud, zatex, voice or terminal copy).
   - Upstream vs Roboco: `client_ffi` `pub fn`s, FFI struct fields, `WidgetKind`/`ColorRole`
     enums, and the item names in `layout/*.rs`.
3. Exclusions (not reported as gaps):
   - cloud/WorkOS/billing/Cloud machines/GitHub;
   - APNs (mapped to local notifications);
   - sync rooms (mapped to engine-local pairing);
   - terminals + CompanionStrip, Muse, commit credit (declined);
   - voice and Live Activity, slash commands (dropped by ADR 0009);
   - zatex (declined);
   - Luau visuals (#900, parked).

## NEW — not in the 2026-10-28 report

None of these is in `2026-10-28.md` (§A–G, including §C Android parity) or in any `.scratch` ticket.

| # | Gap (plain words) | Upstream | Roboco evidence | Report / ticket | Effort |
|---|---|---|---|---|---|
| N1 | **The provider-switch seam is invisible on Android.** When a chat moves to another agent, the desktop shows the divider "Switched to Codex" (`crates/ui/src/transcript.rs:1695-1712`). The iPhone shows "Switched from [logo] to [logo]" as a chip row. Android shows nothing: the mobile layout drops `MessagePart::HarnessSwitch` in its `_ => {}` arm. This also hides seams written by Roboco's own failure-driven harness failover, so on the phone a chat silently changes agent mid-transcript. | `crates/mobile/src/layout/rows.rs:524` (`MessagePart::Switch`), `:948` `switch_row`; `layout/markdown.rs` `PText.marks` (logo before a span); added in `cc7da5c2a` #966 | `crates/mobile/src/layout/rows.rs:419-461` (no `HarnessSwitch` arm); no "Switched" in `crates/mobile`, `crates/mobile-client`, `apps/android`. Data exists: `crates/doc/src/parts.rs:287` `HarnessSwitch { harness, from_harness }` | Not listed. Wave-9 10 ported the engine/doc side only; wave-9 20 is the picker. | S (one row kind in mobile core + a layout test; Android paints chip rows already) |
| N2 | **Settings → Providers: turn agents on or off per computer.** Each agent on the chosen computer shows "Installed" / "Not installed" (dimmed) / "Offline", with an on/off switch. The last agent left on can't be turned off. Errors read "Couldn't turn it on/off"; success gives a selection haptic. Android lists accounts only. The engine already serves `SetHarnessEnabled` (`crates/engine/src/rpc.rs:1604`) and `mobile-client` reads `offered` (`catalog.rs:55`). | `apps/ios/Zeron/Cloud/ProvidersViewController.swift:315-342` (rows, `toggleEnabled` :335), `:361-381` `setEnabled`; FFI `crates/mobile/src/client_ffi/providers.rs:167` `set_harness_enabled` | No `set_harness_enabled` in `crates/mobile/src/client_ffi/*`. No "Not installed" or toggle in `apps/android/.../accounts/*`. | Not listed. The 10-25/10-28 reviews filed `ProvidersViewController` as cloud (#966). | M (FFI + an agents section on the Accounts & Usage page) |
| N3 | **Add an account, i.e. sign an agent in on a computer from the phone.** The computer starts the login. The phone opens the provider page in a browser sheet and relays its return to the computer. Device-code logins show "Enter the code ABCD-1234 when asked". Claude's paste fallback asks "Paste the code". The phone shows "Finish signing in in the browser…" (tap to cancel). There is one "Add account" row per login option (`agent_login_rules`). Android's footer says the opposite: "To add an account, sign in from Roboco on $deviceName." The engine already has `StartAgentLogin`/`Poll`/`Complete`/`Cancel` (`crates/rpc/src/lib.rs:264-267`). | `apps/ios/Zeron/Cloud/AgentAccountsViewController.swift:193-205` (add rows), `:480-590` `DeviceSignIn`; FFI `client_ffi/providers.rs:90-118` (login rules), `:213-281` (`device_login_*`) | `apps/android/.../accounts/AgentAccountsModel.kt:58` (footer). No `login` calls in `crates/mobile/src/client_ffi/accounts.rs` (list/activate/forget only). | Partly: wave-9 18 asks for "starting the agent's first login on arrival" (the error-card entry point). That needs this same plumbing; the general Add-account rows are not ticketed. | M–L |
| N4 | **First-run onboarding on Android.** The flow:<br>1. **"Make it yours"**: colour scheme, new-chat wallpaper and effect.<br>2. **"Your agents"**: the Providers page inline, e.g. "Found Claude Code and Codex on <computer>. Turn on the ones you'd like to use."<br>3. **"Stay in the loop"**: the notification ask, with Finished / Needs you / Something went wrong.<br>4. **"You're all set"**: a summary and **Start a session**.<br>Back / Skip / Continue, with "2 of 5" progress. Only the sync and GitHub steps are cloud. On Android, pairing drops straight into the shell. | `apps/ios/Zeron/Cloud/OnboardingViewController.swift:3-11` (steps), `:263-313`, `:360-412`, `:413-440`, `:440-490`; `Design/Wallpaper.swift` `soften` (blurred wallpaper behind the pages) | No "Make it yours" / "Stay in the loop" / onboarding in `apps/android`. `update/UpdateCenter.kt:73` mentions onboarding but none exists. | A27 covers **desktop** onboarding only. No Android ticket. | M (step 2 depends on N2) |
| N5 | **Markdown parser crash hardening.** Upstream caps block/inline nesting at 32 (`MAX_NESTING`): the tree builder and every renderer recurse per level, so `>` × 20 000 overflows the stack. A pulldown-cmark panic falls back to the message as plain text. Roboco's parser recurses with no cap (`parse_started_block` ↔ `parse_block_sequence`). A deeply nested message from an agent or a paste can abort the process; a stack overflow is not caught by `catch_unwind`. That hits **both Android and desktop**, since `crates/ui/src/markdown/mod.rs:22` re-exports `roboco_markdown`. Android's `guarded_pass` (`crates/mobile/src/layout/mod.rs:669`) only freezes on the last good frame after an ordinary panic. | `crates/markdown/src/parser.rs:168-186` (`catch_unwind` → plain paragraph), `:207-211` `MAX_NESTING`, `:399`, `:453`, test `:3943`; arrived inside `c02739da3` #912 | `crates/markdown/src/parser.rs:139-145` `parse_full`/`parse_at` (no guard), `:521`/`:629` (unbounded recursion); no `MAX_NESTING`/`catch_unwind` in the crate | Not listed. Hidden in #912, filed DECLINED as zatex (`part-06.md:70`); the robustness slice has nothing to do with LaTeX. | S–M (port the cap + guard, skip the math rewrites; desktop and Android benefit) |
| N6 | **Subagent spawn card polish (mobile core).** The card adds the spawned model as a faint trailing label (`claude-haiku-9` → "Haiku 9"; bare aliases capitalised), shown only when the description still fits. A failed spawn tints its icon red. An openable card ends in a chevron. TalkBack gets state (`Subagent: <title>`, value Working / Failed / Done). On Android the card has the icon, "Agent", the description and a spinner only, and TalkBack hears "Open subagent: <title>" with no state. | `crates/mobile/src/layout/tools.rs:61` `AgentLink.model`, `:153` `spawn_model_label` (+ test `spawn_models_read_as_names`), `:1143-1200` `place_agents` (Danger icon, chevron `:1187`); `apps/ios/Zeron/Transcript/RowView.swift:323-327` (a11y) | `crates/mobile/src/layout/tools.rs:1108-1156` (no model, no chevron, icon always `TextSecondary` `:1125`); `apps/android/.../transcript/widgets/SubagentLink.kt:57` | Not listed. #988 is DECLINED as a whole (`part-07`). Ticket 23 says "the side chats + subagents surfaces keep the current shape". This is a transcript-card detail, not the surface: **user call**. | S |
| N7 | **Toast restyle (#966).** The action becomes a small filled capsule in the text colour (SemiBold 14, insets 6/12), not plain accent text. The label wraps to 2 lines (Android cuts at 1). Upstream's `above:` anchor is used only by cloud, so skip it. | `apps/ios/Zeron/Design/Toast.swift:58` (`numberOfLines = 2`), `:64-70` (`.filled()` capsule) | `apps/android/.../design/Toast.kt:413` and `:427` (`palette.accent` plain action, insets 0), `:452`/`:474` `maxLines = 1` | Not listed. | S |

## Decided by ticket 23 ("side chats + subagents surfaces keep the current shape"), for completeness

The user declined these with #988 (`.scratch/upstream-ports-2026-10-wave9/issues/23-android-terminals.md`, comment 2026-10-25). They are listed so a future revisit has the inventory. Not proposed.

- Subagent viewer bottom status bar. Status glyph plus "Working · 2m 3s" / "Finished" / "Failed" / "Waiting for its computer…", with the detail "Runs on its own" or the model. The subtitle becomes "Subagent · <model>". Source: `Companions/SubagentViewController.swift:44`, `:148`, `:181-251`. Android uses the subtitle "Running · …" instead (`sidechats/SubagentViewer.kt:90-99`).
- Companions panel shape (`CompanionsViewController.swift`):
  - "Agents" / "Side chats" sections, with "No subagents yet" and "Ask something on the side without derailing the main thread." empty states;
  - row details "Didn't finish" / "Failed · model";
  - swipe a side chat to **Archive** (`:343-349`);
  - an iPad / unfolded-Duo inspector column beside the session (`Shell/SplitRootController.swift:200-282`);
  - ⌘⌥0 "Agents, Side Chats & Terminals".

  Android keeps its `Side Chats & Agents` sheet (`sidechats/SideChatsSheet.kt:203-206`).

## KNOWN — already in the 2026-10-28 report and/or a ticket

| Gap | Upstream | Report / ticket | Notes from this pass |
|---|---|---|---|
| Error-card **Log in** starts the first login | `Session/SessionViewController.swift:89-99` (`signIn: true`, medium/large sheet) | §C; wave-9 18 | Needs N3's login plumbing (`device_login_*`) — today the Android action only pushes the Accounts page. |
| Session **Delete** (list long-press + ⋯), "Delete session?" confirm, ⋯ menu headed by title + "project @ device" | `Threads/SessionListController.swift:302-306`; `Session/SessionViewController.swift:800-880`; UITest `testSessionMenuHeaderAndDelete` | §C; wave-9 18 | Archive no longer destructive-styled. |
| One-row bottom bar (Settings · Search-in-place · New session), no tab bar to come back | `Shell/MainTabController.swift` (#967/#971) | §C; wave-9 19 | Toast now anchors over that bar (`Toast.swift`, #967), so it rides 19. |
| Tabbed model picker: per-provider swipe pages, logo switcher, search once long ("Search <agent> models", "No matches"), current-model tint, loading bars | `Session/ModelPickerViewController.swift:19-30`, `:259-450`, `:624-780` | §C; wave-9 20 | Ticket 20 doesn't spell out three needs:<br>• the **provider-switch** part: other providers' tabs only when `HostCapabilities.provider_switch` — that field is **absent from Roboco's FFI** (`crates/mobile/src/client_ffi/session.rs`);<br>• the notes "You can switch provider when this turn ends." / "…needs a newer Roboco to switch provider." / "Your next message continues this chat with X.";<br>• a pick that changes provider resets effort/options (`Session/CoreSessionSource.swift:290-330`).<br>Today Android's model chip is harness-locked (`composer/SessionChipMenus.kt:242`). |
| Agent sender caption over messages ("Message from X", tap opens the chat) + one-line "sent to X" | `crates/mobile/src/layout/rows.rs` `sender_caption`/`chat_caption`/`outgoing_row`/`OPEN_CHAT_SCHEME`; `Transcript/RowView.swift:79` "Open chat" | A15, §B, §C | — |
| Questions: close without answering ("Close without answering" ×) + resolved row shows answers | `Composer/QuestionPanel.swift:5-6`, `:77`; `layout/rows.rs` #1022 | §B (`64a26d359`) | Android shows "Answered a question" (`crates/mobile/src/layout/rows.rs:444-446`). |
| Drag a session between Pinned / sections / Recent | `Threads/SessionListController.swift:364-470`; FFI `pin_session_at`/`place_session` | §C (#909, no ticket) | — |
| Uneven side insets (iPhone Duo / landscape) | `Transcript/TranscriptListView.swift:300-302` | §C (#942) | — |
| Pin transcript bottom on width change / fold continuity | `Shell/SplitRootController.swift:290-345` | §C (#981) | — |
| Tablet composer rests level with sidebar toolbar | `Session/SessionViewController.swift:886-915`; `SplitRootController.swift:214-219`, `:361-385` | §C (#1014); wave-9 21 | — |
| Native Luau visuals (`VisualView`, `crates/mobile/src/visual/*`, `WidgetKind::Visual`) | `Transcript/VisualView.swift`; `crates/mobile/src/visual/` | §F parked (#900) | — |

## Mobile-core module inventory (`upstream/main:crates/mobile/src` vs Roboco)

| Upstream module | Roboco | Class |
|---|---|---|
| `layout/{display,markdown,mod,rows,style,tools,file_icons}.rs` | present | Parity except:<br>• N1 `switch_row`;<br>• N6 `AgentLink`/`spawn_model_label`;<br>• captions (A15/#1062/#1068);<br>• #1022 answers;<br>• zatex math (`PMath`, `formula`, `MathFace`, `set_math`, `rebuild`, `LAID_*`: declined);<br>• `VISUAL_LOADING`/`column_width`/`set_visual_source` (parked #900);<br>• `SubagentInput`/`attach_subagent` (#988's Rust→Rust follow; Roboco reaches the same through `openSubagent`/`SubagentLink`). |
| `caption.rs` | — | KNOWN (#1062) |
| `client_ffi/{mod,session,types}.rs` | present (+ Roboco's `accounts`, `failover`, `new_session`, `notify`, `previews`, `rooms`, `workspace`) | Missing `pub fn`s:<br>• `set_harness_enabled` (N2);<br>• `device_login_{start,poll,complete,cancel}` + `agent_login_rules` (N3);<br>• `pin_session_at`/`place_session` (#909 KNOWN);<br>• `HostCapabilities.provider_switch` (KNOWN, ticket 20).<br>Unused by the iPhone app: `rename_project`, `delete_project`, `switch_ref`, `switch_session_ref`.<br>Cloud: `network_path_changed`/`warm_direct` (direct link), auth/push. |
| `client_ffi/providers.rs` | `client_ffi/accounts.rs` (list/activate/forget) | PARTIAL — N2, N3 |
| `client_ffi/cloud.rs`, `voice.rs`, `orb.rs` | — | SKIP: cloud / voice dropped |
| `client_ffi/companions.rs`, `terminal/*` | — | DECLINED (wave-9 22/23) |
| `visual/*` | — | Parked (#900) |
| `wallpaper.rs` | identical (11223 B) | PORTED |
| `crates/markdown` | present | N5; `parse_message` (#1067) unused by mobile (iPhone user bubbles stay plain); zatex parser declined |
| `crates/text` | present | Diff is #912 math inline boxes (declined) + #900 font tweak |

## PRESENT on Android (checked; one citation each, paths under `apps/android/app/src/main/java/sh/roboco/android/`)

### Sessions list
- Pin/Unpin, Archive, Move swipe actions; long-press menu (Pin, Rename…, Archive, Unarchive/Rename in Archived): `sessions/SessionRowActions.kt`, `sessions/SwipeRow.kt`.
- Move to Section / No Section / New Section…: `sessions/SessionsMenus.kt`.
- Section header menus:
  - Open, Reorder…, Rename Section…, Delete Section: `sessions/SectionHeaderRow.kt`;
  - Delete confirm "Its sessions move back to the main list.": `sessions/FolderScreen.kt`.
- Options menu (New Section…, Collapse All, Group by Project, Archived): `sessions/SessionsMenus.kt`.
- Search "Sessions, projects, branches": `sessions/SearchScreen.kt`.
- Pinned in-folder reorder: `sessions/PinReorder.kt`.
- Folder rows: `sessions/ListSection.kt`.
- Pull to refresh: `design/PullToRefresh.kt`.
- Archive → "Archived · Undo" toast: `design/Toast.kt:605`.

### Session rows and section headers
- Status corner Failed/Working/Input/Done (desktop precedence): `sessions/SessionRowCorner.kt:16-25`.
- Harness mark, project tile ("Home"), PR badge: `sessions/SessionRow.kt`, `design/PrBadge.kt`.
- Tablet current-row fill: `sessions/SessionRow.kt:66`.
- Section header Collapsed/Expanded state: `sessions/SectionHeaderRow.kt:237`.

### Session screen
- ⋯ menu: Pin, Copy Transcript, Previews, New Side Chat, Fork into Side Chat, Archive: `session/SessionMenu.kt:76-170`.
- Status pill:
  - "Working · 5s", "Not delivered · Tap to retry", "Editing queued message · Tap to cancel": `session/StatusPill.kt:101-125`;
  - offline/reconnecting copy adapted to engine-local.
- Jump to latest: `session/JumpToLatestButton.kt`.
- Loader-then-transcript open: `session/OpenReveal.kt`.
- Tap outside minimizes the composer: `session/DismissComposerOnTap.kt`.
- Cancelled swipe-back keeps streaming: `session/SessionDetachFence.kt`.
- "Couldn't open session. Try again.": `shell/AppRouter.kt:6`.

### Composer
- Send / Queue / Steer / Stop a11y labels and the "While the agent works" menu (Queue for next turn, Steer now — Deliver into the running turn, Stop and send): `composer/ComposerActionButton.kt`.
- Attach menu (Photo Library, Take Photo, Choose File, Paste Image): `composer/AttachMenu.kt`.
- Staged thumbnails + upload ring + "Remove image": `composer/StagedThumbnailStrip.kt`, `transcript/widgets/UploadRing.kt`.
- @-file mentions + chips: `composer/MentionSuggestions.kt`.
- Queue panel:
  - Edit / Move Up / Move Down / Remove, "More queue actions": `composer/QueuePanel.kt`;
  - queue edit lease ("Can't edit right now"), trailer/Appshot preserved: `composer/QueueEditController.kt`, `composer/QueueEditText.kt`.
- Per-session draft kept: `composer/Drafts.kt`.
- Whole-line resting preview: `composer/ComposerTextMetrics.kt`.
- Ctrl/⌘+Enter sends: `composer/ComposerTextMetrics.kt:103`.
- Question panel (pages, Back/Next/Submit, multi-select, "Other…", single-select auto-advance): `composer/QuestionCard.kt`, `composer/QuestionPanelState.kt`.

### New session and new project
- New session:
  - "What are we building?" canvas, Describe the task;
  - project menu (repo groups, No Project…, New Project…), Run on (host picks checkout), Checkout/New worktree/branch, Reasoning effort;
  - "Couldn't start the session" alert;
  - remembered draft/options;
  - draft→session handoff.
  - Files: `newsession/NewSessionCanvas.kt`, `NewSessionProjectMenus.kt`, `CheckoutModelEffortMenus.kt`, `NewSessionCreate.kt`, `NewSessionDraftStore.kt`, `DraftHandoffAnimator.kt`.
- New Project folder browser: `newsession/NewProjectScreen.kt`, `FolderBrowserController.kt`.

### Transcript
- Row kinds: user / markdown / tools / chip / notice (error card + Action) / image / working. Paths: `transcript/RowWidget.kt`, `crates/mobile/src/layout/rows.rs:744-810`.
- Row menu Copy / Copy Message / Select Text: `transcript/RowMenu.kt`, `sheets/SelectTextSheet.kt`.
- Copy code: `transcript/widgets/CopyCodeButton.kt`.
- Tool rail draw-in, shimmer, fold tweens: `transcript/widgets/ToolRail.kt`, `widgets/Shimmer.kt`, `transcript/RowMotion.kt`.
- Show/Hide details: `transcript/widgets/DisclosureWidgets.kt`.
- "Writing/Working" trailer: `transcript/RowWidget.kt`.
- Runway / own-send glide: `transcript/Runway.kt`.
- Follow and re-latch within 70pt: `transcript/TranscriptState.kt`.
- Image chip → full-screen viewer: `transcript/LinkRoute.kt:17`.
- Image viewer close/share: `image/ImageViewer.kt:140`.
- Link taps: `transcript/LinkRouter.kt`.
- "You: …" a11y: `transcript/TranscriptSemantics.kt:109`.
- Text scale: `transcript/TranscriptState.kt:592`.

### Side chats and subagents (current shape)
- Side Chats & Agents sheet, New Side Chat, Fork into Side Chat, fork error copy: `sidechats/SideChatsSheet.kt:203-206`, `sidechats/SideChatFork.kt`.
- Read-only live subagent viewer with nested spawns, Copy Transcript: `sidechats/SubagentViewer.kt:104-123`.

### Settings
- Notifications master + three kinds, the denied alert: `settings/NotificationSettings.kt:76-97`.
- Appearance System/Light/Dark: `settings/AppearanceSection.kt`.
- Wallpaper choose/change/effect (None/Dither/ASCII/Halftone/Scanlines)/remove: `settings/WallpaperRows.kt`, `core/WallpaperEffects.kt:51-54`.
- Sessions (Group by Project, Archived Sessions): `settings/SessionsSettingsSection.kt`.
- Devices (engine-local): `settings/DevicesRows.kt`.
- Accounts & Usage:
  - per-device accounts, In use, Use This Account / Copy Email / Remove Account…, usage meters + reset times: `accounts/AgentAccountsScreen.kt:390`, `accounts/AccountsFormat.kt`;
  - the "no device" case is safe (#980).

### Notifications
- Local done/input/failed with first-session permission ask: `notify/Notifier.kt`, `notify/NotificationPermission.kt:112-126`.
- Quiet while foreground, tap opens the session: `notify/NotificationPolicy.kt`, `shell/DeepLinks.kt`.

### Tablet
- Split shell with sidebar toolbar (Settings / Options / New session): `shell/SplitRoot.kt`, `shell/SidebarToolbar.kt`.
- Shortcuts New Session / Search / Toggle Sidebar: `shell/ShellShortcuts.kt:72-73`.

### Design
- Brand marks for every non-declined harness: `design/BrandMarks.kt:74-87`.
- Haptics mapped to iOS call sites: `design/HapticTable.kt:65-68`.
- Status glyph: `design/StatusGlyph.kt`.

## Excluded from scope (with the upstream source, so the judgement can be checked)

- Cloud and account:
  - Billing, Cloud settings, GitHub connect, Cloud repo picker, Cloud credits notice ("Out of Cloud credits", "Add credits"), "Starting on Cloud…", "Start from" (Cloud branch menu), "New Cloud Project…";
  - onboarding steps "Sync your work" and "Connect GitHub";
  - Sign In / Sign Out, org pick;
  - `Shell/SignInViewController.swift`, `Cloud/*` except the device halves used in N2/N3.
- Voice ("Voice call", "Talk to Codex", Codex voice settings): `Voice/*`, `MainTabController.swift`. Dropped by ADR 0009; native Codex voice is §F decision-gated.
- Terminals and CompanionStrip (`Companions/Terminal*`, "New terminal", ⌘J): DECLINED, wave-9 23.
- Math settings (Render Math / Math Font / Math Size): `MoreViewController.swift:234-240`. zatex DECLINED.
- APNs registration and push targets (`App/PushNotifications.swift:59-152`): replaced by local notifications (ADR 0009/0010).
- Muse brand mark: DECLINED.
- Debug: `Debug/*`. The Android Transcript Lab exists in the debug source set.
