# 01 — Diff empty states + checkout-folder normalization

**What to build:** A web client user opening the Changes pane on a chat whose diff never resolves
sees the truth instead of an eternal "Preparing diff…" spinner: whether the chat's checkout isn't
a git repository (or has no checkout folder), the chat's diffs live on its own device (each
engine tracks only its own device's chats), or the diff watch genuinely hasn't delivered yet.
Matching a diff frame to a chat also stops failing on Windows verbatim path prefixes, so the
fallback folder match works when a chat row lacks a checkout id.

**Blocked by:** None — can start immediately.

**Status:** done

- [x] A chat with a non-git checkout folder (or no checkout folder) shows an explicit message
      naming that condition, not the preparing spinner.
- [x] A chat hosted on another device shows an explicit "diffs live on its own device" message.
- [x] A watch that hasn't delivered its first item yet still shows the loading state.
- [x] Checkout-folder matching normalizes Windows verbatim (`\\?\`) prefixes; the fallback match
      succeeds when the chat row lacks a checkout id — covered by a regression test that fails
      before the fix.
- [x] The existing watch-error banner and scoped-error notices keep working unchanged.
- [x] The availability classification is a pure function, unit-tested; the diff store's behavior
      against a scripted fake caller follows the engine-client fake-server pattern.
- [x] The existing Changes surface render smoke keeps passing.
- [x] The browser smoke harness's no-checkout chat now renders the legible empty state.

## Comments

### What landed

- **Pure classification** (`lib/diff.ts`): `classifyDiffEmpty(inputs) → DiffEmptyKind`
  (`loading | noCheckoutFolder | remoteDevice | notAGitRepository`) plus
  `diffEmptyMessage(kind)` for the user-visible copy. Ordering: a remote-hosted
  chat wins over everything (the engine only tracks its own device's chats —
  `diff_sync.rs:345`), then a cwd-less/blank cwd, then undelivered watch or a
  stamped `checkoutId` (both stay loading — a stamped id proves the engine
  resolved a git identity, so an outstanding capture is a genuine load), and
  only a delivered watch plus a never-stamped checkout id concludes "isn't a
  git repository".
- **Rendering** (`routes/changes-page.tsx`): `ChangesBody`'s `preparing` arm
  now renders the classified message (`<p class="changes-empty" role="status">`)
  and keeps the `Preparing diff…` spinner only for the loading arm. No CSS
  changes — the existing token-based `.changes-empty` covers the new states.
  Watch-error banner, scoped-error notices, the clean phase, and the scope
  banner are untouched. `ChangesBody` is now exported so the mounted suite can
  drive the real store/watch wiring (the surface chrome stays unchanged).
- **Scoped-device comparison** (verified per the ticket's note): the merged
  fleet rows carry `scopeChat`-scoped `deviceId`/`checkoutId` while diff frames
  carry raw ids — `ChangesBody` scopes the routed engine's raw device id with
  `encodeScopedId(session.engine.baseUrl, …)` before comparing (the composer
  footer's idiom), and the classification uses `checkoutId` purely as a
  boolean git-proof signal, so the scoping never affects it.
- **`resolveDiff` normalization** (`lib/diff.ts`): the cwd fallback branches
  compare through the new `normalizeCheckoutPath` — strips the Windows
  verbatim `\\?\` prefix (UNC form `\\?\UNC\server\share` folds to
  `\\server\share`) and unifies separators to `/`, so a frame cwd
  `\\?\C:\Users\x\repo` matches a chat cwd `C:/Users/x/repo`. The
  regression test was written first and verified failing pre-fix
  (`resolveDiff → expected undefined to be 'co-1'`).
- `state/changes-store.ts`: exported the already-documented `WATCH_RETRY_MS`
  (the code map listed it as exported; it wasn't) so the store suite can pin
  the retry pacing. No behavioral store changes — `watchLoaded` and
  `resolvedForChat` already carried everything the classification reads.

### Tests added

- `tests/diff.test.ts`: the `\\?\ regression (fails pre-fix), a
  `normalizeCheckoutPath` suite, and a `classifyDiffEmpty`/`diffEmptyMessage`
  suite covering every arm's ordering and the exact copy (26 → 34 tests).
- `tests/changes-store.test.ts` (new): the store against a scripted in-memory
  caller (the app-level file-tree idiom, per the conventions' allowance):
  undelivered → watchLoaded flip on the engine's initial enumeration →
  verbatim-cwd resolution for a checkout-id-less chat → clean phase →
  single-frame upsert → stream-end banner with content staying → resubscribe
  reset → retry-pace pin.
- `tests/changes-empty-states.test.ts` (new, jsdom): the real `ChangesBody`
  mounted through the account-row double pattern (fleet/session mocked, the
  `ChangesStore` and its scripted fake watch real): all four states render —
  the smoke harness's plain-tempdir chat gets "isn't a git repository", a
  remote-device chat gets "diffs live on its own device", a cwd-less chat
  gets "no checkout folder", and the spinner survives only for genuine
  loading (undelivered watch or a tracked-but-uncaptured checkout) — plus a
  late-frame delivery re-rendering loading → answer, and a verbatim-cwd frame
  resolving into the diff banner.

### Verification

- `pnpm -r build` from `web/` — all 5 workspace projects build (tsc + vite).
- `pnpm --filter @roboco/app run test` — 124 files / 1907 tests, all green
  (baseline 122/1885; +22 tests across the three suites, none removed).
- One unrelated flake was observed once under full-suite load:
  `tests/registry.test.ts > reconnectBackoffDoublesAndResetsAfterALongLivedConnection`
  (a wall-clock-timing test importing none of this ticket's modules; passes
  in isolation and with these changes stashed). Six of seven full runs were
  clean; pre-existing, not introduced here.

### Skipped / flagged for the orchestrator

- **`resolveDiff`'s checkoutId branch is dead in the merged-fleet app**: chat
  rows carry scoped checkout ids while frames carry raw ids, so the
  checkoutId-first match never fires in production — matching relies on the
  (now normalized) cwd fallback. This ticket scoped the fix to the fallback
  normalization, so the scoping mismatch was NOT changed; the classification
  is immune (it reads `checkoutId` as a boolean). A follow-up could decode the
  scoped id at the store boundary (or scope frame ids) to revive the
  checkoutId match — flagged, not fixed.
- **The browser smoke harness acceptance** is proven by the mounted test's
  exact smoke fixture (same-device chat, plain-tempdir cwd, `[]` initial
  frame) rather than a browser render: the harness (`web-smoke.test.ts`) is
  node-driven with no rendering, so a human opening the smoke URL now sees
  the legible state — that behavior is what the mounted suite pins.
- Adjacent, untouched: for a non-git chat on the `branch` scope the base
  picker still reads "Loading branches…" (ListBranches fails silently) — the
  empty state replaces the spinner, the picker's cosmetic wording is a
  separate gap.
