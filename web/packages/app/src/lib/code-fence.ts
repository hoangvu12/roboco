import { EXTENSION_TO_FILE_FORMAT, getFiletypeFromFileName, type SupportedLanguages } from "@pierre/diffs";

/**
 * The transcript code fence's language adapter onto the diffs library
 * (web-pierre-adoption, ticket 05) — a fence's info string is freeform,
 * user-typed text ("Rust", "python", "js", prose), while the library's
 * `FileContents.lang` must name a language the shared Shiki highlighter
 * can resolve; an unresolvable id leaves the block blank (the highlighter
 * promise rejects and the renderer never mounts its body).
 *
 * The oracle is the library's own extension table: every VALUE it maps to
 * is a bundled language id, and its extension lookup resolves fence-style
 * aliases ("js" → javascript, "bash" → zsh, "py" → python). A tiny alias
 * table covers the labels the old tokenizer supported that neither path
 * knows. Everything else — prose in the info string, an unknown label —
 * degrades to `"text"` and renders plain, exactly the shape the library
 * itself falls back to for unknown file extensions.
 */

/** Language ids the library's extension table ships (its values). */
const BUNDLED_LANGUAGE_IDS = new Set(Object.values(EXTENSION_TO_FILE_FORMAT));

/**
 * Fence-only aliases the old tokenizer's language set covered but the
 * extension paths don't: a shell fence reads like the bash/zsh grammar,
 * and SVG is XML markup (the old tokenizer ran it through its markup
 * path).
 */
const FENCE_ALIASES: Readonly<Record<string, SupportedLanguages>> = {
  shell: "zsh",
  svg: "xml",
};

/**
 * Normalize a fence's info string to a language the library can load: the
 * first word, lowercased (fence labels are conventionally the language,
 * optionally followed by attributes like `rust,ignore` or `js title=…`),
 * resolved through the bundled ids, the extension oracle, then the alias
 * table — `"text"` when nothing matches.
 */
export function codeFenceLanguage(label: string | null): SupportedLanguages {
  const normalized = (label ?? "").trim().toLowerCase();
  const first = normalized.split(/[\s,]+/, 1)[0] ?? "";
  if (first === "" || first === "text" || first === "ansi") {
    return "text";
  }
  if (BUNDLED_LANGUAGE_IDS.has(first)) {
    return first;
  }
  const byExtension = getFiletypeFromFileName(`f.${first}`);
  if (byExtension !== "text") {
    return byExtension;
  }
  return FENCE_ALIASES[first] ?? "text";
}
