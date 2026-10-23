# 10 — Queue polish, drag a chat from the sidebar to mention it (upstream #855)

**What to build:** Port `b65b4753` (#855): queue polish plus dragging a
chat from the sidebar into the composer mentions it. The mention
substrate is new proto (`chat_mentions.rs`, `invocation.rs`) and
composer chip work (`composer/chip.rs`) — it builds directly on the #775
chips ported in ticket 05. 11 files, +652/−71.

**Why now:** the chips substrate (ticket 05) is the prerequisite; with
it landed, this is the natural follow-on and part of the queue/composer
parity run.

**Steps:**
1. `git show b65b4753` — port the proto mention deltas (adapted onto
   ticket 05's landed shapes), the drag-source/drop-target wiring in
   `shell.rs`/`shell/spaces.rs`/`shell/chat_dropzone.rs`, and the queue
   polish.
2. Port the tests (drag-to-mention, queue restore interplay with chips).

**Excluded upstream hunks:** cloud/sync hunks if any.

**Blocked by:** 05.

**Status:** done (ported in c98bd6b)

**Verification budget:** `cargo check -p roboco-proto -p roboco-ui` +
queue/dropzone tests; wiregen check if proto shapes moved. Source:
`.scratch/upstream-drift/2026-10-11.md` § `b65b4753`.

- [x] Drag-to-mention ported end to end
- [x] Queue polish carried by intent
- [x] Tests ported; wiregen green
- [x] Commit records upstream SHA `b65b4753` (#855)

## Comments

Ported b65b4753 (#855) onto ticket 05's landed chips substrate (port
commit c98bd6b, same file/line footprint as upstream: 11 files, +652/−71).

Carried: proto `chat_mentions.rs` (new — `roboco-chat:` scheme, canonical-
link-only parsing, `chat_mention_prompt` flattening applied inside
`harness_prompt`), `composer/chip.rs` `ChipKind::Chat` with the
chat-round-line glyph, `composer.rs` chat-mention links in the projection
(titles always shown — a chat's "path" is its id), `add_chat_mention`, the
`chat_mentions_show_as_chat_chips_with_their_title` test, queue tray polish
(concentric rows/controls, 4px gaps + inset above the composer, frosted
lifted row under the pointer, edge fade ending at the clip edge, trays stop
occluding during drags so drops reach the conversation's drop zone —
`queue_panel_surface(_, dragging)`), the deferred/anchored window-
coordinate sidebar drag row (slide + slide_x) with `mention_drop_overlay`
+ `SidebarSessionDrag` drop on `chat_dropzone`/`side_chats`/main shell, and
the todo tray matching the queue's radius/occlusion behavior. The end-to-end
drag test lands in `settings_modal_regressions` (upstream's
`voice_stage_opens_over_settings` anchor does not exist in roboco) and uses
`roboco_proto::WorkspaceScope`.

No cloud/sync hunks existed in this commit to exclude — the whole change is
local UI + proto. Renames per AGENTS.md: `zeron-chat:` → `roboco-chat:`,
"the Zeron chat" / "the Zeron read_chat tool" → Roboco (roboco-mcp ships the
`read_chat` tool, so the pointer stays accurate).

`cargo run -p wiregen` produced no delta (chat_mentions has no TS wire
types) and `cargo run -p wiregen -- --check` is green, so nothing needed
committing in `web/packages/proto`.

Verification: `cargo check -p roboco-proto -p roboco-ui --tests` clean;
targeted suites green — the new drag-to-mention test, `queue::` 21 (incl.
`drops_on_the_tray_reach_the_drop_zone_beneath_it`,
`the_lifted_row_follows_the_pointer_within_the_list`, the two concentric
radius tests), `shell::spaces::` 30, todo 15, `composer::tests` 138,
`shell::chat_dropzone_tests` 4, `settings_modal_regressions` 9, proto lib
106.
