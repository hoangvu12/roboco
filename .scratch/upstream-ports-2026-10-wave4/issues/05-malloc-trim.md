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

**Status:** ready-for-human

**Upstream SHAs:** `4a7ef2cc` (#635) — `apps/zeron/src/main.rs` (+29),
`docs/memory-plan.md` (+16). Our `apps/roboco/src/main.rs` already carries
the mimalloc `#[global_allocator]` pin (mimalloc is macOS-scoped in the
workspace manifest — the glibc trim covers Linux; keep both).

**Verification budget:** `cargo check -p roboco -j 3`; `cargo check -p
roboco --target aarch64-unknown-linux-gnu` optional; verify the trimmer
spawns only on Linux-gnu via cfg (Windows build unaffected). No new tests
upstream; a smoke log line (`malloc_trim` debug) is enough.

- [x] `spawn_malloc_trimmer` runs in headed + headless modes on Linux-gnu
- [x] Windows build compiles unchanged
- [x] `docs/memory-plan.md` gains the arena findings
- [x] Port commit records upstream SHA

## Comments

- Ported `4a7ef2cc` (#635) by intent into `apps/roboco/src/main.rs`:
  `spawn_malloc_trimmer()` (thread "malloc-trim", 60s period, cfg
  `all(target_os = "linux", target_env = "gnu")`, `libc::malloc_trim(0)`
  with the SAFETY comment + debug log line) called once from `main()`
  after the panic hook installs, inside the existing `long_running`
  block (headed None + Headless command — our equivalent of upstream's
  placement; one-shot CLI commands skip it, as upstream). The trimmer
  sits beside the existing macOS-cfg'd mimalloc `#[global_allocator]`;
  both kept. `docs/memory-plan.md` gains the 2026-09-29 Linux-arenas
  findings paragraph (rebranded path), placed after the mimalloc purge
  note like upstream.
- Exclusions: none — no macOS packaging or CI hunks existed in the
  upstream diff for this ticket.
- Verification: `cargo check -p roboco -j 3` clean on the host
  (aarch64-unknown-linux-gnu — the ticket's optional target, which is
  this box's host: the trimmer compiles and is reachable on
  Linux-gnu). Windows cross-check
  (`--target x86_64-pc-windows-msvc`) is blocked on this box by a
  PRE-EXISTING environmental failure (`ring`'s build script: "GNU
  compiler is not supported for this target" — verified identical on
  the clean base commit; a transitive rustls dep, unreachable from
  this cfg-gated edit). The trimmer + call site are both
  `#[cfg(all(target_os = "linux", target_env = "gnu"))]` — compiled
  out on Windows exactly as upstream; real Windows coverage comes from
  `windows.yml` CI once the lane merges. No new tests upstream; the
  smoke log line (`malloc_trim` debug) is the ported observability.
