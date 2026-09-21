/**
 * The picker popover's ranked-substring filter — line-for-line port of the
 * desktop's `popover::match_rank` + `filter_indices` (`crates/ui/src/popover.rs`).
 *
 * The desktop lists search results with prefixes sorted ahead of substrings so
 * typing "gpt-4o" surfaces the "gpt-4o" hit before any long label that happens
 * to contain the same characters. Case-insensitive.
 */

/**
 * -1 = no match; 0 = prefix match; positive = substring (smaller ranks hit
 * closer to the start of the haystack — the desktop's secondary ordering).
 */
export function matchRank(text: string, query: string): number {
  if (query.length === 0) {
    return 0;
  }
  const haystack = text.toLowerCase();
  const needle = query.toLowerCase();
  if (haystack.startsWith(needle)) {
    return 0;
  }
  const at = haystack.indexOf(needle);
  if (at < 0) {
    return -1;
  }
  return 1 + at;
}

export interface RankedMatch {
  /** Index into the source array. */
  readonly index: number;
  /** 0 = prefix; positive = substring (closer to 0 wins). */
  readonly rank: number;
}

/** Keep only the matching items, in ranked order. Stable on ties. */
export function filterAndSort<T>(
  items: readonly T[],
  label: (item: T) => string,
  query: string,
): readonly T[] {
  if (query.length === 0) {
    return items;
  }
  const matches: { item: T; index: number; rank: number }[] = [];
  for (let ix = 0; ix < items.length; ix += 1) {
    const item = items[ix];
    if (item === undefined) {
      continue;
    }
    const rank = matchRank(label(item), query);
    if (rank >= 0) {
      matches.push({ item, index: ix, rank });
    }
  }
  matches.sort((a, b) => (a.rank - b.rank) || (a.index - b.index));
  return matches.map((match) => match.item);
}
