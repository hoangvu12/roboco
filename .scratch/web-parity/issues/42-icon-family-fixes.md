# 42 — Icon family fixes: root-absolute file-icon URLs, the regenerated stroke family, the Changes pane glyph

**What to build:** The polychrome file-type icons actually render everywhere.
Today every folder/language icon 404s on `/chat/$chatId` routes (the asset
prefix is a document-relative URL), the `file-*` control icons
(`fileImage`, `fileCode`, …) paint nothing because the icon generator drops
the root SVG stroke attributes, and the Changes pane's file headers show an
invented generic `document` placeholder instead of the real per-file icon.
After this ticket: opening a chat shows folder and language icons in the
files tree, mention popup, tool-chip file badges, stats rows, breadcrumb and
markdown file links; the Settings → Appearance "New thread composer
background" row shows its `fileImage` glyph; the Files viewer header shows
`fileCode`; and the Changes pane resolves each file's real icon through the
same manifest the files tree uses.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/transcript-tool-calls-scroll.md` S3
(a)–(d); `../research-2026-09-19/new-thread-background-and-transitions.md` S3
(a)–(d); consolidated gap rows 6, 7.

**Desktop reference (for lookups only):** `crates/ui/src/file_icons.rs` —
`ASSET_PREFIX` (:18), the `AssetSource` impl (:56-82), the dark accent-lift
(:20-38, :64-71), `icon()` (:197-205), resolution order (:237-244, :246-268),
generic fallbacks (:270-276), the bundle-floor tests (:459-512);
`crates/ui/src/file-icons.json` (350 iconDefinitions, 106 languageIds — the
mapping table source); `crates/engine/src/listener.rs:154-173` and
`:371-389` (static bundle + SPA fallback); `crates/ui/assets/icons/file-*.svg`
(the root-stroked sources); `crates/ui/src/icons.rs` ("file-image" :147).
Desktop tests (verbatim, all in file_icons.rs): `exact_names_are_case_insensitive_and_ignore_parent_dots`
(:373), `compound_extensions_win_longest_first` (:393),
`folders_resolve_name_and_expansion_state` (:409 — `src` →
`folder-orange-code`, expansion ignored),
`appearance_independence_and_hints_are_supported` (:425 — language hint
`untitled`+Rust → `files/rust.svg`), `symlinks_and_unknown_files_fall_back_cleanly`
(:441), `every_manifest_reference_is_embedded_and_every_asset_is_svg` (:460),
`complete_theme_bundle_is_registered` (:493).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/lib/file-icons.ts` | edit | `ASSET_PREFIX` (:23) and `fileIconAssetPath` (:135-138) — root-absolute URL |
| `web/packages/icons/scripts/generate.mjs` | edit | `parse()` (:26-38) — carry the root `<svg>` stroke attributes onto the emitted body; assert every asset's root paints |
| `web/packages/icons/src/generated/index.ts` | regenerate | the `file-*` family (:49-53) via `pnpm --filter @roboco/icons regenerate` |
| `web/packages/app/src/components/diff-view.tsx` | edit | `FileGlyph` (:572-581) — replace with `FileIcon kind="file"`; delete the placeholder |
| `web/packages/app/tests/file-icons.test.ts` | edit | root-absolute asset-path assertions (§3) |

---

## 1. Context a fresh session needs

- File-type icons are POLYCHROME `<img>` elements, not tinted SVGs:
  `components/files/file-icon.tsx::FileIcon` renders
  `<img src={resolveFileIcon(...)} …>` (file-icon.tsx:32-48) from assets
  copied into `web/packages/app/public/file-icons/**` (verified present: 249
  `files/*.svg` + 105 `folders/*.svg`, both light and a baked `dark/` mirror).
  The img form is deliberate — authored multi-color fills must not be tinted
  by `currentColor` (unlike the monochrome `@roboco/icons` `Icon`).
- Resolution is a faithful port of `file_icons.rs` (ticket 24, Status: done):
  exact lowercased basename → longest compound extension → language hint →
  MIME hint → generic (lib/file-icons.ts:145-167); directories by exact name
  else the generic folder (lib/file-icons.ts:175-179); the `less`/`yml`
  aliases (lib/file-icons.ts:90-98); `wellBg` (lib/file-icons.ts:342-347).
  The manifest is generated verbatim from the desktop's
  `crates/ui/src/file-icons.json`
  (web/packages/icons/scripts/generate-file-icons.mjs:93-134). **The resolver
  is correct — do not touch it.**
- Chats navigate to `/chat/$chatId` (router.tsx:28; chat-page.tsx:678;
  state/session-provider.tsx:171). At a document URL
  `https://host/chat/abc`, a RELATIVE reference `file-icons/files/rust.svg`
  resolves against the path's directory (`/chat/`) →
  `https://host/chat/file-icons/files/rust.svg`.
- The engine's HTTP listener serves the embedded bundle only at exact root
  paths; the SPA fallback applies ONLY to extension-less paths
  (`!path.contains('.')`), so `/chat/file-icons/…` falls through the static
  lookup (listener.rs:154-173, :371-389) into the credential gate →
  401/404. Every `<img>` 404s → empty boxes. At `/` and `/settings` the
  relative path happens to resolve to the root and the icons load — which is
  why smoke captures at the index route looked fine (ticket 24's verification
  only confirmed resolver STRINGS, issues/24-files-tree-search.md:750).
- Control icons come from `@roboco/icons`: `web/packages/icons` generates
  `src/generated/index.ts` from `crates/ui/assets/icons/*.svg` via
  `scripts/generate.mjs` (regenerate: `pnpm --filter @roboco/icons
  regenerate`; freshness: `pnpm --filter @roboco/icons check` — nothing in CI
  runs it). `parse()` (generate.mjs:26-38) keeps ONLY `viewBox` and `fill`
  from the root `<svg>` and drops `stroke`, `stroke-width`, `stroke-linecap`,
  `stroke-linejoin`; `Icon` (icons/src/index.tsx:34-53) sets only
  `fill={asset.fill}` on the `<svg>`. Most desktop assets repeat
  `stroke="currentColor"` per-path so they survive; the `file-*` family
  carries stroke ONLY at the root — so `fileCode`/`fileData`/`fileImage`/
  `fileMarkdown`/`fileStyle` (generated/index.ts:49-53) render invisible
  (`fill: "none"` + no stroke anywhere).
- Affected `file-*` call sites: `fileImage` →
  routes/settings-appearance.tsx:236 (the "New thread composer background"
  row's `RowTile`, components/settings-widgets.tsx:31-37, tile CSS
  app.css:10200-10214); `fileCode` → components/files/file-viewer.tsx:232.
- Vocabulary: chat, engine, harness, space (CONTEXT.md). No `--rb-*` tokens
  are involved (icons are images / currentColor glyphs).

---

## 2. Spec

### 2.1 Root-absolute asset URLs

**Desktop reference (verbatim from research S3(b)):**

| Item | Value | Source |
| --- | --- | --- |
| Asset namespace | `ASSET_PREFIX = "file-icons/"` registered with gpui's composite AssetSource — an ABSOLUTE virtual path, immune to any URL shape | file_icons.rs:18, :56-82 |
| Dark variant | `file-icons/dark/<asset>` produced by the 7-pair accent-lift table at load time | file_icons.rs:20-38, :64-71 |
| Rendering | `icon(identity, appearance) -> gpui::Img` — the polychrome artwork rasterizes with authored fills | file_icons.rs:197-205 |
| Resolution order | exact basename (case-insensitive) → longest compound extension → language hint → MIME hint → generic file/folder | file_icons.rs:246-268, :237-244 |
| Generic fallbacks | manifest `file` else `files/document.svg`; manifest `folder` else `folders/folder.svg` | file_icons.rs:270-276, :237-244 |
| Mapping table source | `crates/ui/src/file-icons.json` — 350 iconDefinitions, 106 languageIds (e.g. `typescript→ts`, `rust→rust`, `shellscript→shell`, `src→folder-orange-code`), verified identical to the web's generated manifest | file-icons.json; generate-file-icons.mjs:93-134 |
| Bundle floor | ≥350 embedded SVGs, every reference resolvable, every asset has viewBox/dimensions | file_icons.rs:459-512 |

The web kept the desktop's `ASSET_PREFIX` STRING but the desktop's is a
gpui-absolute asset namespace while the web's is a document-relative URL
(lib/file-icons.ts:23, :135-138).

**The fix.** Make the web's prefix root-absolute:
`ASSET_PREFIX = "/file-icons/"` (or `` `${import.meta.env.BASE_URL}file-icons/` ``
if a base path ever applies) in lib/file-icons.ts:23, so
`fileIconAssetPath` (lib/file-icons.ts:135-138) returns
`/file-icons/[dark/]<asset>` — immune to the route, exactly like the
AssetSource. No other resolver change.

**Affected call sites (all render inside `/chat/$chatId`; verified):** the
files tree rows (file-tree-panel.tsx:342, drag ghost :430, search rows
:638), the composer mention popup (composer/mention-popup.tsx:173), the tool
chips' file badge (tool-group.tsx:608) and stats rows (tool-group.tsx:737),
the file breadcrumb (file-viewer.tsx:205), the markdown sole-file-link badge
(markdown.tsx:500). They need NO changes — the URL fix flows through
`FileIcon`/the resolvers — but the acceptance walk covers them all.

### 2.2 The generated stroke family (the invisible `file-*` glyphs)

From `new-thread-background-and-transitions.md` S3 — the defect, verbatim:

- The generated asset (`web/packages/icons/src/generated/index.ts:51`) is
  `fill: "none"` with a body whose `<path>`s carry **no stroke attributes**:
  `<path d="M14 2H7a3 3…"/><path d="M14 2v6h6M5 20l5-6…"/><circle …/>`.
  The `Icon` component sets only `fill={asset.fill}` and no stroke
  (`web/packages/icons/src/index.tsx:34-53`). Result: `fill:none` + no stroke =
  invisible — the icon is in the DOM but paints nothing.
- Cause: the generator's `parse()` keeps only `viewBox` and `fill` from the
  root `<svg>` and drops `stroke`, `stroke-width`, `stroke-linecap`,
  `stroke-linejoin` (`web/packages/icons/scripts/generate.mjs:26-38`). Most
  desktop assets repeat `stroke="currentColor"` per-path so they survive; the
  file-type family carries stroke **only at the root**. Verified broken set
  (generated body contains no `stroke`, root `fill:"none"`):
  **`fileCode`, `fileData`, `fileImage`, `fileMarkdown`, `fileStyle`**
  (generated/index.ts:49-53; sources `crates/ui/assets/icons/file-*.svg`,
  e.g. `file-image.svg`: `<svg … fill="none" stroke="currentColor"
  stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">`).

**The fix (generator, then regenerate).**

1. `parse()` (generate.mjs:26-38) reads the root `<svg>`'s `stroke`,
   `stroke-width`, `stroke-linecap`, `stroke-linejoin` in addition to
   `viewBox`/`fill`, and re-emits them ONTO EVERY `<path>`/`<circle>`/… in
   the body that does not already carry its own — the safe form, since the
   body is injected via `dangerouslySetInnerHTML` (icons/src/index.tsx:49-51)
   and root-inherited attributes do not survive that injection. Emitting them
   on the root `<svg>` (and passing them through `Icon`) is the alternative;
   pick the per-path form so `Icon` needs no change.
2. Regenerate: `pnpm --filter @roboco/icons regenerate` — the diff must show
   ONLY the stroke additions on the affected family (and any other
   root-stroked asset the fix picks up); `pnpm --filter @roboco/icons check`
   green afterwards.
3. Guard the class of bug: generation asserts every asset's root paints —
   an entry whose root `fill` is `none` AND whose body carries no
   `stroke=`/`fill=` attribute fails the run (an invisible glyph can never be
   committed again).

**Blast radius (the two live usages, fixed by the regeneration alone):**
`fileImage` — routes/settings-appearance.tsx:236 (the settings row's tile,
36px box around a 16px `theme.text_muted` glyph, app.css:10200-10214);
`fileCode` — components/files/file-viewer.tsx:232. This also restores the
settings-row icon visibility that 33's research S3
(`new-thread-background-and-transitions.md`) reported — cross-reference
ticket 33 (new-thread background pixel parity, whose research S3 documents
the row) so its acceptance can rely on the glyph being visible; do not
rebuild that row here.

### 2.3 The Changes pane file glyph

The Changes pane's file headers still use the invented placeholder — `FileGlyph`
renders the monochrome `document` control icon for every file and ignores the
path (diff-view.tsx:572-581, the doc comment admits it: "ticket 24's port …
replaces this single seam when it lands; until then one generic document
glyph stands in"). The desktop renders the real per-file icon there
(`file_icons::icon` on file headers).

**The fix.** In `diff-view.tsx`, replace the `<FileGlyph path={file.path} />`
call site (:559) with
`<FileIcon kind="file" name={file.path} appearance={appearance} size={14}
className="diff-file-icon" />` (thread `appearance` down if the component
does not already receive it) and DELETE `FileGlyph` (:572-581). No new
mapping table is written — the desktop's folder/language mapping is the
generated manifest resolved by `resolveFileIcon` (§2.1's table, rows
"Resolution order" / "Generic fallbacks" / "Mapping table source"). The
`.diff-file-icon` class keeps its sizing slot (14px, the stats-row precedent
tool-group.tsx:737).

---

## 3. Pure logic to port

- **`fileIconAssetPath` returns a root-absolute URL** — unit-test in
  `web/packages/app/tests/file-icons.test.ts`: every resolved path (light
  and dark) starts with `/file-icons/` and resolves identically at `/` and
  at `/chat/<id>` (no document-relative re-reading).
- **The resolver suite stays green** — the resolver tests already port the
  desktop's `file_icons.rs` suite (ticket 24, tests/file-icons.test.ts),
  named after the desktop tests (verbatim list):
  `exact_names_are_case_insensitive_and_ignore_parent_dots`,
  `compound_extensions_win_longest_first`,
  `folders_resolve_name_and_expansion_state`,
  `appearance_independence_and_hints_are_supported`,
  `symlinks_and_unknown_files_fall_back_cleanly`,
  `every_manifest_reference_is_embedded_and_every_asset_is_svg`,
  `complete_theme_bundle_is_registered`. Do not rewrite them.
- **The generator's root-paint assertion** (§2.2 step 3) is the ported shape
  of the desktop's bundle-floor guarantee (`every_manifest_reference_is_embedded_and_every_asset_is_svg`,
  file_icons.rs:460): every generated asset must paint. It runs at generation
  time (`pnpm --filter @roboco/icons check`), not in vitest.
- **DOM-level acceptance** (research S3(d) third row, verbatim): "at
  `/chat/<id>`, assert `img.files-row-icon` naturalWidth > 0" — a browser
  walk, not a unit test.

---

## 4. Gaps this ticket closes

Copied verbatim from research S3(d) (transcript-tool-calls-scroll.md) and the
`file-*` glyph rows of new-thread-background-and-transitions.md S3(d) (the
thumb-margin row is excluded — it is the settings Appearance row's own
polish, owned by this batch's settings-row ticket, not this one):

| item | kind | desktop value | web value | fix sketch |
| --- | --- | --- | --- | --- |
| Icon asset URL | WRONG VALUE | absolute asset namespace `file-icons/…` resolved by the AssetSource (file_icons.rs:18, :59-73) | relative `file-icons/…` — breaks on `/chat/$chatId` (lib/file-icons.ts:23, :135-138; file-icon.tsx:37-47) | `ASSET_PREFIX = "/file-icons/"` (or `` `${import.meta.env.BASE_URL}file-icons/` ``) |
| Changes pane file glyph | INVENTED | per-file polychrome icon (`file_icons::icon`) on file headers | monochrome `document` placeholder, path ignored (diff-view.tsx:572-581) | replace `FileGlyph` with `FileIcon kind="file"` |
| Verification blind spot | process | — | ticket 24 verified resolver STRINGS at the index route (issues/24:750), never the loaded `<img>` on the chat route | acceptance step: at `/chat/<id>`, assert `img.files-row-icon` naturalWidth > 0 |
| `fileImage` glyph | BROKEN | root-stroked asset, 1.5 stroke (crates/ui/assets/icons/file-image.svg) | generated body has no stroke; Icon sets no stroke (generated/index.ts:51; icons/index.tsx:34-53) | generator: carry root `stroke*` attrs onto every path (or emit them on the `<svg>`); regenerate; add a check that every asset's root paints |
| `fileCode` glyph | BROKEN | same | generated/index.ts:50 | same (blast radius: files/file-viewer.tsx:232) |
| `fileData`/`fileMarkdown`/`fileStyle` | BROKEN (latent) | same | generated/index.ts:52-53 | same |

(Line citations on the generated index are verified at HEAD: the family sits
at generated/index.ts:49-53, `fileImage` at :51.)

---

## 5. Do not

- **This ticket owns ONLY icons.** Do not touch the tool-chip icon→label
  margin or the jump pill (ticket 41), or the reveal baseline / store reset /
  height estimates / reservation (ticket 40) — coordinate by
  cross-reference, not by editing their seams.
- Do not re-litigate ticket 24's landed files-tree/search resolver spec
  (Status: done) — the resolution logic is correct and verified identical to
  the manifest; only the URL shape breaks. Likewise tickets 19/25's `FileIcon`
  call sites (file badges, stats rows, the editor breadcrumb) stay as they
  are.
- Do not regenerate the manifest or the `dark/` mirror —
  `generate-file-icons.mjs` and the baked public bundle are verified
  identical to the desktop (354 icons); the desktop's runtime 7-pair
  accent-lift table (file_icons.rs:20-38) is desktop-only.
- Do not fix the settings thumb's inner layout (the 34px img + 1px margin in
  `.background-tile-img`, app.css:10274-10280) — that is the settings
  Appearance row's own polish, owned by this batch's settings-row ticket
  (33's research S3(d) lists the row; 33's own Do-not defers it) — this
  ticket only makes the row's `fileImage` glyph paint. Likewise do not
  rebuild the row itself (title/meta/actions, appearance.rs:2184-2229).
- Do not convert `FileIcon` to a tinted SVG or `currentColor` — the img form
  is the point (authored polychrome fills, file-icon.tsx:5-14).
- Do not hand-edit `web/packages/icons/src/generated/index.ts` — regenerate
  via the script; the file is `@generated` and `pnpm --filter @roboco/icons
  check` gates drift.
- Desktop-only, do not port: gpui's composite `AssetSource` namespace and the
  load-time dark accent lift; the engine's static-serving internals
  (listener.rs) — the web fix is client-side URL shape only, no engine
  change.

---

## 6. Acceptance

- [ ] Open a chat on `/chat/<id>` — folder + language icons render: in the
      browser, a files-tree row's `img.files-row-icon` has `naturalWidth > 0`
      (the research's verification-blind-spot row), and the same holds in the
      mention popup, tool-chip file badges (tool-group.tsx:608), stats rows
      (:737), the file breadcrumb (file-viewer.tsx:205) and the markdown
      sole-file-link badge (markdown.tsx:500).
- [ ] Dark appearance: the icons load from `/file-icons/dark/…` on the same
      route.
- [ ] Settings → Appearance: the "New thread composer background" row shows
      a VISIBLE `fileImage` glyph in the 36px tile (previously invisible);
      the Files viewer header shows the `fileCode` glyph.
- [ ] Changes pane file headers show per-file polychrome icons resolved by
      path (a `.rs` file shows the rust icon, a `.tsx` the tsx icon) — the
      `FileGlyph` placeholder is deleted.
- [ ] `pnpm --filter @roboco/icons regenerate` produces stroke-carrying
      bodies for `fileCode`/`fileData`/`fileImage`/`fileMarkdown`/`fileStyle`;
      the generated diff shows only the stroke additions;
      `pnpm --filter @roboco/icons check` is green; the root-paint assertion
      rejects an asset whose root paints nothing (try it on a scratch copy).
- [ ] Unit tests: `fileIconAssetPath` returns root-absolute paths (light +
      dark); the ticket-24 resolver suite stays green.
- [ ] Screenshot pair, desktop vs web, states: (a) a chat open at
      `/chat/<id>` with the files tree showing folder + language icons;
      (b) the settings appearance background row with its tile glyph;
      (c) the Changes pane with two file headers (different languages);
      (d) the files viewer header.
- [ ] `pnpm -r build` green; `pnpm --filter @roboco/app test` green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)
