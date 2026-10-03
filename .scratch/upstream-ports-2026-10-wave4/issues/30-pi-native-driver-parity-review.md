# 30 — Review: upstream's native Pi RPC driver vs ours (parity + adoptables)

**What to build:** NOT a port — our native Pi driver shipped roboco-first
(`1ef237d6` wire/normalize/lifecycle, `86cb3339` catalog/set_model/title,
`70e8e8d0` MCP extension bridge) and ticket 21 recorded "when they write
it, their MCP wiring will port cleanly onto our native PiHarness."
Upstream #630 has now landed the same architecture (native RPC mode, per-
run mjs MCP delegation). Produce a comparison decision record: read
upstream's `crates/harness/src/pi/*` (mod/catalog/normalize/rpc/sessions/
ui/mcp) and its engine-side changes, diff the behavior surface against
ours, and adopt only what we lack. Known candidates from the commit
message:

1. **120s startup budget for cold extensions** — ours defaults to 60s
   (`with_startup_timeout` exists; check what callers pass; extensions
   can start cold).
2. **Dropped input-resolver semantics** — upstream's engine
   input-cancellation task turned a dropped resolver into an empty
   answer; they keep it an error for harnesses (opencode rejects on
   error). Check our `respond_input`/resolver drain paths (sessions.rs
   ~789, ~2637) for the same trap.
3. **`pi/PROTOCOL.md`** — port as an internal reference doc (rebranded)
   if ours lacks an equivalent digest.
4. Anything else materially better in their sessions.rs (resume pointer
   handling, steering bursts in the same model step, nested-agent env
   scrub on the native path — ours scrubs in `acp/child.rs` for ACP
   children; verify the pi native spawn path also removes
   `CLAUDECODE`-style markers).

**Blocked by:** None. (Run first — read-only, informs nothing else.)

**Status:** ready-for-agent

**Upstream SHAs:** `df0cd298` (#630) — reference only. **Do not** port the
commit, its `acp/child.rs` → `process/owned.rs` move, the ACP retirement,
or its CI/iOS files. Any adoptable fix lands as a small separate commit
citing the specific sub-fix, with `Status` here flipped to resolved and
the decision recorded below.

**Verification budget:** reading + `git show`; any adoptable patch gets
its own targeted test run (pi harness tests). No structural convergence,
no driver rewrite — ours is the shipped one.

- [x] Decision record written (adopted / already-covered / rejected per
      candidate)
- [x] Any adopted fix lands separately with its own tests (satisfied by
      reference: the two adoptable fixes landed as engine-lane commits
      37c485cb / 5aec52ef, test runs recorded in those commits; this review
      found no further adoptable production behavior)
- [x] Ticket resolved with findings under `## Comments`

## Comments

**Decision record — upstream `df0cd298` (#630) vs our roboco-first native
Pi driver.** Read-only review, no production code, per the spec's
Implementation Decisions ("#630 is a review, not a port") and the standing
user decisions recorded in the engine-lane ports. Ours stays the shipped
driver; no structural convergence, no driver rewrite.

**Adopted — already on main (engine lane, this window):**

1. **120s cold-extension startup budget** — landed as `37c485cb` ("Harness:
   pi startup budget 60s→120s"). `PiHarness::default`'s `startup_timeout`
   is `Duration::from_secs(120)`
   (`crates/harness/src/pi/mod.rs`); `with_startup_timeout` (tests tune it)
   unchanged; the timeout error path already reports the configured budget.
   Verified in the tree.
2. **Dropped input-resolver → error for harnesses** — landed as `5aec52ef`.
   The input bridge parks the resolver for `respond_input` and a small
   forwarder relays only actual answers
   (`crates/engine/src/sessions.rs`, the split-channel construction): a
   dropped resolver forwards nothing, so the harness sees an error, never an
   empty answer posing as one; deliberate empty answers (run-end drain,
   post-turn auto-decline, interrupt) keep flowing. Verified in the tree.
3. (Adjacent, same window) **Send-next / TurnBoundary steering completion**
   — `977fac61` (upstream 731697b6 #498, engine half): queue-row promotion
   at turn end covers exactly our TurnBoundary harnesses. Our pi steering
   story is complete without #630's machinery.

**Consciously not adopted (user decisions already on record):**

4. **`pi/PROTOCOL.md`** — excluded by the user decision recorded in both
   `37c485cb` and `5aec52ef` ("Deliberately excluded, user decision, on
   record: the #630 pi/PROTOCOL.md doc port and the legacy ACP
   session-resume shim"). Ours is not doc-less: the
   `crates/harness/src/pi/mod.rs` module header IS the equivalent digest —
   framing, session pointers, prompting/steering, turn settlement, stats,
   extension UI, models, titles, MCP bridge, interrupt — distilled against
   `@earendil-works/pi-coding-agent` 0.87.0 with pointers to
   pi.dev/docs/latest/rpc, pi-mono's `docs/rpc.md`, and the reference client.
   Upstream's PROTOCOL.md additionally codifies their ACK-barrier/
   `isStreaming` mechanics and steering FIFO delivery records — contracts
   specific to their `prompt` + `streamingBehavior:"steer"` design that our
   `steer`-queue driver does not implement. Decision stands; no port.
5. **Legacy ACP session-resume shim** (`~/.pi/pi-acp/session-map.json` +
   upstream's host-side UUID→file Store) — excluded on record. Ours needs no
   map: the engine's durable pointer IS the session file path from
   `get_state.sessionFile` (a bare id can hit pi's global session search and
   prompt on stdin — the protocol channel), and a pointer that is not an
   existing file degrades to a fresh session with a visible notice — the
   same outcome upstream's "start fresh with a notice when unrestorable"
   fix produces (`mod.rs` spawn-side filter + lost-session notice).

**Already covered in our driver (no action):**

6. **Nested-agent env scrub** — `child::configure`
   (`crates/harness/src/acp/child.rs`: removes `CLAUDECODE`,
   `CLAUDE_CODE_ENTRYPOINT`, `CLAUDE_CODE_SSE_PORT`,
   `CLAUDE_AGENT_SDK_VERSION`, private process group on Unix) is called on
   BOTH native pi spawn paths: the run spawn and the model probe
   (`discover_models`). Upstream needed the fix because their native launch
   skipped `owned::configure`; ours never did.
7. **Attachments best-effort** — `load_image_contents` stages inline images
   best-effort: unreadable, over-cap, or non-inline-supported types (SVG,
   HEIC, BMP, TIFF, AVIF) degrade to "path ref only" warnings and the path
   refs already ride the prompt text. Upstream's fix corrected their
   driver's whole-run hard error; ours shipped best-effort from the start.
8. **`set_steering_mode` overwrite guard** — NOT APPLICABLE: our driver
   never calls `set_steering_mode` (zero call sites in the crate) and never
   writes pi's global settings.json, so upstream's "never overwrite a
   configured Pi steering mode" has nothing to guard here.
9. **Steering bursts in the same model step** — NOT APPLICABLE: ours steers
   through pi's own `steer` queue (`SteeringMode::TurnBoundary`); delivery
   batching is pi's, and the driver only counts pending deliveries and
   recovers stranded steers via `clear_queue` at settle. Upstream's FIFO
   delivery records exist to make their `prompt`-with-steer path batch
   correctly.
10. **Engine-side hunks** — ours already registers pi over the NATIVE RPC
    mode (`crates/engine/src/registry.rs`), and the input-bridge hunk is
    item 2 above. The ACP retirement / `acp/child.rs` → `process/owned.rs`
    move stays unported by ticket direction (our `acp/child.rs` still serves
    the remaining ACP harnesses).

**Findings — genuinely missing behaviors, recorded, deliberately NOT
landed here** (ticket 30 is read-only; follow-up candidates, not
convergence):

11. **Question-panel wizard clobbers composer drafts** (upstream "preserve
    switched sessions empty resumes and composer drafts", the
    `crates/ui/src/composer.rs` hunks of #630). Two real bugs remain in our
    composer: (a) navigating chats while a wizard is open saves the wizard's
    typed answer as the old chat's draft — ours stores the input text
    unconditionally on the draft swap; upstream guards the swap with
    `if self.wizard.is_none()`; (b) releasing the wizard leaves the typed
    answer in the shared input as the new chat draft — ours only resets the
    placeholder; upstream restores the saved draft via `set_text`. Not
    landed: `composer.rs` is the ui lane's file domain this wave (ticket
    12's question-panel work touches the same region). Recommend a small
    follow-up ticket carrying the two guards.
12. **Wizard prefill/multiline** — upstream #630 also prefills the wizard
    from `UserInputQuestion.prefill` and keeps multiline answers verbatim.
    Our `roboco-proto` `UserInputQuestion` carries neither field (noted in
    `5aec52ef` when adapting the fixture change), so those hunks have
    nothing to land against without a proto wire change. Not adopted;
    wave-5 candidate at most.
13. **Session pointer not refreshed mid-run** — upstream refreshes session
    identity from every accepted state barrier, including extension-driven
    new/switch/fork operations. Ours captures the pointer once from the
    startup `get_state` and reports it in every Done; the settle sequence
    re-queries only `clear_queue`/`get_session_stats`. An extension-driven
    session switch/fork mid-run would leave the durable pointer on the
    pre-switch file — the next resume continues the old branch (a silent
    context fork, not a failure). Narrow; recommend a follow-up refreshing
    the pointer from a settle-time `get_state` before each Done.

Verification: reading + `git show` only (ticket 30's budget); no code
changed, no cargo. Test execution deferred to the wave-final batched pass
(user directive) — nothing here to execute.
