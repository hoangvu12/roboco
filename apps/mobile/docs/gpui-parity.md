# GPUI parity inventory

## Goal

This app adapts Roboco's GPUI product surface to Android. Phone and narrow-screen layout may adapt, but a feature is not removed
because it does not fit the current iOS client.

Recovered baseline: Kratos `feat/mobile-ui-parity`; authentication is ported to current Roboco.

## Workstreams

| Surface | GPUI reference | Mobile requirement |
| --- | --- | --- |
| Shell and navigation | `crates/ui/src/shell.rs`, `shell/` | Spaces, sessions, tabs, archive, history, rails, routes, and responsive panels. |
| Transcript | `crates/ui/src/transcript.rs`, `markdown/` | Streaming rows, markdown, code, tool details, artifacts, follow behavior, selection, and links. |
| Composer and queue | `crates/ui/src/composer.rs`, `queue.rs`, `attachments.rs` | Drafts, mentions, slash commands, attachments, questions, run/steer/interrupt, and queue editing. |
| Workspace | `crates/ui/src/files/` | Tree, search, read/write editor, previews, media, tabs, and workspace watches. |
| Terminal | `crates/ui/src/terminal/` | Open, input, resize, streamed output, and lifecycle controls. |
| Changes and reviews | `crates/ui/src/changes.rs`, `comment_ui.rs`, `comments.rs` | Checkout diffs, file navigation, inline comments, and change-request views. |
| Browser and previews | `crates/ui/src/browser/` | Browser surface, preview lifecycle, and browser-specific controls. |
| Settings and account/device flows | `crates/ui/src/settings/`, `pairing.rs`, `app_menus.rs` | Appearance, agents, devices, accounts, notifications, shortcuts where applicable, and pairing. |
| Shared visual system | `crates/ui/src/theme.rs`, `typography.rs`, `motion.rs`, `icons.rs` | Tokens, fonts, icons, status language, and motion faithful to GPUI. |

## Current Android contract

The UI is shared TypeScript, with Android as the supported native target of this
port. The local Expo module wraps `target/tailcat/RobocoTailcat.aar`, opening a
loopback route to the same engine listener used by desktop Roboco. Authentication
uses Roboco pairing grants and the shared engine client, not the old signed-peer
ControlRpc relay. See `../README.md` for commands and `../HANDOFF.md` for evidence.

One paired engine is selected at a time. The app includes the initial UI surfaces
above; this inventory is a development target, not a claim of full desktop parity.
Offline transcript persistence, multiple saved engines, attachment flows, richer
settings, browser previews, and Android Auto remain future work.
