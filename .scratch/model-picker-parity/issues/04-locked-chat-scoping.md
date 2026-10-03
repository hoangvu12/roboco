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

**Status:** ready-for-agent

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

- [ ] Locked chat: list and favorites scoped to the locked harness
- [ ] Foreign tabs still render (locked styling) — recorded decision kept
- [ ] Pinned tests updated to the new semantics; no dead rows render
- [ ] Full app suite green
