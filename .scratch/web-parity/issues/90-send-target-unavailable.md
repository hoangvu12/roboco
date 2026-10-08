# 90 — Block send when canvas target unresolved

**What to build:** Port zeron `f180fcb1` / `d57b27fd`: `targetUnavailable` from resolved target + fleet snapshot; composer/chat-page refuse send when selected project not loaded on owner engine.

**Blocked by:** **86**, **88**

**Status:** ready-for-agent

**Zeron ref:** `composer.tsx`, `new-thread-selectors.tsx`, `tests/new-chat-selection.test.ts`

**Acceptance:** Cannot send on canvas with cross-engine project row missing from owner cache.

- [x] Cannot send on a canvas with a cross-engine project row missing from the owner cache (demonstrated at the unit seam: `tests/composer-availability.test.ts` "blocks an unresolved project without losing the draft and resumes after resolution" — the remembered project is engine B's, the merged snapshot holds only engine A's rows, the routed session is engine B's; Send is disabled, Enter and the button leave ZERO durable RPCs (no `Mutate createChat`, no `QueueCommand`), and the draft survives; the same draft sends once the row lands).

## Comments

### Implementer note (2026-10-08)

Ported zeron `f180fcb1` + `d57b27fd` on top of the integration head `70dd5528` (86, 87, 88, 89, 91, 92 landed) — one commit on `webparity-90-send-target-unavailable`.

**`f180fcb1` — `targetUnavailable` (the send gate):**
- `components/composer/new-thread-selectors.tsx`: `useNewThreadTarget` now derives `targetUnavailable` (`projectId !== null && space === null`) plus the `projectUnavailable` (a loaded project list without the row — "gone") / `projectLoading` (anything else) split, exactly zeron's computation over the merged fleet snapshot (`spaces.loaded` joins the memo deps). The `NewThreadTarget` doc comments and field order follow zeron's final state (the `projectId` retention note merged with roboco's existing terminal-canvas-key rationale). `NewThreadTargetSelectors`'s `ProjectChip` keeps the selected identity (`currentSpaceId = target.space?.id ?? target.projectId`) and labels the fallback "Selected project unavailable" / "Selected project loading" / "No project".
- `routes/chat-page.tsx`: the canvas target resolves BEFORE the catalog-load effect (a remembered project without a live row must never discover models on a substitute target — the effect now gates on `targetUnavailable`); the stub chat keeps `spaceId: target.space?.id ?? target.projectId ?? null` so the createChat target can never fall through to the device id; the bottom stack renders the zeron warning strip (`composer-target-warning`, "Selected project unavailable…" / "Selected project loading…") above the persistent composer; `Composer` receives `targetUnavailable = !hasSelection && target.targetUnavailable`.
- `routes/chat-page.tsx` guard: a canvas with no routed session renders zeron's "Engine unavailable. Sending is disabled until the host connects." empty state even when another engine's cached chat rows keep `chats.loaded` true. Adapted, not copied: zeron folds this arm into its pre-existing "Engine unavailable" guard (whose other arms — `!chats.loaded && session === null`, offline row — never landed in roboco, which gates connection failure in `root-layout`'s `GateCard` instead); roboco gets ONLY the `f180fcb1` arm (`!hasSelection && session === null`), placed before the existing early returns, with the link pointing at `/pair` (roboco's pairing route; zeron links `/connect`). An established chat keeps its existing not-found state.
- `components/composer.tsx`: `targetUnavailable?: boolean` prop (default false — side-chat and settings hosts are untouched) folds into `requestTargetDisconnected` at both `sendBlocked` sites (submit callback and the send-button `blocked` computation), so the desktop's "blocked send is a no-op — no failure, no wire call" rule holds.

**`d57b27fd` — fresh sends wait for the selected engine's agent catalog:**
- `lib/composer-send.ts`: `SendBlockedConditions` gains `selectedHarnessUnavailable` (docs match zeron's final wording) and `sendBlocked` ORs it in.
- `components/composer.tsx`: `selectedHarnessUnavailable = newChat && (!harnesses.loaded || harnesses.error !== null)` — a fresh chat blocks while the selected engine's harness discovery is loading or errored; established chats are untouched (they keep their committed config). The submit callback's deps also gained `newChat, harnesses.loaded, harnesses.rows` (zeron's deps — the base's omission left a stale closure that never re-evaluated the gates after the catalog landed).

**Deliberately not ported (ticket 93's scope, `631a8e03`):** the second arm of zeron's `selectedHarnessUnavailable` (the settled offered-on-the-selected-engine check — `offeredHarnesses(...).some(id === draft.harness)` on a LOADED catalog), `newChatNoAgents`'s `offeredHarnesses(...).length === 0` form, and `reconcileFreshDraftHarness`. Zeron's `d57b27fd` modifies an expression 631a8e03 introduced; roboco carries neither, so the port lands only d57b27fd's own delta (the fresh-send wait). Ticket 93 is blocked by 90 and will land the offered-check on top. Likewise `composer-target-warning` has no CSS in zeron either — semantic marker only, matched 1:1.

**Tests:** `pnpm -C web/packages/app exec vitest run tests/new-chat-selection.test.ts tests/composer-availability.test.ts` — 6/6 + 5/5. Both files were red first (5/6 selector/page assertions failing at base; the composer-gate suite verified by mutation: removing `targetUnavailable ||` fails the target-gate test, forcing `selectedHarnessUnavailable = false` fails the three catalog-wait tests). `tests/composer-send.test.ts`'s `sendBlocked` block extended for the new condition (29/29). Typecheck `tsc --noEmit` clean; `pnpm run build` (tsc + vite) clean. Adjacent suites re-run green: browser-engine-picker (11), composer-edit-failure (3), composer-flip/dock/draft/reasoning/footer-git/actions (106+), new-chat-target (5), new-thread-git-selectors (5), add-space, settings-fleet-routing, wizard, pending-send, queue-actions, chat-arrival (source-scan), devices, settings-engine-indicator, sidebar-row-send-truth — 396 tests across the touched surfaces. Full app suite deferred to the orchestrator per instructions.

**Residual risks:**
- The page-level catalog gate is belt-and-suspenders, exactly as in zeron: the composer's own pickers row still kicks `catalog.loadHarnesses()` on mount (same catalog object), so discovery RPCs can still leave while the target is unresolved — the enforced invariants are the send gate and the stub identity. Matches zeron 1:1; if 93 wants the page gate to be authoritative it will need a composer-side gate too.
- The `!hasSelection && session === null` arm now shows "Engine unavailable…" + "Pair an engine" where an unpaired browser previously saw the hero canvas. This is zeron's behavior (its guard fires for the unpaired canvas too), but it is a visible change to the first-run surface — flag for the review round.
- `projectUnavailable` keys on the MERGED `spaces.loaded` (`some(engine.loaded)`, ticket 87's merge): a cross-engine project whose owner has not loaded while another engine has reads as "unavailable" rather than "loading" — the label may be pessimistic, but the send is blocked either way (zeron has the same merged semantics).
