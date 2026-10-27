# 22 — Mobile-core terminal emulator (upstream #988/#1007 slices, `fd7d268ca` + `dfbb6876c`)

**What to build:** Port the mobile-core terminal slices: `crates/mobile/src/terminal/{mod.rs (+824), color.rs (+187), keys.rs (+270)}` — the shared
terminal emulator (grid, colors, key translation) the iOS terminal views
drive — plus `client_ffi/companions.rs` (+100) and the `layout/tools.rs`
hunks (+98). Substrate for ticket 23 (Android terminals).

**Port note:** the emulator is transport-agnostic; the FFI surface stays
additive (`EngineInfo.capabilities`-style gating is the app's job).

**Excluded upstream hunks:** the WebRTC "direct link to the host" +
`crates/client` live/demo parts (cloud), all `apps/ios` files, the iOS
probe/Debug files.

**Blocked by:** none.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-mobile` + mobile layout
tests; `cargo xtask android-core` builds. Source:
`.scratch/upstream-drift/2026-10-25.md` § D-b.

- [ ] Terminal emulator + companions FFI ported (no WebRTC/client)
- [ ] Commit records upstream SHAs `fd7d268ca` + `dfbb6876c` (slices)

## Comments
- 2026-10-25: **DECLINED with ticket 23** — no Android terminals, and the
  mobile-core terminal emulator was substrate-only for that feature.
  Nothing else consumes it (the desktop terminal is gpui-side).
