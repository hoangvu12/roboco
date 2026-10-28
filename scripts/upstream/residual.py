"""Residual mode: drop the upstream hunks Roboco already carries before merging.

Many upstream commits are remainders of an earlier partial port: replaying the
whole commit re-conflicts on everything already ported. Residual mode splits
the upstream change of one file (upstream^ -> upstream, both translated) into
hunks, reverts every hunk Roboco provably has back to upstream^, and redoes the
3-way merge, so only what is still missing is left to merge.

Conservative by design (the 2026-10-29 research prototype used "80% of the
added lines appear anywhere in the file", which can drop real code):

- Only significant lines count as evidence (>= 6 chars after strip, with a
  letter or digit). A hunk with no significant line is never dropped.
- Counts are multiplicity-aware: an added line is present only when Roboco has
  at least as many copies of it as upstream has after the commit, and a
  removed line is gone only when Roboco has no more copies than upstream after
  the commit. A line repeated across the file can't vouch for a second copy.
- By default every significant added line must be present and every
  significant removed line gone (threshold 1.0). A lower threshold drops more,
  and the lines it drops without evidence are reported as `lacking`.
- Per file, the residual merge is used only when it is no worse than the plain
  merge (fewer conflict regions, or as many and no more conflict lines).

Stdlib only; used by translate.py.
"""
import collections
import difflib

SIG_MIN_CHARS = 6
DEFAULT_THRESHOLD = 1.0


def significant(line):
    t = line.strip()
    return len(t) >= SIG_MIN_CHARS and any(ch.isalnum() for ch in t)


class Hunk:
    """One upstream change in a file: base[i1:i2] -> theirs[j1:j2] (0-based, end exclusive)."""
    __slots__ = ("i1", "i2", "j1", "j2", "added", "removed", "present", "gone", "lacking", "kept_removed",
                 "dropped")

    def __init__(self, i1, i2, j1, j2):
        self.i1, self.i2, self.j1, self.j2 = i1, i2, j1, j2
        self.added = self.removed = self.present = self.gone = 0
        self.lacking = []        # significant added lines Roboco doesn't have (enough copies of)
        self.kept_removed = []   # significant removed lines Roboco still has
        self.dropped = False

    def label(self, theirs_lines, base_lines):
        """First significant line of the change, for reports."""
        for ln in list(theirs_lines[self.j1:self.j2]) + list(base_lines[self.i1:self.i2]):
            if significant(ln):
                return ln.strip()[:100]
        return ""

    def as_dict(self, theirs_lines, base_lines):
        return {"upstream_lines": [self.j1 + 1, self.j2], "base_lines": [self.i1 + 1, self.i2],
                "added": self.added, "removed": self.removed, "first": self.label(theirs_lines, base_lines),
                "lacking": self.lacking, "kept_removed": self.kept_removed}


class Residual:
    """Result of filtering one file: the residual `theirs` and the per-hunk verdicts."""

    def __init__(self, theirs, hunks, base_lines, theirs_lines):
        self.theirs = theirs
        self.hunks = hunks
        self._base, self._theirs = base_lines, theirs_lines

    @property
    def dropped(self):
        return [h for h in self.hunks if h.dropped]

    @property
    def kept(self):
        return [h for h in self.hunks if not h.dropped]

    def dropped_dicts(self):
        return [h.as_dict(self._theirs, self._base) for h in self.dropped]


def _decode(data):
    return data.decode("utf-8").split("\n")


def residual_theirs(ours, base, theirs, threshold=DEFAULT_THRESHOLD):
    """`theirs` with the hunks Roboco already carries reverted to `base`.

    Returns a Residual, or None when the file can't be filtered (not UTF-8).
    """
    try:
        ol, bl, tl = _decode(ours), _decode(base), _decode(theirs)
    except UnicodeDecodeError:
        return None
    have = collections.Counter(x.strip() for x in ol)
    after = collections.Counter(x.strip() for x in tl)
    out, hunks = [], []
    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, bl, tl, autojunk=False).get_opcodes():
        if tag == "equal":
            out += bl[i1:i2]
            continue
        h = Hunk(i1, i2, j1, j2)
        add = [x.strip() for x in tl[j1:j2] if significant(x)]
        rem = [x.strip() for x in bl[i1:i2] if significant(x)]
        h.added, h.removed = len(add), len(rem)
        h.lacking = [x for x in add if have[x] < after[x]]
        h.kept_removed = [x for x in rem if have[x] > after[x]]
        h.present = len(add) - len(h.lacking)
        h.gone = len(rem) - len(h.kept_removed)
        h.dropped = bool(add or rem) \
            and (not add or h.present / len(add) >= threshold) \
            and (not rem or h.gone / len(rem) >= threshold)
        out += bl[i1:i2] if h.dropped else tl[j1:j2]
        hunks.append(h)
    return Residual("\n".join(out).encode("utf-8"), hunks, bl, tl)


def duplicates(ours, base, theirs, merged):
    """Hunks a clean merge may have doubled: report-only, for files git merged without conflict.

    When Roboco ported an upstream change somewhere else in the file, git can
    merge upstream's copy cleanly next to it. A hunk is flagged when Roboco
    already has all of it (the strict residual rule) and the merge left more
    copies of some of its lines than either Roboco or upstream has. Returns
    hunk dicts with a `doubled` list; empty when nothing looks doubled.
    """
    try:
        ol, tl, ml = _decode(ours), _decode(theirs), _decode(merged)
    except UnicodeDecodeError:
        return []
    have = collections.Counter(x.strip() for x in ol)
    after = collections.Counter(x.strip() for x in tl)
    now = collections.Counter(x.strip() for x in ml)
    if not any(significant(x) and n > max(have[x], after[x]) for x, n in now.items()):
        return []  # cheap exit: no line got more copies than either side has
    r = residual_theirs(ours, base, theirs)
    if r is None:
        return []
    out = []
    for h in r.dropped:
        doubled = [x.strip() for x in r._theirs[h.j1:h.j2]
                   if significant(x) and now[x.strip()] > max(have[x.strip()], after[x.strip()])]
        if doubled:
            d = h.as_dict(r._theirs, r._base)
            d["doubled"] = list(dict.fromkeys(doubled))
            out.append(d)
    return out


def no_worse(before, after):
    """Use the residual merge only when it isn't worse: (regions, lines) pairs."""
    (r0, l0), (r1, l1) = before, after
    return r1 < r0 or (r1 == r0 and l1 <= l0)
