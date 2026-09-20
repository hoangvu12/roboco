# Follow-up research — missing model reasoning selector

Baseline: `web-parity/wave-2` @ `37c354ff`, 2026-09-20.
New user report: "the model picker dont seem to have reasoning selector?"
Scope: source research and tickets only. No implementation, test execution or runtime reproduction.

## 1. Finding and confidence

The UI is implemented: TraitsTray renders the Reasoning heading and rows below the model list (`web/packages/app/src/components/composer-pickers.tsx:918-934`). It is not a wholly missing feature.

A concrete parity defect can hide it. Web selects `selectedModel?.reasoningLevels ?? descriptor?.reasoningLevels ?? []` (:135). An empty array is not nullish, so a model with [] suppresses the descriptor fallback. The tray predicate (:628) and section predicate (:920) then see no reasoning choices. Desktop explicitly tests whether the model list is nonempty, then falls back to the harness list (`crates/ui/src/pickers.rs:1547-1561`).

Fixing visibility alone is insufficient. The click path remembers the choice then calls commit -> applyDraftUpdate (:185-187,149-157). That function clamps against model levels only (`lib/composer-draft.ts:445-449`), so a valid descriptor-backed choice becomes null. The composer's seeding/reload effect also uses model-only levels (:632,649,3091-3092). BuildChatConfig and BuildRunRequest copy reasoning as supplied; transport cannot restore a selection already lost in the draft.

The exact user's model/harness and phone/desktop context are pending clarification. The source establishes conditional failure paths; it does not prove that a particular running picker used one. If the actual model has nonempty levels, inspect catalog readiness (ticket67) or DOM clipping rather than assuming this fallback defect explains it.

## 2. Real source shapes and exclusions

- Claude's descriptor advertises levels (`crates/engine/src/registry.rs:447-452`); Haiku's catalog model has an empty reasoning list and a separate thinking option (`crates/harness/src/claude/catalog.rs:144-148`). Preserve model options while matching the actual desktop fallback.
- OpenCode's descriptor advertises levels (`crates/engine/src/registry.rs:592-597`); models with no recognized variants can have [] (`crates/harness/src/opencode/mod.rs:738-755`). Backend dispatch still maps only advertised variants (:1439-1463); no new backend capability is promised or added.
- Rust's comment above default_reasoning still mentions X-High, but the implementation and tests use High, then Medium, then first entry (:172-181,5014-5027). Follow executed logic and tests.
- No dedicated phone hide rule for .model-traits was found in the searched stylesheet; it scrolls at a 236px cap. This is not proof against overflow/clipping elsewhere in a running responsive card.
- Ticket67 addresses catalog liveness, not reasoning-ladder resolution. These fixes are independently testable with ready catalog fixtures.

## 3. Contracts

### 3.1 State
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

### 3.2 Rendering and data flow
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

## 4. Required future regressions

Existing desktop/web counterparts in `web/packages/app/tests/traits-summary.test.ts:42-98`: default_reasoning_prefers_high_then_medium, clamp_reasoning_keeps_offered_levels_and_heals_foreign_ones, reasoning_label_spells_the_levels, traits_summary_formats_non_defaults, traits_customized_flags_only_real_departures. Retain default helper behavior; change who supplies its effective ladder.

New cases must cover model levels overriding descriptor; empty model falling back to descriptor; both empty; absent model; valid/foreign explicit levels; click -> draft -> refresh -> ChatConfig/RunRequest with non-null selection. DOM coverage mounts the actual picker with a controllable ready catalog, opens it and clicks a Reasoning row. A standalone resolver test cannot detect a caller that continues clamping model-only.

The current Vitest config discovers `tests/*.test.ts` and defaults to node. A new mounted suite must use .test.ts with React.createElement/createRoot/act and a per-file jsdom pragma, reusing ticket67's dependency if already present or adding it as an app dev dependency with `web/pnpm-lock.yaml` update. jsdom checks DOM/actions, not real tray visibility or browser geometry.

## 5. Gaps
| Item | Kind | Desktop value | Web value (file:line) | Fix |
| --- | --- | --- | --- | --- |
| Empty model-list fallback | Verified source mismatch | Empty model levels fall back to harness descriptor | `composer-pickers.tsx:135` treats [] as a successful nullish value | Shared nonempty-list resolver, retaining no-model behavior |
| Hidden tray/section | Proven conditional consequence | Render Reasoning if effective ladder nonempty | `composer-pickers.tsx:628,775,913-920` hides reasoning for the incorrectly empty ladder | Use shared effective ladder in both visibility and row generation |
| Selection discarded on click | Verified source mismatch | Clamp against model-or-harness ladder | `composer-draft.ts:446-447` clamps only against model list, yielding null for [] | Pass matching descriptor into draft normalization |
| Refresh discards selection | Verified source mismatch | Reconcile against same effective ladder | `composer.tsx:632,649,3091-3092` uses model-only lists | Update reconciliation and dependencies with descriptor-aware resolution |
| Exact reported device/model | Unverified runtime | Depends on actual selected catalog | No model/harness/device supplied at ticket authoring | Record actual payload shape and distinguish hidden DOM from clipped UI before claiming symptom reproduced |
