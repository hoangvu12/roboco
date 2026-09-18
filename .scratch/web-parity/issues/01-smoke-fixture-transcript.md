# 01 — Smoke fixture renders a transcript

**What to build:** Today `web_smoke` boots a browser session whose composer is
permanently disabled — the harness catalog never answers, so nothing can ever
be sent and the transcript stays empty forever. After this ticket, opening
the printed pairing URL shows a chat ("Browser smoke chat") with an enabled
composer; typing "hi" and pressing Enter round-trips through a scripted mock
harness and renders a transcript with a markdown heading, a numbered list, a
fenced code block, and two tool-call rows. This is the fixture every later
visual-parity ticket screenshots against, so it must be reliable, not just
possible.

**Blocked by:** None — can start immediately.

**Status:** done

**Research:** `../../web-client/parity-checklist.md` section "Fixture gap";
`../../web-client/research/00-index.md` "Findings that change the plan" #1
and "Fixture gap (unchanged)".

**Desktop reference (for lookups only):**
`crates/engine/examples/web_smoke.rs` (the whole file, 72 lines);
`crates/engine/src/registry.rs::default_registry` (lines 365-581) and
`::descriptors` (lines 336-359) and `Slot::Lazy` (lines 112-121);
`crates/harness/src/mock.rs::MockHarness` (the `Harness` impl, `run()`);
`crates/harness/src/claude/mod.rs::ClaudeHarness::installed`/`resolve_executable`
(lines 128-147, 362-367);
`crates/harness/src/executable.rs::find_on_paths`/`find_on_paths_with` (lines
81-334);
`crates/harness/src/shell_env.rs` (whole file — the login-shell PATH probe).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `crates/engine/src/registry.rs` | edit | new `pub fn smoke_registry() -> HarnessRegistry` builder (eager mock only, zero lazy slots); factor the existing inline mock script out of `default_registry()` into a shared `fn mock_script() -> Vec<AgentEvent>` so both builders replay the identical transcript |
| `crates/engine/examples/web_smoke.rs` | edit | swap `HarnessRegistry::new()` for `roboco_engine::registry::smoke_registry()` (or re-export it from the crate root next to `HarnessRegistry`) |
| `web/packages/app/src/state/picker-catalog.ts` | none (verify only) | `PickerCatalog.loadHarnesses` — confirm it resolves against the fixture, do not edit unless the diagnosis below implicates it |
| `web/packages/app/src/components/composer.tsx` | none (verify only) | `composerReady` gate (line 379), harness-picker default (lines ~150-165) |

## 1. Context a fresh session needs

- `web_smoke` is a throwaway Rust binary (`crates/engine/examples/web_smoke.rs`)
  that boots a real `EngineCore`, serves it over a WebSocket at
  `127.0.0.1:27699`, seeds one chat ("smoke-chat" / "Browser smoke chat") with
  a `cwd` and recent activity, mints a pairing code, and prints
  `SMOKE READY <url>`. This is the only way to visually verify the web client
  against a live engine (`use-browser` skill drives the printed URL); every
  later ticket's "screenshot pair" acceptance criterion depends on it working.
- The engine's `HarnessRegistry` (`crates/engine/src/registry.rs`) has two
  kinds of slots: `Slot::Ready` (an already-constructed `Arc<dyn Harness>`,
  e.g. the eagerly-registered `MockHarness`) and `Slot::Lazy` (a static
  `HarnessDescriptor` plus an `installed: Fn() -> bool` probe and a
  `factory: Fn() -> Result<Arc<dyn Harness>, HarnessError>`, resolved only on
  first `resolve()`). `descriptors()` — the thing `ListHarnesses` calls — never
  forces a lazy `resolve()`, but it DOES call every lazy slot's `installed()`
  probe synchronously, on every single call (module doc: "Re-run on every
  `descriptors()` call — a CLI installed mid-session shows up on the next
  settings/picker open, no restart needed").
- `default_registry()` (used by the real desktop app and by `EngineProfile`
  bootstraps generally) registers one eager `MockHarness` plus **eight** lazy
  slots: `claude-code`, `codex`, `cursor`, `devin`, `grok`, `hermes`, `pi`,
  `opencode`. It also calls `roboco_harness::shell_env::prewarm()` first,
  which spawns a **plain OS thread** (`std::thread::Builder::new().spawn`,
  not a tokio task) that shells out to the user's login shell to snapshot its
  `PATH` (Unix only; a no-op on Windows/other, per `shell_env.rs`'s
  `#[cfg(not(unix))] pub fn login_shell_path() -> None`), caching the result
  in a process-wide `OnceLock`.
- The web composer's gate is `composerReady = harnesses.loaded`
  (`composer.tsx:379`), where `harnesses` comes from
  `PickerCatalog.getHarnesses()`. `loadHarnesses()`
  (`state/picker-catalog.ts:101-133`) sets `loading: true` before the
  `ListHarnesses` unary call and only clears it in `.then`/`.catch` — a call
  that never resolves (never errors, never returns) leaves `loading` latched
  `true` forever, and `composerReady` never becomes `true` (this exact
  failure mode is independently documented as a real, pre-existing web bug in
  `parity-checklist.md`'s "Defect found while comparing" section — out of
  scope to fix here, but it is the mechanism that turns a slow/stuck
  `ListHarnesses` reply into a permanently-disabled composer).
- Vocabulary: harness (not provider/agent-in-the-abstract), engine (the Rust
  process this ticket's binary boots), chat (not session/thread).

## 2. Spec

This ticket has no new UI component — the fix is entirely in the Rust engine
crate. The "component" being built is a registry constructor.

### 2.1 `roboco_engine::registry::smoke_registry()`

**Shape** (replaces **Layout**/**States**/**Interactions**/**Motion**/**Text**,
which do not apply to a non-visual Rust function):

```rust
/// A harness registry for manual browser smoke-testing: the scripted mock
/// harness only, no lazy slots. `default_registry()` additionally registers
/// eight lazy CLI-discovery slots whose `installed()` probes run synchronously
/// inside every `ListHarnesses` reply (see `descriptors()`'s doc comment) —
/// fine on a real desktop where they're near-instant `PATH` stats, but a
/// needless liability in a disposable fixture that must never depend on what
/// CLIs happen to be on the machine running `web_smoke`. This registry can
/// never leave `ListHarnesses` slow or dependent on host state, because it
/// has nothing to probe.
pub fn smoke_registry() -> HarnessRegistry {
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(MockHarness { script: mock_script() }));
    registry
}

/// The mock harness's scripted reply: a markdown heading, a numbered list, an
/// `Exec` tool call + result, a second `Exec` tool call + result, a fenced
/// `rust` code block, then `Done`. Shared by `default_registry()` and
/// `smoke_registry()` so both replay byte-identical output.
fn mock_script() -> Vec<AgentEvent> { /* the body already inline in default_registry() at registry.rs:372-412 */ }
```

**Data** — `smoke_registry()` registers exactly one `Slot::Ready(MockHarness)`
under `HarnessId::Mock`, `order = [Mock]`. `descriptors()` against this
registry does zero filesystem/process work: no `installed()` probes to run
(no lazy slots exist), so the `ListHarnesses` RPC handler
(`RpcReply::value(&self.registry.descriptors())` at `rpc.rs:879`) returns
in-process, synchronously, with no I/O.

### 2.2 `web_smoke.rs` wiring

Replace:

```rust
let core = EngineCore::assemble_with_profile(
    EngineProfile::local(dir.path()).unwrap(),
    Arc::new(HarnessRegistry::new()),
    roboco_engine::HarnessId::Mock,
)
```

with:

```rust
let core = EngineCore::assemble_with_profile(
    EngineProfile::local(dir.path()).unwrap(),
    Arc::new(roboco_engine::registry::smoke_registry()),
    roboco_engine::HarnessId::Mock,
)
```

(Export `smoke_registry` from wherever `HarnessRegistry`/`default_registry`
are already re-exported at the crate root, so the example doesn't need a
`registry::` path if the crate root doesn't expose one today — match whatever
`default_registry` currently does.)

## 3. Diagnosis — why `default_registry()` made `ListHarnesses` never answer

The checklist recorded this as "the likely cause but was not confirmed."
Reading the code confirms the mechanism and narrows it further than the
checklist's guess:

1. `LIST_HARNESSES => RpcReply::value(&self.registry.descriptors())`
   (`crates/engine/src/rpc.rs:879`) calls `descriptors()` **synchronously,
   inline, with no `.await` and no `spawn_blocking`** — whatever it does runs
   on whatever async-runtime worker thread is handling this RPC frame.
2. `descriptors()` (`registry.rs:337-359`) iterates every registered harness
   in order and, for each `Slot::Lazy` entry, calls its `installed()` probe
   **unconditionally, every single call** — this is explicit in the slot's
   own doc comment ("Re-run on every `descriptors()` call"). `default_registry()`
   registers eight such lazy slots.
3. Each ACP-style lazy probe (`cursor`/`devin`/`grok`/`hermes`/`pi`) and the
   native `ClaudeHarness`/`CodexHarness`/`OpencodeHarness` probes all bottom
   out in `find_on_paths`/`find_on_paths_with`
   (`crates/harness/src/executable.rs:81-334`), which unconditionally calls
   `crate::shell_env::login_shell_path()` for **every single probe, every
   single `descriptors()` call** — not just once at startup.
4. **On Unix, this is confirmed.** `login_shell_path()` (`shell_env.rs:33-42`)
   is a `OnceLock`: the *first* caller to reach it (racing against
   `shell_env::prewarm()`'s dedicated background thread, spawned
   unconditionally at the top of `default_registry()`) actually spawns the
   user's login shell (`-lic`, falling back to `-lc`) and blocks reading its
   `env` dump until an end marker appears, the shell exits, or a
   **5-second-per-attempt timeout** fires (`ATTEMPT_TIMEOUT =
   Duration::from_secs(5)`, up to two attempts per the flag-set fallback =
   up to ~10s worst case). Every *other* caller (every one of the eight lazy
   probes after the first, and every subsequent `descriptors()` call for the
   life of the process) blocks on the `OnceLock` until that first capture
   finishes. Where a machine's login shell is slow, or the very first
   `descriptors()` call races `prewarm()`'s background thread before it has
   populated the cache, the calling async-runtime worker thread blocks
   synchronously — inside a plain `std::process::Command`/`std::thread`
   wait, which tokio cannot preempt — for up to several seconds, repeated on
   every poll per the "re-run every call" rule. `web_smoke` runs a
   `#[tokio::main(flavor = "multi_thread", worker_threads = 2)]` runtime, so
   one thread parked this way is enough to starve the other
   connection-handling work sharing the runtime — consistent with
   "`ListHarnesses` stop answering entirely" rather than merely "slow."
   **This mechanism applies on Unix only**: `login_shell_path()` is a hard
   `None` under `#[cfg(not(unix))]` (`shell_env.rs:38-41`), so it cannot fire
   on Windows.
5. **On Windows, the cause is NOT confirmed.** The smoke run that actually
   latched (`run-web-smoke.bat`, `.scratch/smoke.log`) was on Windows, where
   step 4 is structurally impossible — so whatever blocked here is something
   else, still inside the same `descriptors()` → `installed()` →
   `find_on_paths`/`find_on_paths_with` chain, but downstream of the
   `login_shell_path()` call (which just returns `None` immediately and falls
   through). The next candidate, read but not yet instrumented or proven, is
   `resolve_executable()`'s PATH/PATHEXT walk
   (`crates/harness/src/executable.rs`, roughly lines 100-215):
   - `node_version_manager_bins_with` (`:102-197`) adds, on Windows only, a
     `FNM_MULTISHELL_PATH` directory (`:110`) plus an APPDATA/USERPROFILE
     "shell-less PATH backfill" of npm/pnpm/scoop/Volta/nvm/bun install
     locations (`:158-178`, the comment there literally reads "GUI-launched
     processes inherit the shell-less PATH: backfill the default global
     install locations"). None of this spawns a process, but every one of
     these extra directories — several of which resolve through
     `USERPROFILE`/`APPDATA`, i.e. a roaming profile — gets stat'd
     (`is_file()`, via `is_runnable_candidate`) for every PATHEXT variant
     (`.com`/`.exe`/`.bat`/`.cmd`) of every one of the eight harnesses' CLI
     names, on every single `descriptors()` call, with no caching and no
     timeout of any kind.
   - Unlike the Unix shell-spawn path, none of this has a bounded timeout.
     A plain `std::fs`-backed `is_file()` stat is normally sub-millisecond
     on local disk, but if `FNM_MULTISHELL_PATH` is stale (pointing at a
     removed directory) or, more plausibly in a managed/corporate
     environment, `USERPROFILE`/`APPDATA` resolve onto a **roaming or
     network-mapped profile**, a single stat against an unreachable/slow
     network path can block for many seconds with no upper bound — repeated
     across dozens of candidate paths × 8 harnesses × every `descriptors()`
     call, this could plausibly account for "never answers" more
     completely than the Unix case's bounded ~10s ceiling does. This is a
     plausible mechanism, not a confirmed one — no thread dump or tracing
     was captured to prove it fired in the actual hang; treat it as the
     leading candidate to instrument if the fix in §2.1 is ever bypassed
     and the hang needs to be root-caused directly instead of designed
     around.
6. Separately and additively, on any platform: even without any slowness,
   `default_registry()`
   makes `ListHarnesses`' answer **depend on which CLIs happen to be
   installed on whatever machine runs the fixture** — `claude`/`codex`/etc.
   found or not found changes `installed`/`enabled` on every descriptor,
   which is exactly the kind of host-dependent nondeterminism a fixture must
   not have, independent of the hang.

The fix (§2.1) removes the cause structurally rather than papering over the
symptom: a registry with zero lazy slots has nothing for `descriptors()` to
probe, so `ListHarnesses` is a pure in-memory read with no I/O, no
`shell_env` contention, and no host dependency, on every platform.

## 4. Gaps this ticket closes

| item | kind | desktop value | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| Fixture harness registry | WRONG BEHAVIOR | N/A (desktop is a real app, not a fixture) | `web_smoke.rs`'s `HarnessRegistry::new()` is empty → `ListHarnesses` returns `[]` → composer never has a harness to pick and stays disabled per `parity-checklist.md` "Fixture gap" | `smoke_registry()` (§2.1): one eager `MockHarness`, zero lazy slots |
| `ListHarnesses` reliability under `default_registry()` | WRONG BEHAVIOR | N/A | swapping in `default_registry()` left `ListHarnesses` "stuck loading forever" (`parity-checklist.md`) | diagnosed in §3; `smoke_registry()` sidesteps it by construction rather than fixing `shell_env`/`descriptors()` (those are real-desktop code paths, out of scope here) |

## 5. Do not

- Do not fix `PickerCatalog.loadHarnesses`'s latched-`loading`-on-failure bug
  (`parity-checklist.md` "Defect found while comparing"). It is real and
  pre-existing, but it is a symptom-hardening fix, not what makes this
  specific fixture work, and it has no `file:line` ticket assignment yet —
  raise it separately if it resurfaces after this ticket.
- Do not change `default_registry()`'s behavior for the real desktop/engine
  boot path. It is correct for a machine with real CLIs installed; this
  ticket only adds a parallel, smoke-specific constructor.
- Do not add new lazy slots, new mock scripts, or additional seeded chats to
  `web_smoke.rs` beyond what already exists (the single "Browser smoke chat"
  with a `cwd`). Keep the fixture minimal — later tickets seed whatever else
  they need to screenshot.
- Do not attempt to actually resolve/fix `shell_env`'s login-shell probe
  latency or the eight lazy harnesses' discovery cost. That is real-app
  behavior with its own tradeoffs (documented at length in `shell_env.rs`'s
  module doc) and is not part of the web-parity effort.

## 6. Acceptance

- [x] `crates/engine/src/registry.rs` exposes `smoke_registry()` and a shared
      `mock_script()` helper; `default_registry()` calls the same
      `mock_script()` (no duplicated script literal).
- [x] `crates/engine/examples/web_smoke.rs` constructs its `EngineCore` with
      `smoke_registry()` instead of `HarnessRegistry::new()`.
- [x] `cargo build -p roboco-engine --example web_smoke` succeeds; running it
      prints `SMOKE READY <url>` and keeps running.
- [x] Opening the printed URL in a browser (via the `use-browser` skill)
      shows "Browser smoke chat" in the sidebar, selected, with the composer
      textarea, attach button, and send button all enabled (not greyed out)
      within a few seconds of page load — no manual retry needed.
- [x] On Windows, `ListHarnesses` against `web_smoke` answers in under 1s,
      verified by a timestamped log line bracketing the request and the
      reply (e.g. a temporary `tracing::info!` before/after the
      `self.registry.descriptors()` call, or a client-side timestamp around
      the `loadHarnesses()` call) — this is the platform the original hang
      was observed on, so it is the one that must be positively verified,
      not just "presumed fixed by construction."
- [x] (n/a) If it still hangs on Windows after switching to `smoke_registry()`,
      do not guess further: capture a thread dump (or add temporary
      `tracing` spans around `descriptors()` and each lazy `installed()`
      call, if any lazy slot is still reachable) to find exactly where the
      call is parked, and record the actual confirmed cause in this
      ticket's Comments section before closing it.
- [x] Typing "hi" and sending it renders, in order: the user's own bubble
      ("hi"), then the mock reply's markdown heading ("Streaming pipeline"),
      its numbered list, a code-block with `rust` syntax highlighting
      containing `folded = fold_event_into_parts(...)`, and two tool-call
      rows for `cargo test --workspace` and the `git log`/`git merge-base`
      command.
- [x] Screenshot pair: N/A for this ticket (no desktop equivalent — this is
      infrastructure). Take one web screenshot of the rendered transcript
      described above and attach it to the PR/ticket as the "fixture now
      works" evidence; later tickets (18, 02, etc.) are the ones that
      screenshot-diff transcript styling against the desktop.
- [x] `pnpm -r build` green (no web files should need to change for this
      ticket; if `picker-catalog.ts`/`composer.tsx` needed edits to make the
      fixture work, something in the diagnosis was wrong — investigate
      before patching around it).

## Comments

### Implementation note — branch `wp1/01-smoke`

**What landed.** Rust only; no web file changed.

- `crates/engine/src/registry.rs` — the mock script literal that was inline in
  `default_registry()` is now `fn mock_script() -> Vec<AgentEvent>`; both
  builders call it, so there is one copy. New
  `pub fn smoke_registry() -> HarnessRegistry`: one eager
  `Slot::Ready(MockHarness)` under `HarnessId::Mock`, zero lazy slots, no
  `shell_env::prewarm()`. Two new unit tests:
  `smoke_registry_lists_only_the_mock_and_probes_nothing` and
  `mock_script_covers_every_transcript_shape_the_fixture_screenshots`.
- `crates/engine/src/lib.rs` — `smoke_registry` re-exported at the crate root
  next to `default_registry`.
- `crates/engine/examples/web_smoke.rs` — `HarnessRegistry::new()` →
  `smoke_registry()`. Nothing else in the example changed (still one seeded
  "Browser smoke chat" with a `cwd`).

**Verification.**

- `pnpm -r build` (typecheck + vite bundle) green; no web package touched, so
  no vitest run was needed.
- `cargo build -p roboco-engine --example web_smoke` succeeds; the two engine
  warnings in the output (`diff_sync.rs:38` unused import,
  `workspace_files.rs:1814` unused variable) are pre-existing and unrelated.
- `cargo test -p roboco-engine --lib registry` — 14 passed, 0 failed
  (12 pre-existing + the 2 added here).
- Running the example prints `SMOKE READY <url>` and stays up.

**§6 "answers in under 1s on Windows" — confirmed, not presumed.** A temporary
`eprintln!` bracket was added around `self.registry.descriptors()` at
`crates/engine/src/rpc.rs:879`, the fixture was run, and the browser drove a
real `ListHarnesses`:

```
TEMP-PROBE ListHarnesses enter
TEMP-PROBE ListHarnesses exit rows=1 elapsed_ms=0.141
```

0.141 ms, one row (Mock), on Windows 11. Reproduced twice across two fixture
runs (0.171 ms / 0.141 ms). The instrumentation was reverted before committing
— `rpc.rs` is untouched in this branch's diff. §3's step 5 (the unconfirmed
Windows `resolve_executable` PATH/PATHEXT-walk hypothesis) was therefore never
exercised and remains unconfirmed; with zero lazy slots there is nothing left
to instrument, so it stays the leading candidate if anyone ever re-introduces
`default_registry()` here.

**Acceptance walk (all via `use-browser` against the live fixture).** Open the
printed pairing URL → pair → sidebar shows "Browser smoke chat" → click it →
textarea, attach and send all enable within well under a second. Typed "hi",
sent, and the transcript rendered in order: the user's "hi" bubble, the
`## Streaming pipeline` heading, the 3-item numbered list, a "Ran 2 commands"
tool group holding `cargo test --workspace` and
`git log -5 --oneline --decorate && git merge-base HEAD origin/main`, and the
fenced `rust` block with `folded = fold_event_into_parts(...)` syntax-highlighted.

Screenshots (in the main checkout, not this worktree):
`.scratch/web-parity/shots/01/01-composer-enabled.png`,
`02-composer-send-enabled.png`, `03-transcript.png`,
`04-tool-rows-expanded.png`.

**Skipped, per §5.** No change to `default_registry()`'s behavior, no new lazy
slots / scripts / seeded chats, no `shell_env` work, no fix to
`PickerCatalog.loadHarnesses`.

### Findings for later tickets (not fixed here)

1. **Send is Mod+Enter on web, not Enter.** §6 says "typing 'hi' and pressing
   Enter"; `composer.tsx:385-390` deliberately makes plain Enter insert a
   newline and Mod+Enter submit, with a comment explaining why. The walk above
   used Ctrl+Enter. Ticket 13 (Composer core) owns whether that stays.

2. **`PickerCatalog.loadHarnesses`'s failure latch resurfaces — raise it
   separately, as §5 predicted.** It is worse than the checklist's
   "latched loading" framing. `EngineClient.call`
   (`web/packages/engine-client/src/client.ts:216-222`) *throws synchronously*
   with `RpcError("transport", "Engine is offline; reconnecting")` when the
   socket is not yet established — unlike `watch()` (`:233-247`), which queues
   and re-subscribes after connect. `ChatPage`'s
   `void session.catalog.loadHarnesses()` (`routes/chat-page.tsx:67-72`) fires
   as soon as `session` is non-null, which on a **cold full page load of
   `/chat/<id>`** is before the socket connects. The call is rejected before a
   frame is ever sent (verified: the engine's `ListHarnesses` probe logs
   nothing at all on that path), `loadHarnesses` catches into `listWithError`,
   and nothing retries — the composer stays disabled indefinitely while the
   transcript itself streams fine, because watches survive the same race and
   unary calls do not. Reproduced deterministically; observed disabled 250+
   seconds after load. **Workaround for fixture users: always enter through the
   printed pairing URL and reach the chat by clicking its sidebar row (the
   client-side route transition runs against an already-connected client).
   Do not hard-navigate or reload straight onto `/chat/<id>`.** The real fix is
   for `loadHarnesses` to retry on the client's connect transition, not for
   this ticket.

3. **Expanding a tool group hides every part after it in the same message.**
   With "Ran 2 commands" collapsed, the trailing markdown paragraph and the
   fenced `rust` block render; clicking to expand makes the two `Run` rows
   visible and simultaneously removes that trailing text and code block from
   the DOM; collapsing restores them. Fully reversible and reproducible. This
   is why `03-transcript.png` (collapsed, shows the code block) and
   `04-tool-rows-expanded.png` (expanded, shows both command rows) are two
   separate captures rather than one. Belongs to ticket 19 (Tool groups) /
   18 (Transcript rows).

4. **The user bubble wraps one character per line.** "hi" renders as "h" over
   "i" in a bubble roughly one character wide (visible in `03-transcript.png`).
   Cosmetic, pre-existing, and squarely ticket 18's (Transcript rows) —
   flagged here only so the next ticket's screenshot diff is not surprised
   by it.
