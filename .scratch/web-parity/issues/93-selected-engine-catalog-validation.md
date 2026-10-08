# 93 — Validate harness/model on selected engine

**What to build:** Port zeron `631a8e03` / `6e4f363`: before fresh send, harness/model/agent availability checked on **resolved target engine**, not only `useEngineSession()` connected state.

**Blocked by:** **86**, **90**

**Status:** ready-for-human

**Zeron ref:** commits on PR #526 after `4928e1b2`

**Acceptance:** Pick engine B + model only offered on B; friendly error if catalog missing on owner.

- [x] Pick engine B + a model only offered on B; friendly error if the catalog is missing on the owner (demonstrated at the unit/mounted seams: `tests/composer-availability.test.ts` "fresh composer model validation after an engine switch" — the same model id carried from engine A blocks (Enter + Send, zero durable RPCs on either engine) while B's catalog is pending/empty/errored, then sends with B's own ladder and option set once B's metadata lands ("high" reasoning, A-only context pick discarded); "shows missing-executable discovery as an Engine A failure, not a browser setup task" + `picker-catalog.test.ts`'s `modelDiscoveryErrorMessage` pin the friendly error that names the owner engine; `composer-reasoning.test.ts` "ComposerPickers availability states" pins the chip/card takeover states).

## Comments

### Implementer note (2026-10-09)

Ported zeron `631a8e03` + `6e4f363` on top of the integration head `4241fec4` (86–92 landed) — one commit on `webparity-93-selected-engine-catalog-validation`.

**`631a8e03` — the settled offered-on-the-selected-engine check:**
- `lib/composer-send.ts`: `selectedHarnessUnavailable`'s doc becomes zeron's final wording ("The engine reports the harness unavailable, or a fresh chat has not confirmed it yet").
- `components/composer.tsx`: `selectedHarnessUnavailable` gains the settled offered arm on top of wpn-90's fresh-send wait — the exact zeron `d57b27fd` two-arm form: `(newChat && (!loaded || error)) || (loaded && error === null && !offeredHarnesses(rows).some(id === draft.harness))`. The fresh draft initializer consults `defaultDraftHarness` (the sticky harness while offered, else offered[0], else sticky while the catalog is empty) instead of the `"claude-code"` fallback for the initial models lookup, and passes the sticky `{harness, model}` picks through a new `StickyDraftPicks` param on `defaultDraft`/`draftFromChat` — the fresh draft now seeds the remembered harness/model like the desktop's `effective_harness`/`effective_model_id` (pickers.rs:713-745), and stays consistent with the models list the initializer reads. The harness reconcile effect calls the extracted `reconcileFreshDraftHarness` (presence in the OFFERED set, not the raw rows: a present-but-disabled row no longer pins the draft); the model-load effect gates discovery on the offered check and drops `rows.length` for `rows` in deps. `ComposerPickers` receives `engineLabel={session.engine.label}`.
- `lib/composer-draft.ts`: `defaultDraft` resolves against the offered set only (a loaded catalog offering nothing deliberately seeds no model — the send gate reads that as unavailable), keeps the sticky harness while the catalog is empty, and takes the `StickyDraftPicks` param; `defaultDraftHarness` + `reconcileFreshDraftHarness` are 1:1 from zeron.
- `components/composer-pickers.tsx`: `engineLabel?: string` (default "this engine"), `harnessUnavailable` (loaded + no error + the effective harness not offered), `unavailableLabel` (`descriptor?.name ?? effectiveHarness`), the chip's `{label} unavailable` arm, the IdentityCard `model-no-agents` takeover ("… is unavailable / This chat is committed to that agent on this engine. Enable and install it on the engine before sending.") after the noAgents arm, and `modelSlotError = modelError` — the model error is contextualized through `modelDiscoveryErrorMessage`.
- `state/picker-catalog.ts`: `modelDiscoveryErrorMessage` (a `missing_executable` failure names the engine and the `CODEX_EXECUTABLE` override, and says the browser cannot do this), and per-harness model flight identities — `#modelsInFlight` becomes `Map<HarnessId, number>` with `#modelFlightStale`, so `resetModels` starts a new ownership epoch and a stale discovery reply is dropped instead of repainting the new target's catalog.

**`6e4f363` — fresh model metadata validation:**
- `lib/composer-send.ts`: `newChatNoAgents` is replaced by `selectedModelUnavailable` ("A fresh chat's selected model or metadata is not confirmed by its engine"); `sendBlocked` ORs it in.
- `components/composer.tsx`: `selectedModelUnavailable = newChat && (!models.loaded || models.error !== null || !models.rows.some(id === draft.model) || reconcileFreshDraftModel(draft, …) !== draft)` — a model carried from another engine waits for B's own catalog AND for the reasoning/options reconciliation to settle. Both `sendBlocked` sites drop the `newChatNoAgents` line (ze`Catalog loading preserves preference intent, not send permission.`), the submit deps become zeron's final set (dropping `newChat, harnesses.loaded, harnesses.rows`), and `useDraftModelReconciliation(models.rows, harnesses.rows, setDraft, newChat)`.
- `lib/composer-draft.ts`: `reconcileFreshDraftModel` (1:1) — the fresh reconcile also filters option picks the new engine's model does not offer.
- `lib/composer-reconciliation.ts`: the `fresh = false` flag selects `reconcileFreshDraftModel` for fresh drafts; established chats keep `reconcileDraftModel` (committed option picks retained).

**Deliberate adaptations (roboco ≠ zeron):**
- **CompactCard** — roboco's default model-picker presentation (upstream #471; zeron has no compact card) receives the same `harnessUnavailable`/`unavailableLabel`/`modelError` props, the same `model-no-agents` takeover arm, and the same `modelError` attribution. Without this the port's card-body states would be dead code behind a non-default setting; zeron's only card is the identity card.
- **The reasoning preference layer** — roboco's upstream-#471 per-model memory is preserved: the harness-swap path seeds `rememberedReasoningFor(harness, rememberedModelFor(harness)?.id)` (per-model, else global) instead of zeron's bare `defaults.reasoning`, and the fresh initializer's preference layer stays `rememberedReasoningFor`. `reconcileFreshDraftHarness` itself is 1:1; the call site wraps it with the per-harness reasoning fixup.
- **Per-engine canvas drafts (wpn-86)** — zeron's blocked-send assertion "typed draft text survives the engine switch" reads differently here: roboco keys canvas text per engine (`chatDraftKey("", baseUrl)`), so the switch swaps to B's own (empty) canvas draft and A's text is preserved under A's key. The ported blocked tests assert the round trip instead — after the blocked send attempts on B, switching back to A restores "Keep this unsent draft" verbatim — which proves the same invariant (a blocked send consumes nothing) without reopening ticket 86's design.
- **`durableCalls`** now includes `UPLOAD_CHUNK`/`UPLOAD_COMMIT` (zeron's shape), so the blocked-send no-op also proves no upload RPCs leave; the engine-switch suite stages a real PNG through the paste → stage → upload path.

**Tests (all red first where the behavior is new):** `composer-availability.test.ts` — the engine-switch suite (6 pending/empty/error × Enter/Send variants + the recovery test asserting B's reasoning "high" and the discarded A-only option pick in both the createChat config and the RunRequest, + the established-chat preservation while B's models are pending) plus a `TestEngine`/`makeEngine`/`switchTo` fixture extension and two mounted bite checks for the offered arm ("blocks an established chat whose committed harness is unoffered on this engine" and "a fresh chat heals an unoffered remembered harness to the offered one and sends on it" — zeron covers both at the unit level only). `composer-draft.test.ts` — `defaultDraftHarness`, `reconcileFreshDraftHarness`, `reconcileFreshDraftModel`, the sticky-seeding and offered-filter `defaultDraft` tests. `composer-reasoning.test.ts` — the "ComposerPickers availability states" describe (committed-unavailable chip + card, settled no-agents, engine-attributed discovery failure, the A→B→A no-error-carry) + the wiring pin updated to the 4-arg hook. `composer-send.test.ts` — `selectedModelUnavailable` in the `sendBlocked` block. `picker-catalog.test.ts` — the stale-model-flight drop and the per-engine discovery/retry destinations + `modelDiscoveryErrorMessage`.

**Mutation checks (the tests bite):** forcing `selectedModelUnavailable = false` fails the 6 engine-switch blocked tests; reverting `selectedHarnessUnavailable` to wpn-90's one-arm form fails the established-unoffered test; restoring the old rows-presence early return in the harness reconcile effect fails the fresh-heal test (and 5 engine-switch tests). The `reconcileFreshDraftModel(...) !== draft` arm of `selectedModelUnavailable` does not bite at the mounted level — the recovery test waits for the reconciliation to land before sending, exactly like zeron's; the arm is unit-covered (`reconcileFreshDraftModel`'s identity tests + the `sendBlocked` condition).

**Verification:** `pnpm -C web/packages/app exec tsc --noEmit` clean; `pnpm run build` (tsc + vite) clean; focused suites `composer-availability` 15/15, `composer-draft` 49/49, `composer-send` 29/29, `composer-reasoning` 46/46, `picker-catalog` 20/20; adjacent ring (new-chat-selection, new-chat-target, composer-edit-failure, browser-engine-picker, new-thread-git-selectors, composer-footer-git, chat-arrival, composer-flip, composer-dock, add-space, settings-fleet-routing, wizard, pending-send, composer-actions, queue-actions, settings-engine-indicator, devices, sidebar-row-send-truth, catalog-loading, model-rows, traits-summary, picker-card-phone, picker-search, nested-menu, side-chat-actions) — 28 files, 501/501 in the combined batch. Full app suite deferred to the orchestrator per instructions.

**Residual risks:**
- The `selectedModelUnavailable` model gate blocks a fresh chat whose selected harness's model list is loaded but EMPTY (no row matches `draft.model`) — correct per zeron, but it means an engine that offers a harness with zero models keeps the canvas send-disabled until the user picks another harness. Zeron has the same semantics.
- The established-chat-with-unoffered-committed-harness send block is a visible behavior change on top of wpn-90 (previously only fresh chats were gated): a chat created on engine A for a harness A offered and B later disables is now unsendable on B until the harness is re-enabled there. That is zeron's exact intent ("Established chats keep their committed config and are blocked only by a settled unavailable result") and the pickers surface the state explicitly.
- `selectedModelUnavailable`'s `reconcileFreshDraftModel !== draft` arm can keep the button disabled for one render tick after a catalog lands (until the reconciliation effect settles); the window is the point of the arm (never enable before the draft settles) and is invisible in practice.
- The engine-switch suite mounts the identity-card arm (compact off) because zeron's tests drive that card; the compact arm's new takeover states are covered only by the shared chip label + the takeover arm being the same JSX — a compact-specific mounted test is a possible follow-up for the review round.

## Review round (34629422 → reviewed fixup)

Two-axis review per the code-review skill (its sub-agent machinery — no
Agent tool in this session — so both axes ran in the same context against
`git diff 4241fec4...HEAD`, like wpn-90's round).

**Standards findings (both fixed):**
- Duplicated Code — the `modelError={modelsList.error === null ? null :
  modelDiscoveryErrorMessage(...)}` prop expression was duplicated verbatim
  at the CompactCard and IdentityCard render sites (zeron computes it once;
  the Compact adaptation introduced the copy). Hoisted to one
  `const modelError` in `ComposerPickers` ("Both cards read this one value"),
  with the per-card `modelSlotError`/`modelsListError` aliases keeping
  zeron's names.
- Organization — `StickyDraftPicks` sat between `defaultDraft` and
  `defaultDraftHarness`, referencing `RememberedModel` defined ~100 lines
  below. Moved into the sticky-picks type group (`RememberedModel` →
  `StickyDraftPicks` → `ModelFavorite`), restoring zeron's layout:
  `defaultDraft` → `defaultDraftHarness` → `reconcileFreshDraftHarness` →
  `draftFromChat`.
- Accepted judgement calls: the per-card takeover-arm JSX mirrors between
  the two cards exactly as the file's existing `noAgents` arms do (the
  documented-in-code per-card idiom overrides the duplication baseline);
  the IdentityCard's now-unread `modelsList` local stays because zeron's
  final file keeps the identical line; the two self-contained describe
  fixtures (`committedCodex`) follow the file's per-describe idiom.
- No web-parity convention breaches: no CSS touched (the takeover reuses
  the existing `model-no-agents` classes — no literal hex), engine/harness
  vocabulary holds, commit style and zeron-mirroring test names hold.

**Spec findings:** every hunk of `631a8e03` and `6e4f363` is present and
faithful — `composer-send.ts`/`composer-reconciliation.ts` are semantically
identical to zeron's final state (doc-comment wording only);
`defaultDraft`/`defaultDraftHarness`/`reconcileFreshDraftHarness`/
`reconcileFreshDraftModel` bodies are byte-identical; `modelDiscoveryErrorMessage`
and `#modelFlightStale` identical; the composer's two-arm
`selectedHarnessUnavailable`, `selectedModelUnavailable`, both `sendBlocked`
sites + deps, the reconcile/model-load effects, the 4-arg hook, and the
`engineLabel` wiring all match. No missing requirements; no scope creep
beyond the four documented adaptations plus test-only additions.

**The residual Compact-arm gap — CLOSED:** a new
`ComposerPickers compact availability states (wpn-93)` describe in
`composer-reasoning.test.ts` (the compact card is the app's DEFAULT
presentation; zeron has no compact arm):
- "takes over the compact card for a committed harness the engine does not
  offer" — the `.compact-card` body renders "Codex is unavailable" + the
  committed-agent copy, no draft/persist commits.
- "attributes a failed model discovery to the selected engine on the models
  page" — ArrowDown into the models page shows "Model discovery on Engine A
  failed" + the `CODEX_EXECUTABLE` hint.
Both bite: removing the compact card's `harnessUnavailable` arm fails the
takeover test; reverting `modelsListError` to the raw `modelsList?.error`
fails the attribution test. The composer-level mutations still bite after
the fixes (re-verified: forcing `selectedModelUnavailable = false` fails
the 6 engine-switch blocked tests).

**Re-verification:** acceptance criterion re-demonstrated (unchanged);
`tsc --noEmit` clean; `pnpm run build` (tsc + vite) clean; focused suites —
composer-availability 15/15, composer-draft 49/49, composer-send 29/29,
composer-reasoning 48/48 (+2 compact), picker-catalog 20/20; combined batch
with the adjacent ring — 30 files, 533/533. Full app suite still deferred
to the orchestrator. Status → ready-for-human.
