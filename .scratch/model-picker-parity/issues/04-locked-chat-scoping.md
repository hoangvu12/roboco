# 04 — Locked chats scope the picker's rows and favorites

**What to build:** A locked chat (a side chat whose config is not editable —
`harness_locked`, pickers.rs:861-864) restricts the desktop's picker to the
locked harness: the rail retains only that harness's tab and the favorites
view scopes to it, so no dead rows ever render (pickers.rs:1936-1940). The
web renders **all** tabs (`.model-tab-locked`, opacity .35 —
composer-pickers.tsx:1020-1046, app.css:4856-4864) and the favorites view
spans every harness (`scopedModelRows` gets unfiltered descriptors,
composer-pickers.tsx:646-650) — foreign starred rows render as dead rows
whose picks no-op (pickModel's locked guard, :317-321). The current shape is
self-documented ("gap row 46", app.css:4856) and pinned by tests
(composer-reasoning.test.ts:691-723).

**After this ticket, a locked chat's picker scopes its rows and favorites to
the locked harness (no dead foreign rows), while keeping the web's
visible-but-disabled foreign tabs** (the tab strip is the tab *set*; the
content is the scope — a deliberate web shape recorded below).

**Blocked by:** None.

**Status:** ready-for-human

**Research:** `../research.md` §2 (locked-chat item).

**Desktop reference (for lookups only):** `crates/ui/src/pickers.rs:861-864`
(`harness_locked`), `:1936-1940` (rail retention), `:1936-1940`'s callers for
the favorites scoping.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/composer-pickers.tsx` | edit | filter `railDescriptors` (~:207-214): `locked ? [effective] : offered`, so the list AND the favorites view scope to the locked harness; keep rendering the offered tab strip with locked styling |
| `web/packages/app/src/lib/model-rows.ts` | edit (if the scoping helper lives here) | pure scope predicate |
| `web/packages/app/tests/composer-reasoning.test.ts` | edit | update the pinned locked-chat block (:691-723) to the new semantics |
| `web/packages/app/tests/model-rows.test.ts` | edit | pure scoping tests |

## 1. Why keep the disabled tabs (recorded decision)

The desktop hides foreign tabs entirely for locked chats; the web's
visible-but-locked tabs (recorded at app.css:4856) show *why* the picker is
restricted instead of silently shrinking — closer to a disabled control than
a hidden one. This ticket scopes **content** (rows, favorites) without
changing the tab strip's visibility; if the maintainer prefers the desktop's
full hiding, that is a one-line follow-up on top of this ticket (delete the
locked-tab rendering arm). Say so in the commit message.

## 2. Tests

- `composer-reasoning.test.ts` locked-chat block: with a locked side chat,
  the harness tab lists only its models; the favorites view shows only
  starred models of the locked harness; foreign tabs still render with
  `.model-tab-locked`.
- `model-rows.test.ts`: the scope predicate returns `[effective]` when
  locked, the full offered set otherwise.

## 3. Acceptance checklist

- [x] Locked chat: list and favorites scoped to the locked harness
- [x] Foreign tabs still render (locked styling) — recorded decision kept
- [x] Pinned tests updated to the new semantics; no dead rows render
- [x] Full app suite green

## Comments

**Branch:** `ticket/mp-04-locked-chat-scoping` (base `a485d5ce`). Commit
`751d9d89` (implementation, TDD red→green at both named seams: the new
locked-scope pin failed pre-fix with exactly the dead foreign star
`["Parent model", "Codex starred"]`) + review-pass commit (strengthens
that pin with the viewed-tab assertion — the favorites tab must actually
engage `aria-selected="true"`, else a regression that disables the tab
would leave the rows assertion vacuously green; the desktop keeps the
tab's on_click unguarded at pickers.rs:3918 and scopes content, not the
tab). Verification (all from `web/packages/app`): named seams
`tests/composer-reasoning.test.ts` + `tests/model-rows.test.ts` → 57/57;
the six adjacent suites importing the changed modules (base-popover,
catalog-loading, composer-edit-failure, flyout-side, picker-catalog,
shortcuts) → 116/116; full app suite `pnpm exec vitest run` → **148 files,
2233 tests, all passed**; `pnpm exec tsc --noEmit` clean.

**Shape:** the web splits the desktop's one `rail_descriptors`
(pickers.rs:1925-1940) into two memos — `tabDescriptors` (offered +
force-inserted committed row, UNscoped, feeds the tab strip) and
`railDescriptors` (the same set through the new pure
`scopedRailDescriptors()` in lib/model-rows.ts, feeds the rows, the
favorites scope, the models subscription, and the compact card). That is
the §1 recorded decision made mechanical: the strip is the tab set, the
content is the scope. Deleting the locked-tab rendering arm restores the
desktop's full hiding — the one-line follow-up named in the commit
message.

**Adjudicated behavior deltas** (review phase, both against the desktop's
`rail_descriptors` consumers): (1) the models SUBSCRIPTION narrows with
the scope — faithful: the desktop still LOADS foreign models
(`prefetch_models`, pickers.rs:1384-1397, targets offered + effective) and
so does the web (`catalog.prefetchModels`, picker-catalog.ts:415-427,
untouched); every desktop models-map READ while locked goes through the
effective harness or the rail-scoped descriptors (:982, :1848, :1990,
:4007), so subscribing only to slots whose rows can render mirrors the
desktop's read scope exactly. (2) the compact card receives the scoped set
— faithful: compact.rs consumes `rail_descriptors` for its providers page
(:417), Tab cycling (:897), and the provider name lookup (:1131), and sets
rail Harness when locked (:329-336); the providers page is unreachable
when locked on both platforms (desktop `when(!locked)` on the provider
button, web `disabled={locked}`).

**Residual, out of ticket:** the web's committed-harness force-insert
doesn't check `installed` (desktop pickers.rs:1931-1934 does) — pre-existing
divergence, untouched. The web compact "Starred" entry opens the all/harness
rail rather than a Favorites rail (desktop `show_compact_starred`,
compact.rs:338-341) — pre-existing, owned by ticket 05.
