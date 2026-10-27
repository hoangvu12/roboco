# 51 — Markdown images in agent replies render inline on desktop (#1036, part 2)

**Lane:** 4a Features: engine + agents (UI files). **Effort:** M.

**What to build:** `![alt](path)` in a reply renders inline, read
through 50's folder-jailed `ReadChatImageChunk`. Port
`ui/src/reply_images.rs` (cache, decode off the UI thread,
aspect-correct scaling, eviction), the `markdown/render.rs` image arm,
`attachments.rs` hooks, and right-click Copy image (reusing ticket 28's
image menu). A missing or refused image shows the alt text and a quiet
"image unavailable" mark, not an error.

**Upstream:** `5d2b91901` (#1036): `ui/src/{reply_images,attachments,transcript}.rs`,
`ui/src/markdown/render.rs`.

**Roboco files:** the same paths in `crates/ui/src/`.

**Excluded:** `pull_request_media.rs`. Mobile Markdown images aren't in
#1036; record a follow-up if wanted.

**Proto change:** no (uses 50's).

**Blocked by:** 50, 28.

**Status:** ready-for-agent

**Source:** `part-07.md` `5d2b91901`.

- [ ] A reply with `![plot](./out/plot.png)` renders the image (UI test with a fixture engine)
- [ ] Outside-folder paths show alt text only
- [ ] Copy image works from the context menu
- [ ] Commit records `5d2b91901` (UI half)

## Comments
