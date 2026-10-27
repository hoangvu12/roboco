# 75 — Small leftovers: Luau highlighting, overscroll stuck band, updater caps, Linux corner rounding, desktop entry, docs line, test parity, #1060 check (#1031, #966 d, #535, #340, A25, §G)

**Lane:** 4b Features: desktop UI. **Effort:** M (a bag of S items. Any
item can be split off if it grows).

**What to build:**
1. **#1031 (`187b40243`): Luau syntax highlighting.** Add the
   `tree-sitter-luau` 1.2.0 dependency, the rewritten query in
   `crates/syntax/src/lib.rs`, an icon in `file_icons.rs`, and a fence
   alias in `transcript.rs`.
2. **#966 slice d: overscroll stuck after an unended touch.** Port
   `TOUCH_TIMEOUT`, the stretch cap and the 4 tests into `overscroll.rs`.
3. **#535 remainder (`94304283a`; wave-7 17's #535 half):** release-
   metadata size and time caps (`RELEASE_METADATA_MAX_BYTES`/`_TIMEOUT`,
   a streamed bounded read in place of `resp.json()`/`.text()` at
   `crates/update/src/lib.rs:179`), and a quiescence re-check right before
   the swap (`apply_inner(require_quiescent)`; `:1158-1171` checks once).
4. **#340 (`1de3b4bae`) remainder: the explorer column rounds the
   window corners** on Linux floating CSD windows (`files_panel.rs`).
5. **A25 (open PR #658): Roboco is listed twice in GNOME/KDE menus.**
   `dist/roboco.desktop:11` `Categories=Development;Utility;` becomes a
   single main category.
6. **§G docs:** `docs/harness-skill-completion.md:48-49` should say
   Settings → Shortcuts → Composer completion.
7. **Test-parity crumbs (no behaviour change):**
   - 5 unix-gated harness-update coordinator tests from `987bb2f87`
     (hold-back, withdrawn policy, npm own prefix, unprovable owner stays
     manual, changed-since-check re-check), reworked onto the built-in
     feed;
   - the proto omission assertion for `compatibility`/`heldBack`
     (`proto/src/agent.rs:895`);
   - the rpc test `scoped_subscription_returns_before_first_item_and_cancels_silence`
     (`e7ddbbe72`);
   - `boot_compaction_reclaims_the_freed_store_pages` (`8646bde07`);
   - the Pi row in `tests/model_catalog.rs`;
   - the cursor shim agent-dir marker created `0o600` (`c87daf244`,
     `shim.mjs:195`).
8. **#1060 (`e16cc3e99`) check:** upstream changed `crates/preview`
   (`login.rs`, `service.rs`, `signaling.rs`, +`login_tunnel_e2e.rs`) so
   provider sign-ins get past the preview room's device limit. Read the
   `crates/preview` half against Roboco's engine-local preview relay.
   If the same limit can block a sign-in tunnel here, port that half with
   its e2e test. Otherwise record why not. The `edge/` half is out.
9. Optional (dev tooling): the mock `/fanout N` knob + dev-demo fan-out
   session (`591824881`, `harness/src/mock.rs`, `scripts/dev-demo.sh`)
   and the `docs/mcp.md` "Forks and the session card" paragraph.

**Upstream:** `187b40243`, `cc7da5c2a` (slice d), `94304283a`,
`1de3b4bae`, PR #658, `987bb2f87`, `e7ddbbe72`, `8646bde07`,
`c87daf244`, `e16cc3e99` (preview half), `591824881` (optional).

**Roboco files:** as listed per item.

**Excluded:** #1060's `edge/` half (cloud).

**Proto change:** no.

**Blocked by:** 01.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §B, §G, A25, late window #1060;
`part-03.md` `94304283a`; `part-02.md` `1de3b4bae`; `pass2-big-2.md`
gaps 11, 14, 15; `pass2-big-3.md` gaps 3–5; `pass2-big-1.md` gap 10.

- [ ] Each item done with its test, or explicitly skipped with a reason in the commit
- [ ] #1060 verdict recorded
- [ ] Commit records each SHA/PR it carries

## Comments
