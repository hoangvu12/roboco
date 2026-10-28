# 05 — Drop ts-rs, wiregen and the `web-client` capability

**What to build:** Wire types become plain Rust/serde. Their only TypeScript consumer is about to be deleted.

- Delete `crates/wiregen/` and remove it from the workspace `members` in the root `Cargo.toml`.
- Strip `TS` from every `#[derive(…)]`, delete every `#[ts(…)]` attribute and every `use ts_rs::TS` / `ts_rs` import in `crates/{proto,doc,engine,update}`. Research counted about 176 derives, 20 attrs and 24 imports in 20 files; `git grep -n "ts_rs\|ts(\|TS)"` is the source of truth. **Serde attributes stay byte-identical.** ts-rs derives are additive and never changed serde output, so no wire change is expected.
- Remove `ts-rs` from the 5 crate `Cargo.toml`s and `[workspace.dependencies]`. Refresh `Cargo.lock` with `cargo update -w` or a plain build. Do not bump unrelated crates.
- `crates/proto/src/workspace.rs`: remove `capabilities::WEB_CLIENT` (`"web-client"`) from the constants and from `CURRENT`, and update its test. Leave fixture JSON that contains the string as stored data (ADR 0005 freezes on-disk names). Native clients never read it.
- Comments that say a type exists "for the web" or "mirrored by wiregen" get a one-line touch-up where they would otherwise mislead.

**Blocked by:** 04

**Status:** done

**Parent:** `.scratch/web-removal/spec.md`

- [x] `cargo tree -i ts-rs` is empty. `crates/wiregen` is gone.
- [x] `git grep -n "ts_rs\|#\[ts(\|derive(.*\bTS\b" -- crates apps` is empty
- [x] `capabilities::WEB_CLIENT` is gone. `CURRENT`'s test is updated. `git grep -n "WEB_CLIENT"` is empty outside fixtures and `.scratch`.
- [x] Serde contract tests are green: `cargo test -p roboco-proto -p roboco-doc -p roboco-update -p roboco-rpc` and `cargo test -p roboco-engine --lib`
- [x] Android core still builds its UniFFI bindings (`cargo check -p roboco-mobile -p roboco-mobile-client`)
- [x] `cargo check --workspace --all-targets` is clean with no new warnings

## Comments

- 2026-10-28 (implementer): a scripted strip over `crates/{proto,doc,engine,update}` removed 176 `TS` / `ts_rs::TS` derive entries, 20 `#[ts(…)]` attributes and 14 `use ts_rs::TS;` lines across 20 files. Every derive was single-line, so no other tokens moved, and every serde attribute is byte-identical. `crates/wiregen` is deleted and gone from workspace `members`. `ts-rs` is gone from the four crate manifests and `[workspace.dependencies]` (wiregen's was the fifth). `cargo update -w` only *removed* lock entries (`ts-rs`, `ts-rs-macros`, `wiregen`, `termcolor`): 40 deletions, no bumps.
- `capabilities::WEB_CLIENT` is gone from the constants and from `CURRENT`, and `engine_info_uses_camel_case_fields` no longer lists `"web-client"`. **Also fixed, recorded:** that test was already red on `main`/`web-final`, because it never listed `"rooms-v2"` after fleet follow added `ROOMS_V2` to `CURRENT`. CI does not run `roboco-proto`'s lib tests. It now lists `rooms-v2`. Stored data that holds `"web-client"` stays untouched as fixture JSON: `crates/engine-client/src/registry/fixtures/paired-engines-v1.json` and its round-trip assertion in `registry/tests.rs`, plus Android's `EngineDetailModelTest` capability lists.
- Comment touch-ups where they would mislead: `proto/agent.rs` (the "ts-rs mirrors it" quirk note), `proto/compat.rs` (the "web client cannot skew" aside), `proto/{layout,motion}.rs` and `ui/motion.rs` (the artifact is now "the generated theme artifact Android pins"), and `roboco-mcp/src/lib.rs`. Other prose mentions of "the web client" in engine comments are historical and left as is (spec: reference comments stay).
- `cargo tree -i ts-rs` → "package ID specification `ts-rs` did not match any packages". `git grep -n "ts_rs\|#\[ts(\|derive(.*TS" -- crates apps` → empty. `git grep WEB_CLIENT` outside `.scratch` → empty.
- `cargo test -p roboco-proto -p roboco-doc -p roboco-update -p roboco-rpc` green. `cargo test -p roboco-engine --lib`: 445 passed, 3 failed. Two are the host-only `source_control` git tests (global `insteadOf`). The third, `terminals::windows_tests::conpty_cleanup_kills_descendants_on_close_shutdown_and_drop`, timed out on startup under load and passes alone (1 passed, 19 s). `cargo check -p roboco-mobile -p roboco-mobile-client` is clean. `cargo +1.99.0 check --workspace --all-targets`: no errors and no new warnings; ts-rs's "failed to parse serde attribute" warning is gone.
