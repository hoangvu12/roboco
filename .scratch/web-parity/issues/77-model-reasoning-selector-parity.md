# 77 — Show and retain model reasoning choices using the desktop fallback

**What to build:** The web model picker shows the existing Reasoning section whenever the selected model's effective desktop ladder offers levels. Choosing a level keeps it selected through draft normalization and catalog refresh, and sends that same level in the chat configuration and run request. Models with no effective reasoning choices keep their existing options-only or no-tray presentation.

**Blocked by:** None — can start immediately with ready catalog fixtures. Ticket 67 owns catalog liveness and may be needed to verify a first-pairing runtime case; it is not a semantic dependency for this fix.

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/followup-reasoning-selector.md` §§1–5. Required contracts and gap tables are copied below.

**Baseline:** `web-parity/wave-2` at `37c354ff`, 2026-09-20. Recheck symbols if later changes shift lines.

**Desktop reference (for lookups only):** `crates/ui/src/pickers.rs` default_reasoning (172–181), clamp_reasoning (187–194), effective_reasoning (762–775), pick_reasoning (1421–1429), persisted-config normalization (1491–1512), trait_ladder (1547–1561), pinned tray (3432–3451), render_traits (3666 onward).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/lib/traits-summary.ts` | edit | Shared effective-ladder resolver beside defaultReasoning/clampReasoning; retain existing label and model-option semantics |
| `web/packages/app/src/lib/composer-draft.ts` | edit | Descriptor-aware reasoning normalization in applyDraftUpdate; initial/default/remembered reasoning plumbing as needed for the explicit preference contract |
| `web/packages/app/src/components/composer-pickers.tsx` | edit | ComposerPickers ladder, commit/pickReasoning, effective summary and TraitsTray selected row |
| `web/packages/app/src/components/composer.tsx` | edit | Draft initialization and model reconciliation at 453–455,581–652; model-only ladderFor at 3091. Own only reasoning state here |
| `web/packages/app/src/lib/composer-actions.ts` | read; edit only if effective-value plumbing requires it | buildChatConfig and buildRunRequest; preserve existing wire schema and send/persist separation |
| `web/packages/app/tests/traits-summary.test.ts` | edit | Effective-ladder precedence and native default/clamp regressions |
| `web/packages/app/tests/composer-draft.test.ts` | edit | applyDraftUpdate fallback selection retention, initial/remembered preference and harness/model changes |
| `web/packages/app/tests/composer-actions.test.ts` | edit | Assert the selected effective level reaches ChatConfig and RunRequest |
| `web/packages/app/tests/composer-reasoning.test.ts` | new | Mounted picker and composer reconciliation regression with controllable catalog/descriptor updates |
| `web/packages/app/package.json`, `web/pnpm-lock.yaml` | edit only if needed | Reuse ticket 67/69 jsdom dependency or add app dev dependency via package manager |
| `web/packages/app/src/styles/app.css` | read; no edit expected | Existing .model-traits geometry; source does not establish a clipping defect |
| `web/packages/app/vitest.config.ts` | read; no edit expected | Keep node default and tests/*.test.ts discovery |

## 1. Context a fresh session needs

- User report: “the model picker dont seem to have reasoning selector?” Exact model, harness and phone/desktop viewport were not supplied at authoring. This ticket separates verified source conditions from the unverified runtime report.
- The selector already exists: TraitsTray renders Reasoning and its rows beneath the model list at composer-pickers.tsx:918–934.
- ComposerPickers obtains models from the effective harness catalog and a descriptor from railDescriptors (:124–127). selectedModel is the matching draft model or the first loaded row (:131–133).
- Web currently uses `selectedModel?.reasoningLevels ?? descriptor?.reasoningLevels ?? []` (:135). An empty array satisfies this expression, suppressing the descriptor fallback. Rust explicitly falls back when model levels are empty.
- showTraits (:628) and the Reasoning section (:920) consume this ladder. An empty model list plus nonempty harness list therefore hides reasoning, even though native shows it.
- pickReasoning remembers the level (:185–187), then commit calls applyDraftUpdate (:149–157). That helper clamps against model levels alone (composer-draft.ts:445–449), so merely showing the missing rows still loses the chosen fallback level.
- Composer initialization/reload also uses model-only levels (composer.tsx:632,649,3091–3092); the effect currently depends only on models.rows (:652). A later descriptor update needs to participate in reconciliation.
- buildChatConfig and buildRunRequest copy draft.reasoning (composer-actions.ts:42–48,63–75). There is no later transport repair.
- This is a real catalog shape: Claude descriptor levels exist (crates/engine/src/registry.rs:447–452), while Haiku has an empty model reasoning list plus a thinking option (crates/harness/src/claude/catalog.rs:144–148). OpenCode descriptor levels exist (registry.rs:592–597), while models without recognized variants can have [] (crates/harness/src/opencode/mod.rs:738–755).
- Desktop-compatible display does not add backend capabilities: OpenCode dispatch maps only advertised variants (opencode/mod.rs:1439–1463). Do not change adapters or claim all fallback levels affect every model.
- Ticket 67 owns loading/resource lifetime. Tickets 64/74/75 own other composer regions. No Rust changes or picker redesign are needed.

## 2. Spec

### 2.1 Effective ladder, preference and state

| Catalog / selection state | Desktop target and required web behavior | Source at `37c354ff` |
| --- | --- | --- |
| Selected model has a nonempty reasoning list | Use that model list in its advertised order; do not merge extra harness levels | `crates/ui/src/pickers.rs:1547-1552` |
| Selected model list is empty; harness list is nonempty | Fall back to the selected harness descriptor's list | `crates/ui/src/pickers.rs:1554-1561`; web currently uses nullish fallback at `components/composer-pickers.tsx:135` |
| No selected model yet | Desktop effective ladder is empty; preserve loading UI and retained user preference, without inventing model capabilities | `crates/ui/src/pickers.rs:1548-1550` |
| Both selected model and harness lists empty | No Reasoning section; model options can still show independently | `crates/ui/src/pickers.rs:3432-3438,3677`; web `components/composer-pickers.tsx:628,913-920` |
| Explicit or remembered level is offered | Keep it through pick, draft update, catalog refresh and send | `crates/ui/src/pickers.rs:187-194,762-775` |
| Explicit level absent or unsupported once catalog is resolved | Effective default is High if offered, else Medium, else first advertised level; null for an empty effective ladder | `crates/ui/src/pickers.rs:172-194`; web `lib/traits-summary.ts:25-47` |
| Model changes | Resolve the new model's effective ladder; keep a still-offered level. Native persisted-config normalization clamps only when the resulting ladder is nonempty; do not erase stored preference just because metadata is unavailable | `crates/ui/src/pickers.rs:1493-1511` |
| Harness changes | Descriptor must be for the new effective harness; clear foreign model/option state through existing policy | `components/composer-pickers.tsx:160-182`; `lib/composer-draft.ts:409-449` |
| Late descriptor or model refresh | Re-evaluate the current selection from both live inputs; do not let an old model-only effect erase a valid fallback choice | `components/composer.tsx:626-652,3091-3092` |
| Unsupported adapter capability | Display follows the existing desktop contract; do not claim every displayed fallback level changes every backend model | OpenCode maps only advertised variants at `crates/harness/src/opencode/mod.rs:1439-1463` |
| Preference versus effective value | Native explicit draft value takes precedence, then existing chat config or new-chat remembered defaults. Before a model resolves, retain the explicit value; once resolved, derive the effective value against the effective ladder. Do not confuse an empty effective value with permission to erase stored preference | `crates/ui/src/pickers.rs:762-775,1491-1512`; web remembered choice `lib/composer-draft.ts:327-328` |

The distinction between stored preference and effective value is deliberate. Do not eagerly replace remembered/persisted reasoning with null while a model or matching descriptor is still unavailable. Native effective_reasoning returns the explicit preference before the model resolves, while persisted-config normalization only clamps against a nonempty ladder. Once catalogs resolve, the displayed active row, trigger summary and outgoing effective choice must agree.

Current defaultDraft seeds first-model first-level or "medium" (composer-draft.ts:14–26), and composer initialization reads remembered harness but does not consistently seed remembered reasoning. If these paths supply a synthetic explicit value that bypasses native default selection, correct the reasoning initialization in this ticket and update the existing defaultDraft expectation intentionally. Preserve current harness/model choice, sandbox and other defaults.

### 2.2 Presentation, interaction, motion and transport

| Surface / path | Required contract | Source |
| --- | --- | --- |
| Tray position | Existing pinned tray immediately below model list, not a new separate selector/popover | `components/composer-pickers.tsx:718-782`; `crates/ui/src/pickers.rs:3432-3451` |
| Tray geometry | 236px max height, independent vertical scrolling, top hairline, 6px horizontal/bottom padding; existing 2px section row gap | `components/composer-pickers.tsx:56,918`; `styles/app.css:4441-4463`; Rust `pickers.rs:3448-3451,3683-3686` |
| Copy | Heading exactly Reasoning; labels through reasoningLabel; default row has existing Default badge | `components/composer-pickers.tsx:916-934`; `lib/traits-summary.ts:4-17` |
| Selection | Existing menu row click selects a level and leaves combined picker open | `components/composer-pickers.tsx:185-187,923-933`; Rust `pickers.rs:1396,1421-1427` |
| Draft update | Resolve effective ladder using model AND matching harness; preserve valid selection through applyDraftUpdate | Current lossy path `lib/composer-draft.ts:445-449` |
| Initial/reloaded model | Use same resolver for model seeding and later refresh; supplied descriptors must belong to current draft.harness | Current model-only path `components/composer.tsx:632,649,3091-3092`; defaultDraft `lib/composer-draft.ts:14-26` |
| Trigger summary / row selection | Both describe the same effective reasoning value; retain existing model-option summary/customized behavior | `components/composer-pickers.tsx:221-222,928`; `lib/traits-summary.ts:61-82` |
| Established chat persistence | Existing onPersist path writes chosen reasoning in ChatConfig; no new RPC or schema | `components/composer-pickers.tsx:149-157`; `lib/composer-actions.ts:42-48` |
| New-chat and run payload | Existing RunRequest copies draft.reasoning; assert selected level survives to that field | `lib/composer-actions.ts:63-75` |
| Phone | Same data and tray rules; verify tray is reachable with small viewport and keyboard visible before making CSS changes | Existing shared `ComposerPickers`/responsive picker; no runtime clipping reproduced |
| Motion / keyboard | Existing picker open/close, row selection, reduced-motion and keyboard behavior; no new timing or global handler | This is capability resolution and selection retention, not picker redesign |

Existing child order stays model list, pinned TraitsTray, then existing surrounding picker content. Within the tray, keep Reasoning heading/rows before existing model-option sections. Preserve all existing labels, options, opaque surfaces, keyboard behavior and reduced-motion handling. No new animation or duration is specified.

### 2.3 Implementation sequence

1. Establish a failing ready-catalog fixture: selected model has [], matching descriptor has [low, medium, high], and selected preference is low. The real picker must show all three rows and selecting high must update the draft to high.
2. Add one pure resolver with explicit selected-model absence handling. A suitable interface is effectiveReasoningLadder(model, descriptor), using existing Model/HarnessDescriptor types. Nonempty model list wins; otherwise use the matching descriptor list; no model gives no effective ladder. Preserve order and never union the lists.
3. Route visibility, row generation, selected row, trigger summary and customized-state checks through consistent resolved values.
4. Make applyDraftUpdate resolve the descriptor for the NEXT draft harness, not a captured previous harness. Update its real call sites and tests; do not pass a mismatched descriptor after a harness switch.
5. Use the same contract for initialization and model/descriptor reconciliation. Observe both live inputs. Avoid setState loops by returning the prior draft when nothing changed, and preserve preference while metadata is pending.
6. Keep existing persistence behavior: picker choice in an established chat uses onPersist; new chat retains its draft; sending remains QueueCommand Run without an extra setChatConfig mutation. Verify chosen reasoning reaches both existing builders.
7. Exercise the mounted picker and composer reconciliation, including a descriptor arriving after the model and a refresh while the picker remains open. A helper-only green test does not establish that actual callers use the resolver.
8. For the reported runtime case, record harness/model and sanitized catalog shapes plus whether the Reasoning DOM section is absent or present but clipped. If both advertised lists are empty, hiding is expected. If model levels are already nonempty, investigate loading (67) or clipping rather than claiming this fallback fix reproduces the report.

## 3. Pure logic to port and regression tests

Existing desktop/web counterparts in `web/packages/app/tests/traits-summary.test.ts:42–98`: default_reasoning_prefers_high_then_medium, clamp_reasoning_keeps_offered_levels_and_heals_foreign_ones, reasoning_label_spells_the_levels, traits_summary_formats_non_defaults, traits_customized_flags_only_real_departures. Retain default helper behavior; change who supplies its effective ladder. Rust tests at pickers.rs:5014 onward confirm High, then Medium, then first entry; the stale comment above the function mentioning X-High is not the contract.

| Fixture / action | Assertion |
| --- | --- |
| Model [low, high], descriptor [medium, max] | Only low/high offered in model order; no union |
| Model [], descriptor [low, medium, high] | Reasoning visible with those rows; null/foreign effective selection defaults to high |
| Model [low, medium], no descriptor | Model list works and null defaults to medium |
| Model [low], descriptor [] | Default low |
| Model [], descriptor [] | No Reasoning; independent model options still render |
| Model absent, descriptor nonempty, remembered high | No invented model choices and no destructive clearing of retained preference |
| Pick fallback high, then change unrelated draft field | Draft retains high; mounted row and summary agree |
| Existing persisted high or new-chat remembered high, offered ladder | Preference retained; existing-chat and new-chat precedence match native |
| Change to model that still offers chosen level | Retain it |
| Change to model with a nonempty ladder excluding chosen level | Derive native default |
| Switch harness with different descriptor | Use new harness descriptor only; preserve existing foreign-model/options policy |
| Model arrives before descriptor, then descriptor arrives | Tray and effective selection update without a model-row identity change |
| Equivalent model/descriptor refresh after choosing high | Selection retained, no render/update loop |
| Chosen high to buildChatConfig/buildRunRequest | Both reasoning fields are high; send does not add setChatConfig |

The current Vitest config discovers `tests/*.test.ts` and defaults to node. A new mounted suite must use .test.ts with React.createElement/createRoot/act and a per-file jsdom pragma, reusing ticket 67's dependency if already present or adding it as an app dev dependency with `web/pnpm-lock.yaml` update. jsdom checks DOM/actions, not real tray visibility or browser geometry.

Mount the actual ComposerPickers and exercise its real commit callback through the draft helper. Also exercise the actual composer model/descriptor reconciliation, or a narrowly extracted reconciliation owner still wired to it, so an unchanged model-only effect cannot pass unnoticed. Use a controllable ready catalog rather than requiring a live provider or repairing ticket 67 inline.

Preserve existing composer-actions tests: "mirrors the draft as the wire's ChatConfig", "fills every field the engine requires for a Run", "sends only QueueCommand Run — never a setChatConfig mutation", and "sends a setChatConfig Mutate op with the draft as the wire config".

## 4. Gaps this ticket closes

| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Empty model-list fallback | Verified source mismatch | Empty model levels fall back to harness descriptor | `composer-pickers.tsx:135` treats [] as a successful nullish value | Shared nonempty-list resolver, retaining no-model behavior |
| Hidden tray/section | Proven conditional consequence | Render Reasoning if effective ladder nonempty | `composer-pickers.tsx:628,775,913-920` hides reasoning for the incorrectly empty ladder | Use shared effective ladder in both visibility and row generation |
| Selection discarded on click | Verified source mismatch | Clamp against model-or-harness ladder | `composer-draft.ts:446-447` clamps only against model list, yielding null for [] | Pass matching descriptor into draft normalization |
| Refresh discards selection | Verified source mismatch | Reconcile against same effective ladder | `composer.tsx:632,649,3091-3092` uses model-only lists | Update reconciliation and dependencies with descriptor-aware resolution |
| Exact reported device/model | Unverified runtime | Depends on actual selected catalog | No model/harness/device supplied at ticket authoring | Record actual payload shape and distinguish hidden DOM from clipped UI before claiming symptom reproduced |

## 5. Do not

- Do not add an always-visible reasoning control with invented levels, union model/harness choices, or substitute X-High as the default.
- Do not make a visibility-only fix while leaving draft normalization or refresh able to erase the selection.
- Do not destroy stored preferences during loading or confuse retained preference with the effective choice for a resolved capability list.
- Do not add RPC/schema changes, adapter behavior, backend reasoning guarantees or desktop Rust changes.
- Do not change existing model options, labels, menu dismissal, keyboard handlers, opaque web surfaces or reduced-motion behavior.
- Do not absorb ticket 67 resource ownership, 64 performance, 74 geometry or 75 phone Enter work.
- Do not claim the user's exact symptom was reproduced without the actual model/catalog/device evidence.
- Subagents must not launch long-running dev servers, engines, smoke processes or browsers. Runtime verification belongs to the coordinator using an authorized app/capture.

## 6. Acceptance

- [ ] Source mismatch reproduced by a failing fixture before implementation; the fallback ladder and real click path pass after it.
- [ ] Model-specific nonempty ladder takes precedence; empty model levels fall back to the matching harness; both empty omit only Reasoning.
- [ ] Chosen reasoning survives actual draft updates and mounted model/descriptor refresh; late descriptor arrival updates the picker.
- [ ] Existing-chat preference and new-chat remembered/default behavior match the stated native precedence without clearing preferences during loading.
- [ ] Visible active row, trigger summary, ChatConfig.reasoning and RunRequest.reasoning agree for the selected level.
- [ ] Existing send/persist separation, model options and non-reasoning defaults remain intact.
- [ ] Native default/clamp counterpart tests and the test matrix above pass.
- [ ] Desktop/web screenshot pairs cover model-specific reasoning, descriptor fallback and options-only states; web phone capture confirms the tray remains reachable with keyboard visible. Record any unavailable runtime evidence as pending, never passed or waived.
- [ ] Record tested executable and served web bundle identity: engine embeds assets at compile time, so current source alone is insufficient.
- [ ] `pnpm -r build` from `web/` and `pnpm test` from `web/packages/app/` pass. No Rust checks needed unless scope is explicitly expanded to Rust.
- [ ] No unmeasured CSS workaround, new literal design value where a token exists, or unrelated picker redesign.

## Comments

Research/ticket authoring only. No source implementation, dependency installation, tests, builds or runtime reproduction performed.

### 2026-09-20 — implemented on `wp-fu/77`

**Failing-first evidence (baseline `381c768d`, before implementation):** the §2.3-step-1 mounted fixture (model `[]`, descriptor `[low, medium, high]`, preference `low`, real `ComposerPickers` over a ready fake-client catalog) failed as predicted — `expect(traitHeadings()).toEqual(["Reasoning", "Thinking"])` received `["Thinking"]` (the model's empty list suppressed the descriptor fallback), and the follow-on click/persist/refresh tests failed on the absent `high` row. 3 failed / 2 passed at baseline; the 2 passes were the guard fixtures (nonempty-model precedence, both-empty options-only), which pin already-correct behavior.

**What landed:**
- `traits-summary.ts`: `effectiveReasoningLadder(model, descriptor)` — `trait_ladder` port (nonempty model list wins in advertised order, else the matching descriptor's; no model → no ladder; never a union).
- `composer-draft.ts`: `applyDraftUpdate` takes `resolveDescriptor` and clamps against the effective ladder of the NEXT draft's harness, only when that ladder is nonempty (persisted-config normalization, pickers.rs:1493-1512) — stored preferences are retained verbatim while metadata is unavailable. New `reconcileDraftModel` pure core for the composer effect (seed + clamp, identity return on no-change).
- `composer-reconciliation.ts` (new, the §2.3-step-7 "narrowly extracted reconciliation owner"): `useDraftModelReconciliation(models, harnesses, setDraft)` observing BOTH live inputs; `composer.tsx` wires it with `models.rows`/`harnesses.rows` (replacing the model-only effect + `ladderFor`).
- **Intentional `defaultDraft` correction (§2.1 final paragraph):** the first-level-or-`"medium"` seed is gone; reasoning now follows native precedence — the new-chat `remembered` level (new optional param, threaded from `composerDefaults` at the composer init and through `draftFromChat`) kept verbatim while no ladder resolves, else clamped (offered → kept, foreign/absent → High/Medium/first). Existing expectations updated intentionally: `defaultDraft(HARNESSES, MODELS).reasoning` `"low"→"high"`, empty-catalog `"medium"→null`, and the `draftFromChat` null-config replay `"low"→"high"`. Harness/model choice, sandbox and other defaults preserved.
- Harness switches (picker `pickHarness` and the composer's foreign-harness heal) clear reasoning to the REMEMBERED level instead of `null`, matching native (`config.reasoning = None` + `effective_reasoning`'s `defaults.reasoning` fallback); the reconciliation re-derives it against the new harness's effective ladder. Foreign model/options clearing policy unchanged.
- `composer-pickers.tsx`: visibility, rows, selected row, trigger summary and customized state all consume the shared resolver; `commit` passes a per-call `resolveDescriptor` (never a captured previous harness).
- `composer-actions.ts` untouched — builders already copy `draft.reasoning`; a new test asserts the chosen level reaches both `ChatConfig` and `RunRequest`, and the existing "sends only QueueCommand Run — never a setChatConfig mutation" suite passes unchanged.
- No dependency/config/CSS changes; jsdom was already present (ticket 67) and the new mounted suite reuses the session-provider idiom plus jsdom stubs for matchMedia/ResizeObserver/scrollIntoView.

**Tests:** `pnpm -r build` (web/) passes; `pnpm test` (web/packages/app) passes: 90 files, 1424 tests (baseline 1389 + 35 new: 11 mounted picker/hook in `composer-reasoning.test.ts` incl. the wiring pin that `composer.tsx` delegates to the hook, 6 ladder-resolver in `traits-summary.test.ts`, 17 in `composer-draft.test.ts` covering the matrix's draft rows, 1 transport agreement in `composer-actions.test.ts`). `tests/registry.test.ts`'s known 1ms parallel-load flake did not appear in the final full runs.

**Pending runtime evidence (not claimed):** the reported-case catalog shape (exact harness/model/device), desktop/web screenshot pairs (model-specific reasoning, descriptor fallback, options-only), and phone tray reachability with the keyboard visible remain unverified — no dev server, engine, smoke build, or browser was run per the hard rules, so no tested executable/served-bundle identity is recorded. jsdom coverage asserts DOM presence/actions only, not real tray geometry. If the user's actual model already advertises nonempty levels, the symptom more likely involves catalog liveness (ticket 67) or clipping than this fallback path.

