# Pass 3 — iPhone slices of `ios-2.tsv` (older half) vs Roboco Android

upstream/main = `31d26eeb6`; Roboco = `HEAD` (`7df761c7f`, the `feature/fleet-follow` tree).
Summary: 24 commits. Their iPhone behaviours are present on Android, except for the items already known (wave-9 18/19/20, §C, A36). One new mobile-core gap: G1, Markdown streaming parity. One nit: G2.

Method:
- For each commit I read its `apps/ios` and `crates/{mobile,text,markdown}` hunks with `git show`.
- For each behaviour I checked the current iPhone code with `git show upstream/main:apps/ios/...`, then searched Android by behaviour: copy, timings, a11y labels, haptics, lifecycle hooks.
- For the mobile crates I diffed function names and file lists, upstream vs HEAD. For `crates/markdown` I also diffed the code.
- Path prefix for Android files below: `apps/android/app/src/main/java/sh/roboco/android/`. The Android port cites the Swift line numbers in its comments, which made each match checkable.
- Commits before `fe46a971a` touched the old SwiftUI app, which `fe46a971a` deleted. For those, the target is whatever the current iPhone kept.

Known items are cited, not re-reported:
- wave-9 18: session Delete, ⋯ header, Log in.
- wave-9 19: the one-row bottom bar.
- wave-9 20: the tabbed model picker.
- §C N1–N7.
- A36: the Markdown nesting cap.
- §A smaller: #626, the six-session cache byte budget.

---

## Post-rewrite commits (current iPhone code base)

### 61b8abb38 (#589) Session notifications: done / needs you / failed
APNs and the edge are cloud. ADR 0009/0010 map them to local notifications. The iPhone behaviours:

| Behaviour | Android | Evidence |
|---|---|---|
| Three kinds, copy "Run finished" / "Waiting on your input" / "Run failed" | present | `notify/NotificationPrefs.kt:16-18` |
| Title = the chat's title; one notification per chat (APNs thread → tag) | present | `notify/Notifier.kt:124-158` |
| Quiet while the app is open (`willPresent` → `[]`) | present | `notify/NotificationPolicy.kt:10-17` |
| Tap opens the session, cold launch included (pending chat until the shell is up) | present | `notify/Notifier.kt:156`, `shell/DeepLinks.kt` |
| Permission asked the first time a session is started from the phone | present | `notify/NotificationPermission.kt:121` |
| Settings → Notifications: master switch with subtitle "When a session finishes, needs you or fails" / "Turned off in … Settings"; per-kind switches shown only when on | present (iOS → Android settings) | `settings/NotificationSettings.kt:82-85` |
| Denied → alert "Notifications are off" (Not Now / Open Settings) | present | `settings/NotificationSettings.kt:54,88` |
| Status re-read when returning from system Settings | present (`ON_RESUME`) | `settings/NotificationSettings.kt:66,225` |
| Sign-out unregisters the push target | N/A (cloud) | — |

### b89bafbe3 (#594) Harness mark 20pt, centred on the title's x-height
| Behaviour | Android | Evidence |
|---|---|---|
| Mark side fixed at 20 (not type-scaled) | present | `sessions/SessionRowGeometry.kt:61-62` |
| Centred on the title's x-height, text starts at mark + 14 | present | `sessions/SessionRowGeometry.kt:117-121`, `:68` |

### d81ced916 (#593) Worktree shows as a branch, aligned row icons, clean resting draft
| Behaviour | Android | Evidence |
|---|---|---|
| Checkout chip: branch icon for "New worktree" too | present | `newsession/NewSessionChips.kt:133-134` |
| Checkout menu: "New worktree" toggle, no image and no "Isolated branch…" subtitle | present | `newsession/CheckoutModelEffortMenus.kt:196-209` |
| Checkout chip in the UI sans; only `pr` stays mono | present | `composer/ContextChip.kt:129-142` |
| Project tile drawn inline on the meta line (x-height centred, at the title's leading edge) | present | `sessions/SessionRowMetaLine.kt:53,98,123` |
| Tile re-tinted on a light/dark change | present (Compose recomposes on theme) | `sessions/SessionRowMetaLine.kt:98` |
| Resting draft: whole lines ending in "…", never a scrolled box; focusing restores it | present | `composer/ComposerTextMetrics.kt:68` |

### 5017efa90 (#592) Steering never kills the turn; queued images reach the agent
| Behaviour | Android / core | Evidence |
|---|---|---|
| Queue row's primary button = `deliver_queued_now` (text steers, attachments send now) | present | `composer/QueueActions.kt:85`; `crates/mobile/src/client_ffi/session.rs:760`; `crates/mobile-client/src/session/queue.rs:345` |
| Its a11y label "Send" (was "Send now") | present | `composer/QueuePanel.kt:288` |
| Composer Steer issues a real steer | present | `session/SessionCommands.kt:45` (`BusyPolicy.STEER`) |
| Host holds a steer with no mailbox; drain waits for `pending://` bytes | engine-side, PORTED in pass 1 | — |

### 7a4343b0a (#585) Tapping links in the transcript opens them again
| Behaviour | Android | Evidence |
|---|---|---|
| "Tap to put the composer away" coexists with link and image taps | present (Initial-pass observer, never consumes) | `session/DismissComposerOnTap.kt:14` |
| One a11y element per painted link fragment: label = host, value = URL, link trait, id `transcript-link`, follows code/table scrollers | present | `transcript/TranscriptSemantics.kt:195-215` |
| http(s) opens in an in-app browser (SFSafari → Custom Tab), other schemes go to the system | present | `transcript/LinkRouter.kt:35-58,99-111` |
| Core: every markdown link gets hit regions (lists, quotes) | present (same crate) | `crates/mobile/src/layout/tests.rs` `links_get_hit_regions` |

### 52047c951 (#584) Sessions open at once with a loader
| Behaviour | Android | Evidence |
|---|---|---|
| Animated push shows a spinner (12pt ×1.75, 40pt above centre); transcript held at alpha 0 | present | `session/OpenReveal.kt:20,167` |
| Reveal when push landed AND rows laid out: 0.18 s ease-out fade; instant under Reduce Motion | present | `session/OpenReveal.kt:20,39` |
| Empty/unreachable session stops holding after 8 s | present | `session/OpenReveal.kt:22,38,126` |
| Not used for handoff or unanimated swaps | present | `session/OpenReveal.kt` (`viewIsAppearing` guard) |

### 433aa148d (#577) Leaving search transitions smoothly
The current iPhone has no tab bar (#967/#971, one-row bottom bar), so this behaviour is superseded upstream. Android still has the tab shell (wave-9 19, known). Within that shell the behaviour is present:

| Behaviour | Android | Evidence |
|---|---|---|
| Accessory stays attached across tabs, search included | present | `shell/MainTabShell.kt:447` |
| Content fades back in (0.25 s, 0.12 s delay) after leaving search; skipped under Reduce Motion | present | `shell/NewSessionAccessory.kt:153-157` |

### 119b5ec0c (#576) New session remembers what you typed and picked
| Behaviour | Android | Evidence |
|---|---|---|
| Close (swipe, ✕, replaced tablet column) keeps text, images and options | present | `newsession/NewSessionDraftStore.kt:34-61` |
| Options and text survive relaunch; images kept for this launch only | present | `newsession/NewSessionDraftStore.kt:43,183` |
| Saved on backgrounding | present | `newsession/NewSessionModel.kt:407` (`ON_STOP`) |
| Creating the session clears the text and images | present | `newsession/NewSessionDraftStore.kt:189-207` |
| Sign-out forgets | N/A. The engine-local analogue (reset on unpair) is the generation bump at `:61` | — |

### 198777a4a (#574) TestFlight archive only
SKIP: upstream-ci. No iPhone behaviour.

### fe46a971a (#570) iOS rewrite on the Rust core
Behaviours grouped as in the commit's own sub-commits. Each was checked against the current Swift; where a later commit changed it, the current form is listed.

**Transcript (core + painter)**

| Behaviour | Android / core | Evidence |
|---|---|---|
| Rust layout of rows and display lists; prefix-sum frames; streaming O(changed) | present (same crate) | `crates/mobile/src/layout/*` |
| Reading width capped at 768, centred; composer stack ≤ 768 | present | `crates/mobile/src/layout/rows.rs:158`; `session/BottomStackMetrics.kt:23-24` |
| Tool groups:<br>• 26pt header, chevron 140 ms;<br>• title shimmer 3.4 s;<br>• rail with elbows;<br>• phased draw-in 480 ms;<br>• reveal 360 ms expo;<br>• fold 140 ms | present | `transcript/RowMotion.kt:16-18`, `transcript/widgets/ToolRailMotion.kt:118`, `transcript/widgets/Shimmer.kt:59` |
| Inline details: output 24 lines + "… n more lines", diffs, "Thought process"; diff compute bounded at 100 ms | present (core) | `crates/mobile/src/layout/tools.rs:235,244,873` |
| Show/Hide details control with a selection haptic | present | `transcript/widgets/DisclosureWidgets.kt:85,122-125` |
| Tap a tool line → full command/path in a mono sheet (medium/large detents, grabber) | present | `sheets/SelectTextSheet.kt:62,88`; `sheets/TextSheetController.kt:62` |
| Long-press row menu: Copy / Copy Message / Select Text | present | `transcript/RowMenu.kt:34-35` |
| Copy-code button: light haptic + checkmark | present | `transcript/widgets/CopyCodeButton.kt:109` |
| Error-card action button: light haptic | present | `transcript/widgets/ActionButton.kt:45` |
| Follow the tail; re-latch only on release or momentum within 70pt heading down; never while the finger is down | present | `transcript/TranscriptViewport.kt:27,195-208` |
| Status-bar tap scrolls to top and releases follow | present (adapted: tab reselect) | `shell/MainTabState.kt:51-52` |
| Streaming runway: own-send glide (`GLIDE_RETAIN`), one viewport held, queued turn promoted when its bubble lands | present | `transcript/Runway.kt:189`; `session/SessionComposer.kt:167` |
| Streaming veil | present | `transcript/Veil.kt`, `transcript/VeilDiff.kt` |
| Bottom fade behind the composer; fades instead of cuts | present | `design/EdgeFade*.kt`, `design/FadingText.kt` |
| User rows: Appshot context stripped; @file mentions as atomic accent chips | present (core) | `crates/mobile/src/layout/rows.rs` |
| Subagent card: running/failed from the lifecycle (spinner, danger tint) | present (core). Card polish is N6 | `crates/mobile/src/layout/tools.rs:1108-1156` |
| Display-model cache dropped on memory warnings | present (`onTrimMemory ≥ RUNNING_LOW`) | `transcript/ModelPipeline.kt:258-265`; `transcript/TranscriptHost.kt:81-88` |
| Dynamic Type for rows (fixed heights per size class) and the transcript text scale | present | `design/TypeScale.kt:15-51`; `transcript/TranscriptState.kt:592` |
| Image viewer: zoom out of the tile, pinch 1–5×, double-tap 2.5×, drag to dismiss, share, close; cross-fade under Reduce Motion | present | `image/ImageViewer.kt:112-123,209-227` |
| Upload progress as a ring on the thumbnails, no banner | present | `transcript/widgets/UploadRing.kt`, `composer/StagedThumbnailStrip.kt:321` |

**Composer**

| Behaviour | Android | Evidence |
|---|---|---|
| One glass surface morphing capsule ↔ card; card only when focused, holding photos, or on the new-session canvas | present | `composer/ComposerMorph.kt`, `composer/ComposerSurface.kt` |
| Queue looks like Send; long-press "While the agent works" menu; a11y labels | present | `composer/ComposerActionButton.kt:188` |
| Send = medium impact; Stop = rigid; mention pick = selection | present | `composer/ComposerController.kt:187,212`; `composer/MentionSuggestionsState.kt:201` |
| Sending dismisses the keyboard; a failed send keeps text, images and focus | present | `session/SessionComposer.kt:142-163` |
| Tap outside minimizes the composer; the new-session canvas only drops the keyboard | present | `session/DismissComposerOnTap.kt` |
| @file mentions over the host's SearchFiles; list above the chip strip; closes when unfocused | present | `composer/MentionSuggestionsState.kt:99,139-151,242` |
| Ctrl/⌘+Return sends | present | `composer/ComposerTextMetrics.kt:103` |
| Question panel: pick = selection haptic, submit once = medium | present | `composer/QuestionPanelState.kt:179,241-255` |
| Queue panel:<br>• long rows compress, buttons stay on the card;<br>• the queue is shown on first render after navigating back;<br>• queued edits via the host lease keep the draft, images, trailer and Appshot | present | `composer/QueuePanel.kt`, `composer/QueueEditController.kt` |
| Drafts saved on backgrounding; a reappearing session re-attaches | present | `composer/SessionDraft.kt:111,151`; `session/SessionNavLifecycle.kt:75-86` |
| Context-usage chip | present | `composer/ContextChip.kt:45`; `session/ContextUsage.kt` |

**Session screen and navigation**

| Behaviour | Android | Evidence |
|---|---|---|
| Status pill: working/elapsed, "Not delivered · Tap to retry" | present | `session/StatusPill.kt:101-125` |
| Jump to latest | present | `session/JumpToLatestButton.kt:66` |
| A cancelled swipe-back keeps the session attached; accessory derived after every transition | present | `session/SessionNavEffects.kt:25-31`; `session/SessionNavLifecycle.kt:75-86` |
| Accessory and bottom glass hand off inside push/pop (track interactive back) | present | `session/SessionNavChoreography.kt` |
| New chat → chat handoff (draft card glides into the composer, message flies to its bubble; Reduce Motion → cross-fade) | present | `newsession/DraftHandoffAnimator.kt:47`, `newsession/HandoffOverlay.kt` |

**Lists, shell, settings**

| Behaviour | Android | Evidence |
|---|---|---|
| Front page:<br>• foldable Pinned and user sections, counts, live glyph;<br>• fold state persists;<br>• section toggle = selection haptic | present | `sessions/FoldState.kt:32,182-190`; `sessions/SectionHeaderRow.kt` |
| Swipe Pin/Unpin (leading); Archive + Move (trailing); Archived list → Unarchive | present | `sessions/SwipeRow.kt:70`; `sessions/SessionRowActions.kt:61,147` |
| Long-press: Pin, Move, Rename…, Archive (+ Delete = wave-9 18) | present except Delete (known) | `sessions/SessionRowActions.kt` |
| Pinned reorder; section rename/delete | present | `sessions/PinReorder.kt`; `sessions/FolderScreen.kt:72,266` |
| "Archived · Undo" toast above the chrome actually on screen | present | `design/Toast.kt:79`, `design/ToastPlacement.kt` |
| Pull to refresh runs the resync, then ends (0.6 s floor, 3 s cap) | present (adapted) | `sessions/Refresher.kt:17-40` |
| Rows: harness mark, PR badge, status corner (spinner only while working, check + Done), time when idle | present | `sessions/SessionRowCorner.kt:16-25`; `design/PrBadge.kt` |
| "New session" accessory with live "2 working · 1 needs you" summary | present | `shell/LiveCounts.kt:123-129`; `shell/NewSessionAccessory.kt:112` |
| Wallpaper: on the new-chat page and behind Sessions, fading as the list scrolls; contrast guard in Rust | present (`wallpaper.rs` identical) | `sessions/SessionsHero.kt:15-31`; `settings/WallpaperRows.kt` |
| New-session headline always above the composer | present | `newsession/NewSessionLayout.kt:33-75` |
| Model chip opens on a real model name (last host catalog, then live); host switch reloads | present | `newsession/ModelCatalog.kt:12-90` |
| New Project:<br>• host picker;<br>• remote folder browser with "Parent Folder", "Use “name”", "Loading…", "Couldn't read that folder.", "X is offline.";<br>• stale-reply guard | present (adapted to engines: "No paired engines yet.") | `newsession/FolderBrowserController.kt:105-160` |
| Never show ids ("Unknown device"; harness/model labels from the core) | present | `newsession/NewSessionOptions.kt:62` |
| Tablet:<br>• phone sessions page as sidebar (360 / 320–400);<br>• bottom toolbar (Settings, Options, New);<br>• ⌘N / ⌘⇧F / ⌘B;<br>• Settings as a sheet;<br>• session survives compact ↔ regular | present | `shell/TabletShellState.kt:31-42`; `shell/SidebarToolbar.kt`; `shell/ShellShortcuts.kt:72-73`; `shell/SettingsDialog.kt`; `shell/CarryOver.kt` |
| Sign-in, org picker, Switch Organization, demo button | N/A (cloud) | — |

**Mobile-core crates first created here**

| Item | Roboco | Evidence |
|---|---|---|
| `crates/text` (CoreText-exact breaking) | present. Roboco's deliberate drawn-vs-fit divergence and the Minikin note are recorded | `crates/text/src/layout.rs`, `crates/text/src/analysis.rs` |
| `crates/mobile` layout, width cache 4 MB, tool-diff 100 ms bound | present | `crates/mobile/src/layout/mod.rs:310`, `layout/tools.rs:244` |
| `crates/markdown` incremental parser | **partial**:<br>• streaming-parity and task-marker fixes later added upstream are missing → **G1** (new);<br>• the nesting cap and panic guard are A36 (known) | see G1 |

### f8f9c97f0 PR badges: icon always, no `#`
Its iOS hunk edits the SwiftUI `Views/PullRequestBadge.swift`, which the rewrite deleted. The current `PRBadgeView` (`Design/StatusGlyph.swift:171`) keeps the behaviour.

| Behaviour | Android | Evidence |
|---|---|---|
| PR icon + bare number on rows; a11y "Pull request N, state" | present | `design/PrBadge.kt:136-186` |

### 731697b66 (#498) iOS slice: hide child chats from lists
| Behaviour | Android / core | Evidence |
|---|---|---|
| Chats with `parentChatId` never appear on the front page or in project lists | present | `crates/mobile-client/src/workspace/view.rs:310`; `workspace/change_requests.rs:54` |
The PARTIAL class from pass 1 stays; it is desktop/engine (A17).

---

## Pre-rewrite commits (SwiftUI app, deleted by `fe46a971a`)

| SHA | iPhone behaviours | Kept by current iPhone? | Android |
|---|---|---|---|
| 096f361f9 (#511) | Test stabilisation; DocDisk persistence completion | — (Loro store gone) | N/A |
| 0e3239d97 (#490) | Snapshot write cadence; store residency bounded by **bytes** | Residency now lives in the Rust core's warm set | Known: §A "Android: six-session cache has no byte budget (#626, M)" |
| a1f3fd1a2 (#479) | • durable outbox + send watchdog;<br>• stable row heights;<br>• centred jump-to-latest;<br>• chip fades;<br>• connectivity in the strip;<br>• composer grows under the keyboard;<br>• transport error copy | Outbox = sync rooms (dropped). The rest is kept in the rewrite's form. | Watchdog → "Not delivered · Tap to retry" `session/StatusPill.kt:112`; jump button `session/JumpToLatestButton.kt`; chip fades `design/FadingHorizontalScroll.kt`; pill offline/reconnecting copy adapted; composer growth `composer/ComposerTextMetrics.kt`. Present. |
| 6ecea0558 (#468) | • model catalog normalisation (drop "default" when real rows exist, fold `[1m]`/`-1m` into Context Window, curated labels);<br>• follow kept across stale gestures;<br>• history anchor kept while streaming | Normalisation moved to the Rust client; follow/anchor kept | `crates/mobile-client/src/catalog.rs:434,469-490` (built-in ids/labels identical to `upstream:crates/client/src/catalog.rs`); `transcript/TranscriptViewport.kt`, `transcript/ScrollAnchor.kt`. Present. |
| 68306a172 | Pins with per-item ordering keys | yes (core `move_pin`) | `crates/mobile/src/client_ffi/workspace.rs` `move_pin`; `sessions/PinReorder.kt`. Present. |
| 787f58311 (#349) | Antigravity in catalog + brand mark | yes | `design/BrandMarks.kt:56,81,143`. Present. |
| e4702fa68 (#350) | Projectless sessions on a chosen host; empty workspace | yes ("No Project…", "Run on") | `newsession/NewSessionProjectMenus.kt:18`; `crates/mobile/src/client_ffi/workspace.rs` `projectless_sessions`; empty state `sessions/SessionListStatusOverlay.kt:106`. Present. |
| 9601a2440 (#367) | Generated images inline: loading, "unavailable", tap to retry, preview | Images are a core row kind (`layout/rows.rs` `MessagePart::Image`) | `crates/mobile/src/layout/rows.rs:431`; `transcript/widgets/ImageTile.kt`, `ImageTileStatus.kt`. Present. The desktop slice is A18. |
| af279d541 (#366) | "Zeroning" working word | **No**: the current iPhone shows no rotating words (only desktop `crates/ui/src/transcript.rs:2419`) | N/A on Android. Desktop has "Robocoing" (`crates/ui/src/transcript.rs:2312`). |
| d43292575, dfb72cb29 | Synced pinned sessions | yes (engine-local) | Present (as 68306a172). |
| c679de97f | CreateWorktree carries the project id so setup actions run | yes | `crates/mobile/src/client_ffi/new_session.rs:166-177` (`space_id`). Present. |

---

## New Android gaps

### G1 — Streamed Markdown can render differently from the finished text (mobile core; desktop too)
- **What users see:** while an agent reply streams, some Markdown is laid out wrongly. Android keeps that wrong tree after the turn ends (`rows.rs:470-486` keeps the same `IncrementalParser` and takes `parser.tree()`), so the wrong render stays until the session is reopened. The desktop shares `roboco_markdown`. Cases:
  - a block quote or list followed directly by a "lazy continuation" line (`>a\n|b\n>|-c`). The two-block reparse window can't fold the earlier container back;
  - reference-style links whose definition sits inside a quote or list item (`> [r]: https://…`, `- [r]: …`), or whose label spans two lines (`[a\nb]: /u`). These never switch the parser to full reparses, so `[w][r]` stays literal text;
  - a stray task checkbox: pulldown emits a task marker after its item closed (`- [x] *\n\nb`), so the next paragraph gets a checkbox. The marker's range can also start at whitespace (`- - \t[x]`).
- **Upstream:**
  - lazy-continuation reach: `crates/markdown/src/parser.rs:2140-2160`;
  - `has_link_defs` + `strip_container_markers`: `:2284-2320`;
  - task-marker guard and range trim: `:466-500`;
  - tests: `lazy_continuations_stream_like_a_full_parse` `:2851`, `quoted_and_listed_link_definitions_reparse_in_full` `:3743`, `task_markers_stay_with_their_own_item` `:3898`, `multi_line_reference_labels_reparse_in_full` `:4068`, `streamed_regressions_match_full_parse` `:3515` (minus the math cases).
  - All of it arrived in `c02739da3` #912. Optional, same commit: `crates/markdown/src/mend.rs:56-100` indexes open delimiters per character, so `_a `×40k then `a* `×40k stays linear. Roboco's `mend.rs:245` `rposition` is quadratic on such a tail.
- **Roboco:**
  - `crates/markdown/src/parser.rs:1312` (`has_link_defs`: top level only, one line);
  - `:1250-1258` (boundary = second-to-last block, no container step);
  - `:770` (task marker taken unconditionally).
- **Relation to known items:** distinct from A36/N5. A36 is the nesting cap + panic guard; its collapse tests (`collapsed_deep_items_keep_word_breaks`, `deep_collapsed_content_keeps_its_text`) belong to A36. #912's math rewrites stay declined (zatex).
- **Files to touch:** `crates/markdown/src/parser.rs` (+ tests), optionally `crates/markdown/src/mend.rs`. No Android Kotlin changes; Android and desktop both benefit.
- **Effort:** S–M. Port it together with A36.

### G2 (nit) — Subagent card tap haptic
- iPhone plays a light impact (`apps/ios/Zeron/Transcript/RowView.swift:333`). Android plays a selection tick (`transcript/widgets/SubagentLink.kt:54`).
- One line. Fold it into N6 if N6 is taken up (it came with #899/#988, after this half's commits).
- Effort S.

No other iPhone behaviour from these 24 commits is missing on Android beyond the known items:
- wave-9 18: Delete in the long-press menu;
- wave-9 19: the tab shell vs the one-row bar;
- #626: cache byte budget;
- A36: nesting cap.
