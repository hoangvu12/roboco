# Research: video attachments + web attachment upload health

Date: 2026-10-21 · Scope: roboco @ `aa192005` (main), zeron mirror @ `69e64ef5`.

## Q1 — Does roboco (or zeron) accept video attachments?

**No — neither does. Attachments are images-only at every layer, by design.**

Evidence (roboco; zeron's mirror is byte-identical modulo rebrand in these spots):

| Layer | Behavior | Source |
| --- | --- | --- |
| Desktop staging | `format_by_extension` accepts only `png jpg jpeg gif webp svg bmp tif tiff`; `Composer::add_paths` **silently skips** anything else ("matching the original's `image/*` filter") | `crates/ui/src/attachments.rs:203-214`, `crates/ui/src/composer.rs:6203-6221` |
| Desktop picker | `prompt_for_paths` has **no file-type filter** — you can pick an `.mp4`, it is then silently dropped | `crates/ui/src/composer.rs:6667-6678` |
| Web staging | `EXT_TO_FORMAT` same image set; picker input `accept="image/png,…,image/tiff"`; drop/paste skip non-images silently | `web/packages/app/src/lib/attachments.ts` (`EXT_TO_FORMAT`), `components/attachments/attachment-strip.tsx:183` |
| Engine upload | Bytes are format-agnostic, but read-back jail `mime_by_ext` is image-only (`+ avif/heic`); 24 MB cap | `crates/engine/src/uploads.rs:436-448` |
| pi harness | Inlines only png/jpg/gif/webp as `{type:"image"}` blocks (5 MB inline cap); everything else rides the prompt as a path ref | `crates/harness/src/pi/mod.rs:1541-1600` |
| claude harness | Same: base64 image content blocks only | `crates/harness/src/claude/mod.rs:588-651` |
| opencode harness | `mime_for`: images + `pdf` only; v2 path passes `file://` URIs and lets the server sniff | `crates/harness/src/opencode/mod.rs:2286-2300` |

`grep -ri video` across zeron's `crates/harness/src/{pi,claude,opencode,codex,acp}` → zero hits.

### Ecosystem state (what a video PR would ride on)

- **OpenCode** (a zeron harness): `read` tool now returns `audio/*` / `video/*` as model-native attachments; V2 sessions route by model input capabilities (opencode.ai/v2/docs/attachments, PR anomalyco/opencode#18005, issue #22258). Zeron's v2 `prompt_body_v2` already passes attachments as `file://` URIs — **the opencode path is the cheapest video win**; only the UI staging filter and engine read-back jail stand in the way.
- **Gemini CLI**: supports `video:path` multimodal input natively (google-gemini/gemini-cli#2556, #3869) — but it is not a zeron harness.
- **Claude Code**: image/PDF only; the Anthropic API has no video input. A video to a claude chat could only ever ride the path-ref text (agent reads it with its own tools).
- **pi**: inline message content is image-only (`ImageContent`); pi's `fetch_content` tool *can* analyze local videos via ffmpeg, but that is tool-driven, not an attachment.
- **ACP**: image content blocks only — no video in the protocol.

### What a video feature actually needs

1. UI staging: video extensions + non-image presentation (gpui/the browser can't thumbnail a video without frame extraction; at minimum a "video" file-icon row, no thumbnail).
2. Size cap: 24 MB `MAX_ATTACHMENT_BYTES` is far too small for video; needs a separate, larger video cap (chunked upload already handles big files; the 1 MiB relay frame budget is respected by 680 k-char chunks).
3. Engine: read-back jail + `mime_by_ext` need `video/mp4|quicktime|webm|matroska`.
4. Harness delivery, per harness:
   - opencode: near-free (file:// URIs; add mimes to `mime_for`).
   - claude/codex/pi: path-ref only unless the upstream CLI gains native video parts.
5. Capability gating: most models reject video; the composer should warn per harness/model (OpenCode V2 already errors server-side on unsupported input).

A zeron PR that is realistically mergeable: "allow video attachments to opencode sessions" (staging + jail + `mime_for`, no thumbnails). Full cross-harness video is a project, not a PR.

## Q2 — "Attachments don't work in the web version"

**The web attachment path works — I verified the full wire against a real engine.** I built the `web_conformance` engine example and drove the exact client path (pair → auth → `UploadChunk` × 3 @ 680 000 b64 chars for a 2 MB file → `UploadCommit` → `ReadAttachmentChunk` loop): byte-identical round-trip, correct mime (`image/png`). The app suite is green too (`pnpm vitest run` in `packages/app`: 138 files / 2122 tests pass). The composer→strip→`sendRun`/`queueMessage` wiring is complete (`composer.tsx:2562`, `composer-actions.ts:sendRun`, `chat-page.tsx:533`).

### But I found one real web-only parity bug: the 30 s unary cap silently defeats the upload ladder

- Web `EngineClient.call` applies a blanket unary timeout, `#timeoutFor(method)`, default **30 s** (`packages/engine-client/src/client.ts:88,557-577`); the registry constructs clients with defaults (`registry.ts:238`).
- `UploadChunk`/`UploadCommit` are NOT in the special-cased list (only `Clone|Fetch` → 900 s, catalog lists → 100 s, harness updates → 240 s/1200 s).
- The app's attachment ladder intends **90 s first-window chunk / 30 s chunk / 150 s commit** (`lib/attachments.ts`: `FIRST_CHUNK_TIMEOUT_MS`, `CHUNK_TIMEOUT_MS`, `COMMIT_TIMEOUT_MS`) and races its own timers via `callWithTimeout` — but the *inner* `client.call` still aborts at 30 s. Effective budgets: 90→30, 150→30.
- The desktop has no such inner cap: the Rust RPC client (`crates/rpc/src/client.rs:149-168`) has **no per-call timeout**; `call_with_timeout` (attachments.rs:298) is the only ladder.
- Consequence: on a slow/cold link to a remote engine (the exact case the 90 s/150 s budgets were written for — cold-dial allowance), the web upload dies with `Engine request timed out: UploadCommit` → surfaced as *"Couldn't upload the attachment — the device may be offline."* The desktop survives the same link.

Fix sketch (web only): in `#timeoutFor`, treat upload/read methods like `Clone|Fetch` (the 900 s long budget) — the app-level `callWithTimeout` + whole-send deadline (`attachmentDeadlineMs`, ≤ 900 s) already bound the total; the client's inner cap should never be the binding constraint. Alternatively add a per-call `timeoutMs` override parameter to `call`.

**Fixed 2026-10-03:** `#timeoutFor` now routes `UploadChunk` / `UploadCommit` / `ReadAttachmentChunk` to the long budget (`client.ts`), so the app-level ladder is the binding constraint, matching the desktop (whose RPC client arms no per-call timer). Regression test added in `fake-server.test.ts` ("attachment transfers outlive the default unary timeout") with a control proving the tier is method-specific.

### Other plausible "doesn't work" UX causes (both platforms, worth knowing)

- **HEIC/AVIF photos are silently skipped**: the engine jail knows `heic`/`avif` (`uploads.rs:443-444`) but the UI staging set excludes them (gpui can't decode them). An iPhone default-format photo dropped on the composer does nothing, with no error. Same on web (not in `accept`, not in `EXT_TO_FORMAT`).
- Any non-image (including **PDF**, even though opencode's `mime_for` supports it) is dropped silently by design.

## Repo state note

The probe test I used (`web/packages/engine-client/tests/attach-probe.test.ts`) was removed after verification; the repo tree is clean. It would be worth re-adding as a permanent conformance case — upload/commit/read-back currently has **zero** real-engine coverage in CI (`conformance.test.ts` covers pairing/watch/mutate only; `web-smoke.test.ts` covers pair+watch+send).

## Q2b — Follow-up: "the attachment button just doesn't work" (two reported cases) — CONFIRMED, one root cause, fixed

User reports: (1) new-chat screen, desktop web — attach button dead; (2) mobile, existing chats — attach button not clickable. Both are the same CSS stacking bug in the web port:

In **expanded** pill mode the DOM (composer.tsx:3378+) is:

- `.composer-attach` (paperclip) — `position: absolute; left: 12px; bottom: ~14px`, 28×28 — FIRST child of `.composer-body`
- `.composer-actions` (model chip + send strip) — `position: absolute; left: 0; right: 0; height: 46px; padding-left: 42px` (`app.css:4171`) — LAST child

Neither has a z-index → same stacking context → DOM order decides → the full-width actions strip paints ON TOP of the paperclip. Its 42px left padding "reserves" the slot visually (the strip is transparent, so the button shows through), but **padding boxes hit-test**: every click/tap in the 12–40px slot lands on the strip, never on the button. No hover either. The button reads as dead.

When is expanded mode active?
1. **New-chat canvas — always** (`expandedRender = expanded || newChat`, composer.tsx:3273; the canvas composer never renders compact) → case 1. Desktop web, existing chat, single-line draft = compact (paperclip is an ordinary flex row item → works, matching the user's experience).
2. **Phone width — always**: `composerFlip` forces expansion when compact capacity < `MIN_COMPACT_INPUT_WIDTH = 200` (lib/composer-flip.ts:233); at phone viewports that is permanently true → case 2.
3. (Also affected: desktop web, existing chat, once the draft flips expanded via multiline text.)

Why the desktop app can't have this bug: the GPUI composer renders the attach button **inside** the actions row as its first child (`crates/ui/src/composer.rs:10525-10536` — `.child(div().flex_1()…child(attach))`), so there is nothing to cover it. The web port restructured it into an absolute sibling and reserved its space with padding — forgetting the padding box still hit-tests above the button.

**Fix applied** (web-only; zeron has no web client, so no upstream PR): `app.css` — `z-index: 1` on `.composer-pill[data-mode="expanded"] .composer-attach`, lifting the button above the transparent strip. The strip's interactive children (model chip, Send) sit far right of the 12–40px slot, so nothing else changes. Full app suite re-run: 138 files / 2122 tests pass.

Regression-test note: this class of bug (hit-target covered by a sibling overlay) is only observable in a real browser (jsdom does no hit-testing, and vitest loads CSS as no-ops). The node-driven `web-smoke` suite can't catch it; a Playwright e2e clicking `.composer-attach` on the new-chat canvas would.

## Sources

- Repo: paths cited in the tables above (roboco `main` @ `aa192005`; zeron mirror `zeron/main` @ `69e64ef5`).
- OpenCode attachments: https://opencode.ai/v2/docs/attachments/, https://github.com/anomalyco/opencode/pull/18005, https://github.com/anomalyco/opencode/issues/22258
- Gemini CLI video: https://github.com/google-gemini/gemini-cli/pull/2556, https://github.com/google-gemini/gemini-cli/pull/3869
- pi docs (local install): `@earendil-works/pi-coding-agent/docs` — inline content is image-only; video only appears in package-gallery metadata.
