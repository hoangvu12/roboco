# Web client parity wiring fixes

**Status:** ready-for-agent

## Problem Statement

A parity audit of the desktop app against the web client (at v0.7.0) found a
set of behaviors where the desktop is wired and the web client is not. From
the user's perspective the web client misrepresents their data: side chats of
a standalone chat appear as standalone chats in the sidebar; running side
chats chime and post completion banners; the chat menu offers actions that
make no sense for a child chat; the sidebar row doesn't reflect a send that
is in flight; a pin that the engine rejects silently disappears; rows in the
archived shelf render mis-shaped. Beyond the sidebar, whole behaviors are
missing: workspace slash commands do nothing, a worktree-creating send runs
its setup actions invisibly, a desktop-sent appshot renders as a raw
untrusted-content blob, an agent-CLI update never announces itself, and
renamed/conflicted files lose their tree coloring.

A large share of the drift shares one root: the web client consults a chat's
parent linkage only in the explorer and command-palette paths, so every other
surface treats child chats as top-level chats.

## Solution

Close the audited wiring gaps on the web client in one batch, in six
clusters:

1. **Child-chat identity** — the sidebar list, chat menu, and notification
   driver all learn which chats are children and behave like the desktop:
   children never list as top-level rows, their menu drops the rows that
   don't apply, and their completions stay silent.
2. **Sidebar row truth** — rows reflect in-flight, failed, and queued sends;
   pin-limit rejections are validated and surfaced instead of silently
   reconciled away; the pinned section accepts a drag when empty; archived
   rows render as full cards; the one-list mode gains the collapsible
   "Sessions" section.
3. **Right-pane tabs** — a tab chip for a running subagent or side chat
   shows the live spinner.
4. **Cross-device rendering** — appshot-bearing user messages from the
   desktop render clean (marker stripped, attachment presented as an
   appshot); git-status tree coloring handles renamed-away ancestors and
   conflict states.
5. **Composer wiring** — the slash popup offers the workspace commands
   (model, new, resume, settings, diff, files, terminal, rename, stop) and
   executes them against web surfaces; a send whose run creates a worktree
   surfaces the setup outcome as a terminal tab and a failure notice; file
   tree/search rows can be dragged onto the conversation column (or a
   side-chat pane) as a file mention.
6. **Notifications** — an agent-CLI update posts a banner (gated by a new
   agent-updates notification preference) with dedupe, matching the desktop.

No engine, wire, or schema changes: every RPC involved already exists and is
declared to the web client.

## User Stories

### Child-chat identity

1. As a web client user, I want side chats to stay nested under their parent
   chat rather than appearing as standalone chats in the sidebar, so that my
   session list reflects the real structure of my work.
2. As a web client user who forks chats or lets the agent spawn side chats,
   I want the sidebar to list only top-level chats, so that a swarm of
   children doesn't flood the list.
3. As a web client user, I want the chat menu opened on a side chat to omit
   the rows that don't apply to a child chat (pin, archive,
   conversation-link copy), so that I am never offered an action the
   desktop itself withholds.
4. As a web client user, I want a running side chat to never chime or post
   a completion, input-request, or failure banner, so that background agent
   work stays as quiet on the web as it is on the desktop.
5. As a web client user, I want a side chat's live status still tracked
   under the hood, so that muting its notifications never hides its state
   from the explorer, palette, or tab surfaces that do show it.
6. As a desktop user, I want the web client to render my same chat
   structure, so that a colleague on the browser sees the same session list
   I see.

### Sidebar row truth

7. As a web client user, when my message is sent but not yet confirmed by
   the engine, I want the chat's sidebar row to show working status, so
   that I can see the send is in flight.
8. As a web client user, when a send failed delivery, I want the row's
   corner to read "Failed", so that I can spot the broken chat at a glance.
9. As a web client user, when a message sits in the queue, I want the row's
   corner to read "Queued", so that pending work is visible without opening
   the chat.
10. As a web client user, when I try to pin beyond the pinned-session
    limit, I want to be told the limit in the sidebar notice, so that the
    pin doesn't silently vanish on the next engine frame.
11. As a web client user, when an engine-side pin write fails for any
    reason, I want the failure surfaced, so that local state never silently
    diverges from the engine's.
12. As a web client user, I want to drag a chat onto the pinned area even
    when no pins exist yet, so that the first pin can be made by dragging
    rather than only through the menu.
13. As a web client user, I want archived rows to render with the same card
    shape as active rows — status corner, project @ device line,
    change-request badge — so that nothing is missing and rows are sized
    for what they actually render.
14. As a web client user with many chats, I want the one-list mode's
    regular chats to sit under a collapsible "Sessions (N)" section, so
    that I can collapse them out of the way of my pins and custom sections.
15. As a web client user, I want dropping a chat onto the collapsed
    Sessions header to open the section and accept the drop, so that the
    unpin drop target is always reachable.
16. As a web client user, I want collapsed sessions to be excluded from the
    visible row order and jump-target cycling, so that hidden rows are
    truly hidden.

### Right-pane tabs

17. As a web client user, I want a right-pane tab chip for a running
    subagent or side chat to show the live spinner in place of its static
    icon, so that I can tell which background surface is active at a
    glance.
18. As a web client user, I want the spinner to clear when the run comes
    to rest, so that the chip's state always tells the truth.

### Cross-device rendering

19. As a desktop user who sends a message with an appshot, I want the web
    client to render it clean, so that my colleague doesn't see a raw
    untrusted-content blob in the user bubble.
20. As a web client user receiving an appshot-bearing message, I want the
    attachment presented with its appshot label rather than a bare
    filename, so that the transcript reads the same as it does on the
    desktop.
21. As a web client user browsing the file tree, I want directories that
    only contain a renamed-away file to keep their status coloring, so
    that a rename's blast radius is visible.
22. As a web client user with a merge conflict, I want conflicting paths
    (added-and-added, deleted-and-deleted, unmerged) to color as conflict,
    so that the tree's danger states match the desktop.

### Composer wiring

23. As a web client user, I want the slash popup to offer the workspace
    commands — model, new, resume, settings, diff, files, terminal, rename,
    stop — so that the same shortcuts I know from the desktop work here.
24. As a web client user, I want `/model` to open the model picker, so that
    I can change agent, model, and reasoning without the mouse.
25. As a web client user, I want `/new` to start a new chat and `/resume`
    to open the conversation search, so that navigation lives in the
    composer.
26. As a web client user, I want `/settings` to open settings, so that the
    composer stays a keyboard path to every surface.
27. As a web client user, I want `/diff`, `/files`, and `/terminal` to open
    the matching right-pane surfaces for the active chat, so that panes are
    one keystroke away.
28. As a web client user, I want `/rename` to rename the active chat and
    `/stop` to interrupt the active run, so that chat management is
    reachable mid-typing.
29. As a web client user, I want chat-gated commands to be suppressed when
    no chat is active, so that the popup never offers something that can't
    run.
30. As a web client user typing a command that collides with a harness
    skill of the same name, I want the workspace command to remain
    reachable under its prefixed form, so that collisions never shadow
    either option.
31. As a web client user in a side chat, I want the workspace commands to
    route to my side chat's surfaces, so that the composer behaves the
    same there as in the main chat.
32. As a web client user whose send creates a worktree with an
    auto-running setup action, I want a terminal tab attached and selected
    for that setup, so that I can watch it happen.
33. As a web client user whose worktree setup action fails, I want a
    sidebar notice telling me so, so that failures are never silent.
34. As a web client user, I want to drag a file row from the tree or
    search onto the conversation column (or a side-chat pane) and get a
    file mention in that composer, so that referencing a file doesn't
    require typing its path.

### Notifications

35. As a web client user, when a monitored agent CLI update becomes
    available, I want a notification banner, so that I learn about it
    without visiting settings.
36. As a web client user, I want an "Agent updates" toggle in notification
    settings, so that I can mute just that banner kind.
37. As a web client user, I want the agent-update banner to dedupe across
    polls, so that a re-check doesn't re-notify me about the same update.
38. As a web client user, I want the banner to respect the background-only
    and master notification preferences, so that it follows the same rules
    as chat notifications.
39. As a web client user, I want clicking the agent-update banner to take
    me to the agents settings page, so that applying the update is one
    click away.

## Implementation Decisions

- **Sidebar row pipeline** — the row builder gains a child-chat exclusion
  matching the desktop's visible-chats predicate (non-archived, no parent
  linkage). Row display status derives from the existing send/echo state in
  addition to watched chat statuses, mapping an in-flight send to working;
  the row's corner gains failed/queued override chips. The builder's input
  shape does not change — the exclusion and status derivation are pure
  functions over existing stores.
- **Sessions disclosure** — the one-list mode's regular rows move under a
  collapsible "Sessions" disclosure with the desktop's semantics: collapsed
  state is in-memory only (a reload re-expands), collapsed rows are
  excluded from the visible-order helper, and the collapsed header is a
  drop target that expands on drop. The disclosure integrates with the
  existing keyed FLIP list as a section header key.
- **Chat menu row model** — the menu gains a side-chat flag; when set, it
  suppresses the same row set the desktop suppresses for child chats (pin,
  archive, conversation-link copy), leaving rename and delete.
- **Notification decision layer** — the sound/banner decider gains a
  child-chat gate: side-chat baselines stay tracked (so dots and statuses
  are current) but never emit completion, input-request, or failure
  sounds/banners. The session notification driver passes the chat's parent
  linkage into the decider.
- **Agent-update notifications** — a new agent-updates boolean joins the
  web client's notification settings (default on, healed like its
  siblings). The harness-update store becomes a notification source: an
  update becoming available posts a banner through the existing
  notification path, deduped by an update-wave key, click-routed to the
  agents settings page, and gated by the master, background-only, and
  agent-updates preferences.
- **Pin write path** — the sidebar pin write validates the
  pinned-session limit client-side and posts the desktop's limit message to
  the sidebar notice; the settings-sync write loop stops swallowing write
  failures and surfaces them through the same notice channel. The pinned
  section mounts whenever pins exist **or** a pin transfer is in flight,
  so the empty section can accept the first drag.
- **Archived shelf** — archived rows render through the same compact row
  shape as active rows (status corner, project @ device line,
  change-request badge), keeping the height model and the row renderer in
  agreement.
- **Right-pane surface facts** — the pane store's per-surface facts gain a
  running flag (subagent streaming, side-chat working); the tab chip swaps
  its leading icon for the existing glyph spinner while the flag is set.
- **Appshot rendering** — the transcript's user-message attachment parsing
  strips the appshot context marker (the untrusted observed-content block
  that wraps appshot references) and maps appshot attachments to their
  presentation label. This is display-side only; no new attachment kinds
  are created, and the web client still cannot capture appshots.
- **Tree status decoration** — the decoration mapper consumes each status
  entry's prior path (rename source) and colors ancestors of both paths;
  double-added, double-deleted, and unmerged entries map to the conflict
  state.
- **Slash completion catalog** — the invocations catalog gains workspace
  command rows (label, description, needs-chat gating, collision prefix)
  merged into the slash popup beside harness commands and skills.
  Execution goes through a small command dispatch registry that maps each
  command to existing web surfaces: model picker, new chat, conversation
  search palette, settings route, diff/files/terminal panes, rename flow,
  and interrupt. Side-chat composers route to their own surfaces through
  the same registry.
- **Worktree-setup outcome** — after a send whose run carries a worktree
  spec, the composer polls the project-action setup outcome RPC on the
  desktop's cadence (bounded retries, short interval). A running setup
  attaches and selects a terminal surface titled with the action name;
  a failed setup posts the failure to the sidebar notice. Repeated
  outcomes attach the same surface rather than duplicating tabs.
- **File-row drag into the composer** — the conversation column and the
  side-chat panes accept the existing internal workspace-path drag payload
  (already produced by tree and search rows) and convert it into a file
  mention in that column's composer, reusing the attachment strip's drop
  wiring rather than adding a parallel mechanism.
- **Scope of changes** — web client only. No engine, wire, or schema
  changes; every RPC used (setup-outcome polling, pin writes) already
  exists and is declared to the web client.

## Testing Decisions

- A good test here asserts external behavior: given fixture registry rows,
  frames, and store state, the row list's membership, order, statuses, and
  chips; the menu's row set; the decider's emit/no-emit verdicts; the
  catalog's offered rows; the parser's stripped output; the mapper's
  colors. No test reaches into component internals.
- **Pure-lib suites** (the primary seams, all pre-existing): the sidebar
  row builder and visible-order helper; the chat menu row model; the
  notification decision layer; the slash completion catalog; the
  user-message attachment parser; the tree decoration mapper. Prior art:
  the command-palette child-chat exclusion regressions, the explorer
  fixtures that build chats with parent linkage, the side-chat action
  suites, and the notification rule-table tests.
- **Store-level suites**: pin-limit validation and surfaced write failures;
  the right-pane running flag and tab facts; the transient empty pinned
  section during a transfer; the sessions disclosure state. Prior art: the
  right-pane store and sidebar store vitest suites.
- **RPC-path suites**: the worktree-setup poll (attach/select/failure
  paths) against a scripted fake engine session; the pin-rejection notice
  against the same fake. Prior art: the engine-client scripted fake server
  and conformance suites, and the side-chat first-send tests.
- Lane-final check stays the standard web typecheck plus the touched
  vitest suites.

## Out of Scope

Filtered out of this spec deliberately, with reasons:

- **Platform-bound (cannot exist on the web client)**: on-device
  dictation; appshot capture; the embedded browser right-pane surface;
  `roboco://` deep links; native window/menu chrome and OS-level window
  keys; per-attachment relay transfer percent (no web stream, recorded
  deviation).
- **Documented intentional divergences** (recorded in web code or tickets —
  changing them is a separate decision, not a parity fix): Steer/send-next
  queue actions (spec decision: no steer anywhere); mermaid rendering;
  per-line diff tokenization; MRU right-tab restore (accepted
  desktop-only); tab context menus and session-header side-chat/fork
  buttons (deferred — they need new web chrome-contract hosts); fork error
  surface placement; first-send mint placement; side-chat draft retention
  shape; side-chat composer width cap source (separate ticket); monochrome
  file-tab icons.
- **Motion/cosmetic-only**: drag ghost/gap/return-glide affordances; the
  unpin strip's over-suppression edge; code-fence scrollbar chrome.
- **Uncertain/unverified from the audit** (need a live check before
  becoming work): partial git-status frame notices; the worktree-spec send
  payload shape; syntax language coverage parity; plain-transfer edge
  autoscroll.
- **Engine-side or desktop-side changes**: all fixes are web-client
  wiring; engine and desktop behavior is the reference, not the target.

## Further Notes

- Provenance: a six-area parity audit (sidebar, right pane/tabs/side
  chats, composer/queue, transcript, settings/notifications, files/
  terminal/palette) at main `aa192005` (v0.7.0). The audit also corrected
  two non-gaps that should not be re-filed: working-tree discard **is**
  wired on the web client, and repo clone has no UI on either platform.
- The child-chat cluster shares one root — the web client only consults
  parent linkage in the explorer and palette paths — so the three fixes
  (list, menu, notifications) should land together and share fixtures.
- This is roboco-native work (upstream has no web client), so the upstream
  port workflow does not apply; no upstream SHAs are referenced. All state
  stays engine- or client-local per the engine-local data ADR.
- Vocabulary: user-facing web copy says "chat" (the web parity
  vocabulary decision); desktop code says "session". This spec uses the
  domain glossary terms — side chat/child chat for parented chats, chat
  status for live run state, queued message for held sends.
- Post-audit environment note: the original symptom report (and the
  missing pi context meter) came partly from a **stale engine** — the
  headless engine serving the user's web client was still roboco 0.4.0
  (pi-acp bridge, pre-side-chats). An upgrade kit at
  `/home/ubuntu/engine-upgrade/` stages the 0.7.0 swap; after the engine
  restarts, re-verify symptoms against 0.7.0 before counting any audit
  item as fixed or broken. The gaps in this spec were verified against
  v0.7.0 source on `main`, independent of that engine's version.
