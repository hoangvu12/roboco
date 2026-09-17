# 04 — Deletion pass (INVENTED items)

**What to build:** Sixteen surface-by-surface research passes each grepped
the web client for UI, CSS, and behavior that has no desktop counterpart at
all — chips, buttons, popovers, routes, and dead code someone added without a
Rust source to copy from. Per the spec's decision #4 ("Invented web-only UI is
deleted, not polished"), this ticket removes every one of them. After this
ticket, `grep`-ing the web source for the removed identifiers listed in this
ticket's acceptance criteria returns nothing, and the app still builds and
runs — nothing here is a redesign, only a subtraction, with a small stub
wherever removing something would otherwise leave a dangling import, an
unused prop, or a broken layout.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** every research file's §5 gap table, filtered to rows whose
`kind` column is `INVENTED` (including the `**INVENTED**` bold-markdown
variant and `INVENTED (harmless)`/`INVENTED (dead)`/`INVENTED (see analysis
below)`/`INVENTED (justified)`/`INVENTED (relative to the Changes tab)`
sub-tags used by individual files). Full grep performed across all 16 files;
the results are grouped by source file below. Files `03-markdown.md`,
`08-history.md`, `12-settings-shell-appearance.md`, `14-state-behavior.md`,
`15-foundations.md`, and `16-sidebar-body-add-space.md` contain **zero**
INVENTED rows (confirmed by grep — nothing to delete from those surfaces).

**Desktop reference (for lookups only):** none — by definition, INVENTED
items have no desktop source. Each row below cites the desktop's ABSENCE
(e.g. "no sandbox picker exists anywhere in `crates/ui`") as the reason for
deletion, not a file:line to port from.

**Web files to touch:** see each numbered item below; they span
`composer-pickers.tsx`, `picker-popover.tsx`, `chat-menu.tsx`,
`queue-panel.tsx`, `change-request-badge.tsx`, `lib/change-requests.ts`,
`transcript.tsx`, `space-filter.tsx`, `app-shell.tsx`, `preview-panel.tsx`,
`state/right-pane.ts`, `diff-view.tsx`, `file-tree-panel.tsx`,
`file-viewer.tsx`, `terminal-dock.tsx`, and `app.css`.

## 1. Context a fresh session needs

- "INVENTED" (per `TEMPLATE.md`'s legend) means the web has something the
  desktop does not — the opposite of MISSING. It does not mean "bad idea" —
  several rows below are explicitly noted by their research file as
  "harmless" or "acceptable" workarounds for something the web genuinely
  lacks (e.g. no embedded browser chrome). Read each item's "why" before
  deleting reflexively; a few items are deliberately KEPT per spec decisions
  and are called out as such.
- Two items named in this ticket's brief are explicitly **excluded** from
  this pass and must NOT be touched here even though they are INVENTED:
  - The **kebab chat-menu button** (`01-shell-chrome.md` row S26,
    `.chat-row-kebab`) — the desktop is right-click-only; deleting the kebab
    is real work that depends on ticket 10 (pickers and menus) shipping a
    working right-click/long-press menu trigger first. Leave it in place.
  - The **four-tab identity popover's overall structure**
    (`composer-pickers.tsx`'s `.identity-tabs` strip, `IdentityTab`,
    the four separate `PickerPopover` mounts) — rebuilding it as the
    desktop's single card with an icon tab strip + search + virtualized list
    + traits tray is ticket 10's job. This ticket deletes only the
    **Sandbox tab** and its picker (item 2 below) from inside that
    structure; the remaining three-tab shape stays as today's approximation
    until ticket 10 rebuilds it properly.
- "Deleting must leave the build green" means: after removing a component,
  handler, or CSS rule, there must be no orphaned import, no prop typed for
  a case that can no longer occur, no unused variable lint failure, and no
  visibly broken layout (a removed button's flex slot should collapse
  cleanly, not leave a gap). Each item below states what to stub.
- Vocabulary: "Queue" is the composer's non-steering send-while-busy action
  (`QueueMessage` with `holdForTurnEnd: true`); "Steer" is a different,
  engine-supported action the desktop's own UI deliberately never exposes —
  per spec decision #3, it does not exist on web either. Several deletions
  below are specifically about removing invented Steer-now surfaces that
  contradict this decision.

## 2. Deletions, grouped by research file

### 2.1 From `01-shell-chrome.md`

| # | Item | Why delete | File:line | What to stub so the build stays green |
|---|---|---|---|---|
| 1 | `.window-control-active` pressed state | The desktop's pane toggle has NO active/pressed visual state — it is a plain `window_control_button` | `components/titlebar.tsx:126` (the `paneOpen` prop driving the class), CSS rule in `app.css` | Remove the conditional class application; keep the `paneOpen` prop itself (other logic may read it) but stop mapping it to a CSS class on this button. Delete the `.window-control-active` CSS rule entirely once nothing references it — `grep -rn "window-control-active" web/packages/app/src` must return nothing. |
| 2 | `.identity-badge` | The titlebar identity row has no badge — only mark + title + "folder @ device" | wherever `.identity-badge` is rendered in the titlebar identity component + its CSS rule | Remove the badge element and its CSS. If the badge carried real information (confirm at implementation time what it showed), fold that text into the existing title/folder line instead of dropping it silently — but the BADGE SHAPE itself must go. |
| 3 | `.space-filter-sort` button | Fully styled 24×24 button with `aria-label="Sort chats"` and **no `onClick`** — inert, not wired to anything | `components/space-filter.tsx:72` | Remove the `<button className="space-filter-sort">` element entirely (do not leave a disabled/no-op button — an inert control is worse than no control). Its CSS rule (`.space-filter-sort` in `app.css`) is removed with it. A real view-options menu is out of scope for this ticket. |
| 4 | `/chat/$id/changes` and `/files` as routes | Changes and Files are pane surfaces on desktop, never routes; `chatIdOf` (`app-shell.tsx:385`) matches only `^/chat/<id>/?$`, so on these routes the pane column, its tabs, and the toggle all disappear today | the route definitions under `web/packages/app/src/routes/` for these paths | Remove the route files/definitions. Any internal link that navigated to `/chat/$id/changes` or `/files` must instead call `rightPaneStore.show(chatId, "changes" | "files")` and navigate to the plain `/chat/$id` route. Grep every `useNavigate`/`<Link to=` call targeting these paths and repoint it. |
| 5 | `canForward` hard-coded `true` | `NavHistory::can_forward()` is a real, stateful computation on desktop (`index + 1 < entries.len()`); the web has no forward-history tracking at all today, so hard-coding `true` always shows an enabled forward button that does nothing useful past the first back-navigation | `components/app-shell.tsx:239` | This is technically a WRONG BEHAVIOR row, not INVENTED, but the ticket brief calls it out for this pass — **stub it to `false`** (`canForward={false}`) rather than leaving the misleading hard-coded `true`, and leave a `// TODO(ticket 05): wire to NavHistory.canForward()` comment. Do not attempt to build real forward-history tracking here — that is ticket 05's job in full. |

### 2.2 From `02-transcript.md`

| # | Item | Why delete | File:line | What to stub so the build stays green |
|---|---|---|---|---|
| 6 | `.subagent-dot` colored status dots | Desktop shows a `mini_glyph_spinner` while Running, an ordinary quiet chip when Done, and the danger tint when Failed — NO status words, NO dots at all | `transcript.tsx:909` (`subagent-dot subagent-dot-${tool.subagentStatus}`), CSS in `app.css:4010` | Remove the `<span className={\`subagent-dot ...\`}>` element and its three color variants in CSS. Keep `tool.subagentStatus` itself (still needed to pick the danger tint / spinner in whatever replaces this — building the real `GlyphSpinner`/danger-tint replacement is ticket 19's job, not this one; for now the chip may render with NO running/done/failed indicator at all, which is closer to broken-but-honest than a fabricated dot). |
| 7 | `.chip-pending` dot | No per-chip pending dot exists on the desktop at all | `transcript.tsx:913` (`{!tool.resolved && !spawn && <span className="chip-pending" aria-label="Running" />}`) | Delete the conditional span. `tool.resolved`/`spawn` remain in scope (used elsewhere in the same component for other conditions) — verify no other code path depended on this specific JSX existing. |
| 8 | `.chip-error-mark` "failed" text | No per-chip "failed" text on desktop; failure shows only via the danger tint plus the summary's own "· N failed" count | `transcript.tsx:914` (`{tool.isError && <span className="chip-error-mark">failed</span>}`) | Delete the conditional span. `tool.isError` remains in scope (still drives the danger tint elsewhere). |
| 9 | `"No messages yet."` / `"Loading…"` empty-state paragraphs | The desktop transcript renders NOTHING when empty — the shell owns the new-chat hero / empty case, not the transcript component | `transcript.tsx:78`, `:377`, `:426` (three separate occurrences: one in a subagent-doc loader, two in the main transcript body) | Replace each `<p className="chat-transcript-empty">...</p>` branch with `return null;` (or an empty fragment where the surrounding structure requires an element). Do NOT build the shell's real new-chat hero here — that is ticket 15's job; for now the transcript area is simply blank in these states, matching desktop's "shell owns it, transcript renders nothing" structure even before the shell actually owns anything. |
| 10 | `.transcript-error` floating card | No such floating banner exists on desktop — it shows a 24px `"Engine off. Cached history is read-only."` / `"Reconnecting… Cached history is read-only."` strip above the list, owned by the SHELL, not the transcript | `transcript.tsx:446-453` (the `{error !== null && <div className="transcript-error" role="alert">...}` block, including its Retry button), CSS in `app.css:3420` | Delete the floating card and its CSS. Keep the `error`/`onRetry` props on the component signature (do not break the caller) but stop rendering them here — until ticket 18/01 builds the real 24px shell strip, an active transcript-stream error is simply not surfaced in the UI. Note this regression explicitly in the ticket's Comments section when implementing, since silently swallowing a real error string is a real (temporary) UX loss. |
| 11 | `.status-strip` rendered inside `.transcript-wrap` | The desktop reserves this strip in the SHELL, not the transcript component | wherever `<div className="status-strip" />` is rendered inside `transcript.tsx` (line 435 per current source) | Remove the element from `transcript.tsx`. If the shell does not yet reserve equivalent vertical space (ticket 06 owns building the real shell-level status strip), removing it here may cause a layout shift when the working indicator appears — acceptable for this ticket; note it in Comments as a known regression until ticket 06 lands. |

### 2.3 From `04-composer.md`

| # | Item | Why delete | File:line | What to stub so the build stays green |
|---|---|---|---|---|
| 12 | Sandbox picker (tab + list + popover) | No sandbox picker exists anywhere in `crates/ui`; `SandboxLevel::WorkspaceWrite` is written on chat creation and preserved otherwise — never user-selectable | `composer-pickers.tsx:30` (`SANDBOX_LEVELS` const), `:45` (`"sandbox"` in the `OpenPicker` union), `:75,81` (`sandboxItems`/`sandboxLabel`), `:119` (the `IdentityTab id="sandbox"` row), `:176-185` (the `open === "sandbox"` `PickerPopover` block) | Delete `SANDBOX_LEVELS`, remove `"sandbox"` from the `OpenPicker` type union, delete the `sandboxItems`/`sandboxLabel` computations, delete the `IdentityTab id="sandbox"` JSX, delete the `open === "sandbox"` popover block. Keep `draft.sandbox: SandboxLevel` on `DraftConfig` itself (composer-actions.ts) — it still needs a value for the wire — but always construct it as the fixed literal `"workspace-write"` wherever a draft is created/defaulted, matching desktop's "written on create, preserved otherwise" rule. `grep -rn "SANDBOX_LEVELS\|SandboxLevel" web/packages/app/src` should show only the fixed-literal write path afterward, no picker UI. |
| 13 | `.identity-tabs` positioning CSS (partial) | Covered by the exclusion in §1 for the tab-strip structure itself, but the Sandbox tab's own CSS entries (if any exist beyond the shared `.identity-tabs`/`.identity-tab-*` rules) must go with it | `app.css:1847-1858` region — remove only rules that exclusively target the sandbox tab; leave the shared `.identity-tabs`/`.identity-tab` rules for ticket 10 to rebuild | Verify no CSS selector left behind references `sandbox` after item 12's markup is gone. |
| 14 | `@keyframes composer-flip` | Dead CSS — never referenced by any `animation-name` anywhere in the codebase | `app.css:2045-2052` | Delete the `@keyframes` block. |
| 15 | `.composer-input-wrap` inline `animationDuration` | Dead — sets a duration for the unused `composer-flip` animation | `composer.tsx:469` | Delete the inline style property. |
| 16 | `setChatConfig` mutation before every send | Desktop sends model/reasoning/options ON the `RunRequest` itself; only a genuinely NEW chat writes a persisted `ChatConfig`, via `Mutate createChat` | `lib/composer-actions.ts:130` (`await maybePersistConfig(...)` call inside `sendRun`), `:211-221` (`maybePersistConfig` itself) | Remove the `maybePersistConfig` call from `sendRun`'s body (line ~130). Per the ticket brief's explicit instruction, **do not delete the `persistChatConfig`/`maybePersistConfig`/`sameChatConfig`/`sameModelOptions` functions themselves** — they may still be the right mechanism for a genuinely-new-chat's config write (verify against `chat-actions.ts`'s create-chat path at implementation time; if create-chat already writes config a different way, these functions become fully dead and MAY be deleted then, but confirm before removing). `buildRunRequest` must already carry `draft.harness`/`model`/`reasoning`/`modelOptions` on the request itself (it does, per current source) — no request-shape change needed, only removing the redundant pre-send mutation. |
| 17 | Footer chip radius/border | `footer_label` has NO radius and no background on desktop | `app.css:3340` (`border-radius: var(--rb-radius-control)` on the composer footer chip) | Remove the `border-radius` (and any accompanying `background`/`border` if present on the same rule) from the footer chip's CSS. |
| 18 | Filename caption under each attachment thumb | Desktop shows no name in the attachment strip | `.composer-staged-name` — `app.css:2194-2201`, and its rendering call site in `attachments/attachment-strip.tsx` | Delete the CSS rule and the JSX element that renders the filename caption. |
| 19 | Inline "📎 Attach" button | Desktop has exactly one attach affordance (the paperclip in the composer's actions cluster) | `.composer-attach-button` — `attachments/attachment-strip.tsx:210-220`; only rendered when `pickerRef` is absent, but `chat-page.tsx` always passes one, so this is already dead code in practice | Delete the conditional block and the CSS rule. Confirm via grep that `pickerRef` is indeed always passed from every call site before deleting (per the research file's claim) — if a call site is found that omits it, that call site needs the shared paperclip wired in instead of silently losing attach capability. |
| 20 | Drop overlay ("Drop images to attach") | This belongs to the shell's `#chat-dropzone`, not the composer's attachment strip | `.composer-attachments-overlay` — `attachments/attachment-strip.tsx:203-207` | Delete the overlay element and its CSS from the attachment strip. Porting the shell-level drop veil is out of scope for this ticket (no shell dropzone ticket is named in this batch) — note in Comments that drag-and-drop-to-attach has no UI feedback until that lands. |
| 21 | Upload progress bar (in the strip) | Desktop publishes progress into `AppState.begin_upload_progress` and renders it elsewhere; the attachment strip itself has no bar | `.composer-attachments-progress` — `attachments/attachment-strip.tsx:234-244` | Delete the progress-bar element and its CSS from the strip. Keep the underlying `uploadProgress` callback/state plumbing in `composer.tsx` (still useful data — ticket 17 decides where progress is actually surfaced) — only the strip's own bar rendering goes. |
| 22 | `@media (max-width:768px) { font-size:16px }` responsive font bump | Desktop has no responsive font bump; this is real web-only CSS, not the flip-morph invention pattern | `app.css:2054-2059` | Per the research file's own caveat, this MAY be a legitimate mobile-Safari zoom-prevention workaround rather than pure invention. **Do not delete outright** — flag it in this ticket's Comments for a product decision, since phone widths are explicitly out of scope for this whole effort (spec decision #5: "the existing phone layer... stays as is; do not break it"). Leave this rule in place. |

### 2.4 From `05-pickers-popovers.md`

| # | Item | Why delete | File:line | What to stub so the build stays green |
|---|---|---|---|---|
| 23 | `Tab` closes the popover | No Tab handling anywhere in `popover.rs`/`pickers.rs` | `picker-popover.tsx:106-110` (the `case "Tab":` branch in the keydown handler) | Delete the `case "Tab":` branch entirely (falls through to no handling, i.e. the browser's native focus-tab behavior takes over, which is what desktop effectively gets for free since it has no such handler either). |
| 24 | Harness "CLI not detected" disabled rows | `offered_harnesses` on desktop FILTERS uninstalled harnesses out entirely — no disabled row, no secondary text is ever shown | `picker-popover.tsx:208-215` (`harnessPickerItems` rendering `disabled` rows with `"CLI not detected"` secondary text) | Change `harnessPickerItems` to filter out (not disable) any harness whose `installed`/`enabled` flag is false, matching `offered_harnesses`'s filter-not-disable behavior. This changes list contents, not just styling — verify the picker's "no results" empty state still reads sensibly when every harness is filtered out. |
| 25 | Chat menu: `onWheel` closes the menu | No wheel dismissal anywhere in `popover.rs` | `chat-menu.tsx:121` (`onWheel={onClose}`) | Delete the `onWheel` prop from the backdrop element. |
| 26 | Chat menu: opaque backdrop div | Context menus on desktop have NO scrim — only `occlude()` (an invisible click-catcher) | `.menu-backdrop` — `app.css:4208` (`position: fixed; inset: 0; z-index: 50`) | Marked "harmless" by its own research row — the backdrop IS already fully transparent (no `background` property paints anything). **No deletion needed**; verify at implementation time that no `background`/`background-color` has been added to `.menu-backdrop` since the research pass, and leave it as a transparent occluder. |

### 2.5 From `06-queue-attachments-comments.md`

| # | Item | Why delete | File:line | What to stub so the build stays green |
|---|---|---|---|---|
| 27 | "Steer now" queue-row button | Desktop's queue row has exactly ONE primary action, Send now — Steer is an engine RPC with NO UI entry point anywhere in `queue.rs` ("All providers use Send now", per the desktop's own comment); this directly contradicts spec decision #3 ("Steer-now does not exist on web") | `components/queue-panel.tsx:96` (`const sent = await store.steerNow(row.id);`), `:280-288` (the button JSX + its "Steer now" label) | Delete the "Steer now" button JSX and the `steerNow` call site in the row component. If `QueueStore.steerNow(...)` (in `state/queue-store.ts`) has no other call sites after this removal, it becomes dead code — leave the method defined (do not delete the store method itself; a future capability the desktop doesn't expose either might still want it available, and the research file explicitly offers "gate it behind a to-be-designed capability" as an alternative to full removal) but ensure nothing in the UI calls it. Keep only the Send now button. |
| 28 | "Hold" chip on queue rows | `hold_for_turn_end` is never surfaced in the desktop's row UI — engine-side only, gates auto-drain | `queue-panel.tsx:265` (`{row.holdForTurnEnd === true && <span className="queue-row-chip">Hold</span>}`), `.queue-row-hold` class application at `:221`, CSS in `app.css:1508` | Delete the conditional `<span>` and the `queue-row-hold` class from the row's className template. Delete the `.queue-row-hold` CSS rule. Keep `row.holdForTurnEnd` itself on the data model — it is still meaningful to the engine, just not surfaced in this row's UI. |
| 29 | Dead `queueMessage` export (note only — do not delete) | `lib/queue-actions.ts::queueMessage` exists but has zero call sites anywhere in the app | `lib/queue-actions.ts:37` | **Do not delete this function.** The fix for its zero-call-sites problem is wiring it into `Composer.submit()`'s busy+non-steering branch, which belongs to ticket 13 (composer core) per the research file's own fix note. This row exists in this ticket only as a note: leave `queueMessage` defined and unused for now: confirm the build's lint config does not fail on an unused *exported* function (exports are conventionally exempt from no-unused-vars; if this repo's lint config is stricter, add a narrowly-scoped suppression comment rather than deleting the function). |
| 30 | Dead `QueueStore.renewEdit` (note only — do not delete) | `state/queue-store.ts:241` exists but has zero call sites in `chat-page.tsx`/`composer.tsx` | `state/queue-store.ts:241` | **Do not delete this method.** Wiring a 20s heartbeat while an edit is open belongs to ticket 16 (queue panel). Same lint-exemption note as item 29. |

### 2.6 From `07-changes.md`

| # | Item | Why delete | File:line | What to stub so the build stays green |
|---|---|---|---|---|
| 31 | File header status word (`"new"`/`"deleted"`/`"renamed"`) | No such element on desktop — status is only ever expressed via the notice row | `diff-view.tsx:293-304` (`FileHeaderRow`'s extra `<span class="diff-file-status">`) | Delete the span and the string-selection logic feeding it. The notice row (wherever `"New file"` etc. already renders) is untouched — it already carries this information per the desktop's actual behavior. |
| 32 | File header index chip (`#{fileIx+1}`) | No such element on desktop | `diff-view.tsx:286-288` (`<span class="diff-file-index">`) | Delete the span. |
| 33 | "Create PR" button + compare-URL fallback | **No create flow exists anywhere on the desktop** — no wire `CreateChangeRequest` RPC, no button; the sidebar/composer badge only opens an EXISTING PR's `summary.url`. The web's best-effort provider compare-URL guesswork (GitHub/GitLab/Bitbucket/AzureDevOps/Codeberg) is explicitly called out by its own research row as "not something to reconcile by reading the Rust harder, since it simply isn't there" | `components/change-request-badge.tsx::CreateChangeRequestButton` (lines 72-87+, the whole component), `lib/change-requests.ts::changeRequestCreateUrl` | Delete `CreateChangeRequestButton` and `changeRequestCreateUrl` entirely, and every render call site that mounts the button (find via `grep -rn CreateChangeRequestButton`). Where a call site currently branches on "PR exists → open badge; PR doesn't exist → show Create button," change it to "PR exists → open badge; PR doesn't exist → render nothing" (matching desktop: the badge simply doesn't appear until a PR already exists). Do not invent a disabled/greyed "Create PR" placeholder — that would just be a smaller invented affordance. |

### 2.7 From `09-files-tree-editor.md`

| # | Item | Why delete | File:line | What to stub so the build stays green |
|---|---|---|---|---|
| 34 | Chevron rotation animation | Desktop swaps the icon instantly (`ALT_ARROW_RIGHT`↔`ALT_ARROW_DOWN`) — no tween at all | `app.css:2815-2826` (the `transition: transform ...` rotating a `▸` glyph) | The research row itself says "keep or drop per product call — currently not 1:1." Given spec decision #4's blanket "invented is deleted, not polished," delete the transition and swap to an instant icon change (matching desktop exactly) rather than leaving an unresolved judgment call. |
| 35 | File size shown in tree rows | Tree rows never show size on desktop | `file-tree-panel.tsx:108` (`<span className="files-row-size">{formatBytes(entry.size)}</span>`) | Delete the span. `entry.size` remains on the data model (harmless, unused-in-this-view). |
| 36 | File size shown in viewer header | Breadcrumb toolbar never shows size on desktop | `file-viewer.tsx:67` (`<span className="files-viewer-meta">{formatBytes(file.size)}</span>`) | Delete the span. (This is the same element `10-files-preview.md`'s row 689 also names — one deletion satisfies both files' gap rows; see §2.8.) |
| 37 | Symlink name suffix (`" ↪"`) | Desktop changes the ICON (`FileIconIdentity::symlink`), never appends a text glyph to the name | `file-tree-panel.tsx:106` | Delete the string-concatenation appending `" ↪"`. A real symlink icon swap is out of scope here (file icons in general are a MISSING item elsewhere, not this ticket's job) — for now a symlink row simply looks identical to a regular file/folder row until the icon work lands. |

### 2.8 From `10-files-preview.md`

| # | Item | Why delete | File:line | What to stub so the build stays green |
|---|---|---|---|---|
| 38 | Breadcrumb byte-size badge | Not present on the desktop's breadcrumb toolbar at all | `file-viewer.tsx:67` (same element as item 36 above — one deletion, listed under both source files since both research passes independently flagged it) | Same fix as item 36 — do not delete twice; this row exists so a grep of either research file's citations is satisfied. |

### 2.9 From `11-terminal-preview.md`

| # | Item | Why delete | File:line | What to stub so the build stays green |
|---|---|---|---|---|
| 39 | Terminal tab strip horizontal scroll | `render_tab_bar` on desktop is a plain flex row with no scroll handling — with enough tabs it just overflows | `app.css:2379-2391` (`.term-tabstrip { overflow-x: auto; scrollbar-width: none; }`) | The research row calls this "arguably an improvement... not a blocking issue." Per spec decision #4's blanket rule, still remove it for strict parity: drop `overflow-x: auto` and `scrollbar-width: none`, letting the tab strip overflow exactly like desktop. |
| 40 | `preview-panel.tsx` as a standalone right-pane surface | **What the desktop does instead** (per `11-terminal-preview.md`): there is no standalone "Preview" surface at all. The discovered-dev-server list is the EMPTY-TAB BODY of an embedded native Browser tab (`browser/view.rs::preview_body`, shown only while `page.url.is_none() && previews_task.is_some()`) — the browser tab's own chrome (address bar, back/forward/reload, its own tab strip) is what frames it, not a purpose-built header bar. The web has no embedded browser tab type at all (desktop-only per §6 of that research file — no browser-in-a-pane engine exists on web), so there is nothing to fold this into today | `components/preview-panel.tsx` (the whole file — both the standalone surface registration AND its own invented header bar, `:36-60`, "Previews" / title / url / Reload / Open / Close), `state/right-pane.ts:16-18` (`"preview"` in `RightSurface`/`RIGHT_SURFACES`/`SURFACE_TITLES`/`SURFACE_ICONS`) | This is the one deletion in this ticket that removes an entire working surface with no direct replacement, because its replacement (an embedded browser tab type) does not exist on web and is not in scope for any ticket in this batch. Remove `"preview"` from `RightSurface`/`RIGHT_SURFACES`/`SURFACE_TITLES`/`SURFACE_ICONS` in `right-pane.ts` and delete `preview-panel.tsx` and its mount point in the right-pane host. **Confirm before deleting**: check whether any ticket in the dependency chain after this one (07 "right pane host and multi-instance tabs" is the most likely owner) expects a Preview surface to still exist in some form — if ticket 07's own scope reintroduces it as part of a generalized tab model, coordinate so this deletion isn't immediately re-added; absent such a finding, delete outright per the research file's explicit "no browser chrome to reuse" framing. Note in Comments that discovered dev servers have NO UI at all on web after this deletion until an embedded-browser-tab feature is scoped (not currently ticketed). |

### 2.10 From `13-settings-sections.md`

No deletions from this file. Its one INVENTED row (`EngineDrawer vs Devices`,
row 512) is explicitly marked "Keep, but do not present it as the Devices-page
port" by its own research analysis (§6's "Architectural note: EngineDrawer is
not Devices") — `EngineDrawer` solves a real problem (which paired engine is
active) that the desktop's `settings/devices.rs` does not solve the same way,
and likely belongs to ticket 31 (Fleet) as a legitimate, intentional surface.
Do not delete or modify `components/engine-drawer.tsx` in this ticket.

## 3. Full list of removed identifiers (for the acceptance grep)

```
window-control-active
identity-badge
space-filter-sort
SANDBOX_LEVELS
composer-flip
composer-staged-name
composer-attach-button
composer-attachments-overlay
composer-attachments-progress
chip-pending
chip-error-mark
subagent-dot
chat-transcript-empty
transcript-error
CreateChangeRequestButton
changeRequestCreateUrl
diff-file-status
diff-file-index
files-row-size
files-viewer-meta
queue-row-hold
PreviewPanel
preview-panel
```

(`canForward`, the Sandbox `IdentityTab`/`PickerPopover` JSX, the Tab-closes
handler, the wheel-closes handler, the "Steer now" button JSX, and the
`.term-tabstrip` scroll CSS are structural/behavioral removals without a
single greppable class/identifier each — verify those by the specific
file:line diffs in §2, not by a class-name grep.)

## 4. Gaps this ticket closes

Every row in §2 is a gap of kind `INVENTED` — the web has UI, CSS, or
behavior the desktop does not, and this ticket's fix is uniformly "delete
it" (with the stub noted per row where deleting outright would break the
build). See §2's per-file tables for the full item / kind / why-delete /
file:line / stub columns; they are not repeated here to avoid the two
sections drifting out of sync. The one exception, `EngineDrawer` (§2.10), is
recorded as INVENTED by its source research file but is explicitly NOT a gap
this ticket closes — it is a legitimate surface under a different name, kept
as-is.

## 5. Do not

- Do not delete the kebab chat-menu button (excluded per §1 — waits for
  ticket 10).
- Do not rebuild the four-tab identity popover as the desktop's single card
  in this ticket (excluded per §1 — that is ticket 10's job); only the
  Sandbox tab inside it goes.
- Do not delete `queueMessage` or `QueueStore.renewEdit` — they are dead but
  correct; their fix is wiring them up (tickets 13/16), not removing them.
- Do not delete `maybePersistConfig`/`persistChatConfig`/`sameChatConfig`/
  `sameModelOptions` in `composer-actions.ts` — only the pre-send call site.
- Do not touch `.menu-backdrop` (item 26) beyond verifying it stays
  transparent — it is correctly harmless as-is.
- Do not delete the `@media (max-width:768px)` font-size rule (item 22) —
  flag it for a product decision instead; phone widths are out of scope for
  this whole effort and this rule may be load-bearing for mobile Safari.
- Do not delete `EngineDrawer` (§2.10) — it is a legitimate surface under a
  different name, not a parity bug.
- Do not invent a replacement UI for anything removed here beyond the exact
  stub described in that item's row (e.g. no placeholder "Create PR
  (coming soon)" button, no disabled Sandbox tab, no "Preview unavailable"
  card unless a row explicitly calls for one).

## 6. Acceptance

- [ ] Every identifier in §3's list returns zero matches:
      `grep -rn "<identifier>" web/packages/app/src` for each, run as one
      batch and pasted into the PR description.
- [ ] `composer-pickers.tsx` has no `SandboxLevel`/`SANDBOX_LEVELS` picker UI;
      `draft.sandbox` is always constructed as the literal `"workspace-write"`.
- [ ] `right-pane.ts`'s `RightSurface` union no longer includes `"preview"`;
      `preview-panel.tsx` is deleted; the right-pane host no longer mounts it.
- [ ] `app-shell.tsx`'s `canForward` prop reads `false` (not hard-coded
      `true`), with a comment pointing at ticket 05.
- [ ] `change-request-badge.tsx` has no `CreateChangeRequestButton` export;
      `lib/change-requests.ts` has no `changeRequestCreateUrl` export; no
      call site renders a create-PR affordance when no PR exists yet.
- [ ] `transcript.tsx` renders nothing (not a placeholder paragraph) for the
      loading/empty/error states this ticket touches; `.status-strip` is no
      longer rendered inside `transcript.tsx`.
- [ ] `queue-panel.tsx` shows only the Send now button per row — no Steer now
      button, no Hold chip.
- [ ] `pnpm -r build` is green (typecheck passes with no orphaned imports or
      unused-required props).
- [ ] Package vitest is green — no test asserted against any removed
      selector/behavior (update or delete any test that did).
- [ ] Manual smoke: `web_smoke` (ticket 01) still boots, a chat still opens,
      the composer still sends, the queue panel still shows queued rows with
      a working Send now button, and no removed element leaves a visible
      layout gap (collapsed flex slots, not empty boxes).

## Comments

(empty; appended during implementation)
