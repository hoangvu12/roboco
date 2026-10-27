# 05 — Drop ts-rs, wiregen and the `web-client` capability

**What to build:** Wire types become plain Rust/serde. Their only TypeScript consumer is about to be deleted.

- Delete `crates/wiregen/` and remove it from the workspace `members` in the root `Cargo.toml`.
- Strip `TS` from every `#[derive(…)]`, delete every `#[ts(…)]` attribute and every `use ts_rs::TS` / `ts_rs` import in `crates/{proto,doc,engine,update}`. Research counted about 176 derives, 20 attrs and 24 imports in 20 files; `git grep -n "ts_rs\|ts(\|TS)"` is the source of truth. **Serde attributes stay byte-identical.** ts-rs derives are additive and never changed serde output, so no wire change is expected.
- Remove `ts-rs` from the 5 crate `Cargo.toml`s and `[workspace.dependencies]`. Refresh `Cargo.lock` with `cargo update -w` or a plain build. Do not bump unrelated crates.
- `crates/proto/src/workspace.rs`: remove `capabilities::WEB_CLIENT` (`"web-client"`) from the constants and from `CURRENT`, and update its test. Leave fixture JSON that contains the string as stored data (ADR 0005 freezes on-disk names). Native clients never read it.
- Comments that say a type exists "for the web" or "mirrored by wiregen" get a one-line touch-up where they would otherwise mislead.

**Blocked by:** 04

**Status:** ready-for-agent

**Parent:** `.scratch/web-removal/spec.md`

- [ ] `cargo tree -i ts-rs` is empty. `crates/wiregen` is gone.
- [ ] `git grep -n "ts_rs\|#\[ts(\|derive(.*\bTS\b" -- crates apps` is empty
- [ ] `capabilities::WEB_CLIENT` is gone. `CURRENT`'s test is updated. `git grep -n "WEB_CLIENT"` is empty outside fixtures and `.scratch`.
- [ ] Serde contract tests are green: `cargo test -p roboco-proto -p roboco-doc -p roboco-update -p roboco-rpc` and `cargo test -p roboco-engine --lib`
- [ ] Android core still builds its UniFFI bindings (`cargo check -p roboco-mobile -p roboco-mobile-client`)
- [ ] `cargo check --workspace --all-targets` is clean with no new warnings

## Comments
