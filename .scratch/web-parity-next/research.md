# Web client drift survey — next candidates (research only)

**Tickets:** `issues/01-syntax-language-coverage.md` (ready), `02-footer-chip-state-and-refs.md`
(ready), `03-footer-popover-direction.md` (ready), `04-command-palette-rows.md`
(ready), `05-completion-popup-scroll.md` (ready),
`06-queue-lease-failure-placement.md` (ready),
`07-pause-animations-background.md` (ready), `08-devices-grouping.md` (ready),
`09-settings-cosmetics.md` (ready), `10-link-menu-decisions.md` (needs-triage).

**Status:** research only, nothing implemented. Method: three read-only
sweeps (composer surfaces; settings/dialogs/palettes; transcript/panes/
terminal/files) against the desktop reference in `crates/ui/src`, skipping
everything the web-parity-fixes spec already landed (sidebar rows/menus/
child-chats, tab spinners, appshot rendering, git-status coloring, workspace
slash commands, worktree-setup outcome, file-drag mentions, notifications)
and every documented intentional divergence.

**Headline:** overall parity is strong — the sweeps found ~20 new gaps, of
which 3 are user-visible and substantive, ~6 are small wiring items, and the
rest are cosmetic. Two items the previous spec parked as "unverified" are
now resolved enough to act on (syntax coverage: a real gap; partial
git-status frame notices: still needs a live engine).

## Substantive, user-visible

1. **Code-fence syntax coverage** — desktop tree-sitter highlights 28
   languages (`crates/syntax/src/lib.rs:806-833`); web covers 18 + json/yaml/
   html special cases (`lib/syntax.ts:115-137, 166-178`). **css, toml,
   markdown, jsonc, dockerfile, lua, nix, make** fall through to
   comments/strings-only highlighting — css and markdown fences are common
   in assistant output and render unhighlighted on web. The prior spec
   listed "syntax language coverage parity" as uncertain and never resolved
   it. Close: add the 8 missing languages (css and toml first).
2. **Composer footer chip state staleness (new-thread canvas)** —
   desktop bumps a generation, cancels in-flight loads, clears the branch
   pick, resets checkout kind and refs on every space/device switch
   (pickers.rs:712-736); web keeps `draftBranch`/`checkout`/`refs` in local
   state with no reset (new-thread-selectors.tsx:201-203,
   composer-footer.tsx:107-109) — switching projects leaves a stale
   "From {oldref}" label, stale worktree detection, stale refs. Also: the
   worktree-reuse pick doesn't reset the checkout kind (desktop
   pickers.rs:1599-1604 sets `checkout = Local`; web pickRef
   composer-footer.tsx:688-696 records only the name, so the chip keeps
   reading "New worktree"). And lazy refs (web loads on popover open only,
   composer-footer.tsx:707-733) leave the chips at "Select ref"/"Current
   checkout" where the desktop eagerly shows the current branch
   (pickers.rs:3193, 2166-2173).
3. **Command palette rows** — three small gaps in one surface: action rows
   lack the `kbd_hint` shortcut badges the desktop renders
   (command_palette.rs:280-300 vs command-palette.tsx:425-440); hover does
   not move the highlight (desktop `hover_command` :155-163 moves `active`;
   web rows have no pointer-enter handler); archived rows are not muted
   (desktop dims them, shell.rs:6594-6598; web `ChatRow` never reads
   `row.archived` though lib/command-palette.ts:114 supplies it).

## Wiring

4. **Workspace-file link context menu** — desktop offers "Open with default
   app" / "Show in folder" (markdown/link_interaction.rs:443-458); web's
   menu has only open/copy (markdown.tsx:455-476). Browser-hosted checkouts
   make OS open/reveal impossible without an engine RPC — needs a parity
   decision (engine RPC or recorded exclusion).
5. **External-link menu's "Open links in Roboco" toggle row** —
   link_interaction.rs:493-521 vs absent on web; follows from the excluded
   embedded-browser surface, but the exclusion was never recorded to cover
   the menu row/setting.
6. **Popover direction on the new-thread canvas** — desktop opens
   Branch/Checkout menus **below** the trigger when ≥180px fits
   (pickers.rs:2767-2801); web pins `anchorAbove` (composer-footer.tsx:447/
   546/660), covering the input pill. May be desktop drift since ticket 09/10
   pinned above — verify against the current desktop before changing.
7. **Settings → Appearance: "Pause animations in background"** — desktop row
   (appearance.rs:3879-3917) with the reduce-motion port; web persists the
   field (ui-settings.ts:445) but renders no control.
8. **Settings → Devices grouping** — desktop splits "This device"/"Other
   devices" labeled sections (devices.rs:623-637); web renders one flat card
   with a "This device" badge (settings-devices.tsx:246-268).

## Cosmetic (batchable)

9. Mention/slash popups don't scroll the active row into view on arrow
   navigation (desktop composer.rs:7070/:7402; web resets scrollTop only on
   new results — mention-popup.tsx:94-99, slash-popup.tsx:44-49).
10. Queue edit-lease failures surface as sidebar toasts on web
    (queue-panel.tsx:186-196) where the desktop uses the composer's red
    failure notice (queue.rs:1460/:1559/:1630) — same copy, different place.
11. Files settings row label drift: "Show all files" (web
    settings-files.tsx:75) vs "Show hidden and ignored files" (desktop
    files.rs:198); the autosave control is 5 pill buttons vs the desktop's
    select dropdown (files.rs:88-112).
12. Row icons 16px desktop vs 14px web in mention/slash rows (desktop
    composer.rs:7168/:7565; web pinned 14 by ticket 14 — desktop grew them
    since).
13. Device popover end-alignment (pickers.rs:5293-5310) vs start-aligned on
    web; the SwitchRef dim of the whole ref list (pickers.rs:3650-3666) is
    absent on web.
14. Accounts "Forget" affordance: icon-only trash with tooltip (desktop
    accounts.rs:1150-1175) vs a text danger button (web
    settings-accounts.tsx:445).
15. Web-only affordance to record or remove: the terminal toggle chip on the
    new-thread target row (new-thread-selectors.tsx:110-145) — desktop's
    canvas target row shows only device+project chips
    (pickers.rs:3084-3177).

## Verified at parity (do not re-audit)

Wizard (reducer, auto-advance, failure restore); slash popup row content;
review comments end-to-end; attachment strip + lightbox; rename/delete/
space/device/section/project-action/discard/tree-confirm/login dialogs'
copy and keyboard contracts; add-space palette (web-native surface,
fully specced); titlebar cluster; general/appearance/notifications/
shortcuts/agents/accounts/remote-access/archived settings content;
transcript rows (hover copy, folds, jump pill, badges, retry delivery);
markdown (code headers, tables, task lists, blockquotes, file-ref wells,
link hover cards); tool groups (fold chrome, spawn chips, blob fetch);
diff pane (scope, folds, stats, review comments, commit pins); history
pane; file viewer (edit/save/reveal/breadcrumbs/preview); terminal
(height clamp, tab chrome, OSC titles, scrollback, resize debounce);
context meter; queue rows.

## Still unverified (needs a live engine)

- Partial git-status frame notices in the files tree — both sides subscribe
  to the same status stream; the notice path isn't statically comparable.
- Model picker W7 (search scope per tab) — see
  `.scratch/model-picker-parity/research.md` §2.
