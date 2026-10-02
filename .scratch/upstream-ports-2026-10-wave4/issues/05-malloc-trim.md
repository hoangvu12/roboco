# 05 — Trim glibc malloc arenas on Linux

**What to build:** glibc gives every thread a 64MB arena (default cap
8 × cores) and trims only the top of a heap, so a headless engine held
6.6GB in 129 arenas after a day. Capping arenas (`M_ARENA_MAX=2`) cut idle
RSS but multiplied engine CPU ~6× in arena-lock contention — upstream
landed the periodic `malloc_trim(0)` instead, releasing free pages inside
every arena once a minute at ~zero cost while chats stream. Port
`spawn_malloc_trimmer()` (thread "malloc-trim", 60s period, cfg
`all(target_os = "linux", target_env = "gnu")`, called once from `main()`
after the panic hook installs — our allocator is macOS-only cfg, so Linux
runs glibc and is exactly the target case) into `apps/roboco/src/main.rs`,
beside the existing mimalloc global allocator, and append the glibc
findings to our existing `docs/memory-plan.md`.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `4a7ef2cc` (#635) — `apps/zeron/src/main.rs` (+29),
`docs/memory-plan.md` (+16). Our `apps/roboco/src/main.rs` already carries
the mimalloc `#[global_allocator]` pin (mimalloc is macOS-scoped in the
workspace manifest — the glibc trim covers Linux; keep both).

**Verification budget:** `cargo check -p roboco -j 3`; `cargo check -p
roboco --target aarch64-unknown-linux-gnu` optional; verify the trimmer
spawns only on Linux-gnu via cfg (Windows build unaffected). No new tests
upstream; a smoke log line (`malloc_trim` debug) is enough.

- [ ] `spawn_malloc_trimmer` runs in headed + headless modes on Linux-gnu
- [ ] Windows build compiles unchanged
- [ ] `docs/memory-plan.md` gains the arena findings
- [ ] Port commit records upstream SHA

## Comments
