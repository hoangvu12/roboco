# 28 — Tool calls: image previews, right-click Copy path/image, selectable output with a line cap, a question answer card that grows (#813 non-browser slices, A14)

**Lane:** 1d Fixes: sidebar + chat. **Effort:** M.

**What to build:** the parts of #813 that don't need the in-app browser
or the pin sync:
1. **Tool-call image previews.** Expanded tool calls preview the images
   they touch, and free them on collapse (`tool_images.rs`,
   `render_tool_image_strip`, the tool-images fixture example).
2. **Right-click any transcript image:** Copy path / Copy image
   (`open_image_menu`, `copy_image`). Ticket 51 reuses this for reply
   images.
3. **Selectable and copyable paths, commands and output in expanded tool
   calls** (`expanded_tool_call_text_drag_selects`), with the
   `OUTPUT_LINE_MAX_CHARS` cap.
4. **A14: multi-line answers to agent questions grow the card**
   (`answer_cap`; absent from `composer.rs:9839`). Today the question
   panel clips them to one line. This is upstream's merged fix for the
   bug that open PRs #738/#629 also target. Take #813's shape, and borrow
   #738's resize/top-padding clean-up only if the merged shape lacks it.

**Upstream:** `e5466c533` (#813), UI non-browser slices.

**Roboco files:** `crates/ui/src/transcript.rs`, new
`crates/ui/src/tool_images.rs`, `crates/ui/src/composer.rs`,
`crates/ui/examples/` (tool-images fixture).

**Excluded:** `browser/windows_webview.rs` and the WebView2 dependency
set (wave-7 22: out with the in-app browser). The engine path-jail half
is already ported (`fed992a35`). `apps/ios`, `crates/client`.

**Proto change:** no.

**Blocked by:** 01. Lands before 51.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A14, §B (#813 slices); `part-06.md` `e5466c533`;
`pass2-features-desktop.md` §3–4.

- [ ] A tool call that read a PNG shows its preview; collapsing frees it (UI test)
- [ ] Copy path / Copy image on transcript images (UI test on the menu)
- [ ] Tool output is drag-selectable; long lines are capped (UI test)
- [ ] A 5-line answer shows all 5 lines in the question card (UI test)
- [ ] Commit records `e5466c533` (non-browser UI slices) and the WebView2 exclusion

## Comments
