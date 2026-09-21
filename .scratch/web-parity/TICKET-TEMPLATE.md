# Ticket template — web parity

Read this before writing any ticket under `issues/`. The reader of a ticket
is a small model in a fresh context window that has NOT read the research,
the Rust, or the other tickets. Everything it needs to build the ticket must
be in the ticket. Links to research sections are for depth, never a
substitute for the content.

## Rules

1. **Copy, don't summarize.** Layout tables, motion tables, state tables,
   strings, keyboard tables, and data/RPC details are copied from the
   research file's §3/§4 verbatim, including the `file:line` sources. If a
   research table has 20 rows, the ticket has 20 rows.
2. **One ticket = one demoable slice.** After the ticket, a screenshot of
   the named states matches the desktop. If the slice needs a piece of
   another ticket, that ticket is a blocker, not an inline dependency.
3. **Web files are named.** For each file: what changes (new / edit /
   delete), and the components or CSS classes inside it that this ticket
   owns. The writer greps `web/packages/app/src` to find them; do not guess.
4. **Gaps are explicit.** The §5 rows from the research that this ticket
   closes, verbatim, so the implementer knows current vs. target.
5. **"Do not" is explicit.** INVENTED items the implementer must not
   re-add; desktop-only items not to attempt; adjacent things owned by other
   tickets.
6. **Acceptance is checkable.** Each criterion is a thing you can look at
   or run. Screenshot pairs name the exact state ("chat selected, sidebar
   row hovered"). Unit tests name the desktop test they port.
7. **Length is not a concern.** A ticket for a big component is 400–800
   lines. That is correct.

## File layout

```
# NN — <Title>

**What to build:** 2–5 sentences, from the user's point of view: what they
see and can do after this ticket that they could not before.

**Blocked by:** NN (<title>), NN (<title>) — or "None — can start immediately".

**Status:** ready-for-agent

**Research:** `../../web-client/research/NN-<file>.md` §3.x, §3.y, §4, §5 rows a–b.

**Desktop reference (for lookups only):** `crates/ui/src/<file>.rs::<fn>` …

**Web files to touch:**
| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/x.tsx` | edit | `XComponent` |
| `web/packages/app/src/styles/app.css` | edit | `.x`, `.x-row`, `.x-row-active` |
| … | new | … |

## 1. Context a fresh session needs

5–12 bullets. What this surface is, where it sits, how it is wired today on
the web (store → component → CSS), which tokens/vars it uses, which
vocabulary applies. Enough that the implementer does not need to explore.

## 2. Spec

One `### 2.x <Component>` per component this ticket builds, in render
order. Each has, copied from the research:

**Layout** (table: property | value | source)
**Children (in order)**
**States** (table: state | condition | what changes)
**Interactions**
**Motion** (table: what | trigger | spec | from → to | reduced motion)
**Text** (verbatim)
**Data** (reads / writes / RPC)

## 3. Pure logic to port

Signatures, rules in prose, and the desktop test names that become the web
unit tests. Copied from research §4.

## 4. Gaps this ticket closes

Table copied from research §5, filtered to this ticket:
item | kind | desktop value | web value (file:line) | fix.

## 5. Do not

- Bullets: INVENTED items not to re-add; desktop-only items to skip;
  things owned by other tickets (name the ticket).

## 6. Acceptance

- [ ] <one line per component/behavior, checkable>
- [ ] Unit tests: `<desktop test name>` → `<web test file>` …
- [ ] Screenshot pair, desktop vs web, states: <list>
- [ ] `pnpm -r build` green; package vitest green
- [ ] No new literal hex/px where a `--rb-*` token exists

## Comments

(empty; appended during implementation)
```
