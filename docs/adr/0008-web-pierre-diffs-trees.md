# The web client adopts @pierre/diffs and @pierre/trees for code/diff/tree surfaces, ending pixel-parity for them

The web client was built as a desktop-parity port: every surface's doc-comments cite the desktop file:line it ports, and the research notes record exact desktop measurements. That mandate produced hand-rolled replacements for three things the ecosystem already solves — a ~1,000-line textarea-overlay code editor with a hand-rolled tokenizer (`code-view.tsx` + `lib/syntax.ts`), a 1,145-line custom virtualized diff viewer plus a 1,024-line patch parser (`diff-view.tsx` + `lib/diff.ts`), and a hand-rolled file tree with a VS Code icon-manifest port. Research (`.scratch/web-pierre-adoption/research.md`) evaluated `@pierre/diffs` (diffs.com, v1.4.x, Apache-2.0, React 18/19) and `@pierre/trees` (trees.software, 1.0.0-beta.6, Apache-2.0) and the decision is to adopt both:

- The Changes pane renders from the engine's existing `patch` string via `parsePatchFiles` → `FileDiff`/`CodeView`, with review comments as line annotations; `ChangesStore` and the engine diff pipeline stay as-is.
- The file viewer is a read-only Pierre `<File>` fed by the existing `ReadWorkspaceFile`/`FileDocument` machinery. File editing on web is **suspended** until a follow-up adopts Pierre edit mode (`EditProvider` + `onEditChange` → `FileDocument`); the hand-rolled editor is deleted, not kept in parallel.
- The file tree is `useFileTree`/`<FileTree>` (beta), with git status from the existing watch mapped to `setGitStatus`.
- Theming: a Roboco Shiki theme registered from the web theme tokens plus the `--diffs-*`/`--trees-*` CSS variables; bundle cost accepted (~+120 KB gzipped main, lazy per-language chunks).

Accepted consequences:

- The web's editor/diff/tree look like Pierre's design language, not the desktop's. Desktop and web diverge on these surfaces, and future desktop changes to them no longer auto-port — the parity porting pattern ends for these three surfaces. Parity remains the rule for chat, composer, settings, and the rest.
- Trees is beta: pin the version, expect API churn, and keep the integration seam thin (one component wrapping the model).
- Edit mode is beta and deferred; until it lands, web file viewing is read-only.
- Known bugs fixed by adoption and not by us: tree indentation guides (stacking per level), diff rendering fidelity.
