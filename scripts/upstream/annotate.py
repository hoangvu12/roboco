"""Why each remaining conflict region conflicts, and what to do about it.

For every conflict region translate.py leaves, this reads the three sides
(Roboco, upstream^, upstream) and says why they disagree:

- already      upstream's new lines here are all in the Roboco file
- prerequisite upstream^ code Roboco lacks came from an upstream commit the
               ledger has not ported yet (blame on the mirror)
- removed      upstream's side carries code Roboco removed or declined:
               cloud (edge, WorkOS, sync rooms, ...), iOS, the in-app browser,
               or a commit the ledger records as skipped/declined/superseded
- rename       the sides differ only by the zeron -> roboco rename
- ported       the upstream^ code came from a commit Roboco ported differently
- prefork      Roboco rewrote the pre-fork upstream code here
- roboco-only  Roboco-only lines sit where upstream changed the code
- drift        both sides carry the same code, ordered or formatted apart

Each region gets one hint (the first that applies, in that order) plus the
evidence. Hints are advice from text and blame, not proof: moved code blames
wrong. The result goes to a sidecar report (JSON + Markdown) outside the
working tree, never into the merged files.
"""
import json
import os
import re

import upstream_common as uc

HINTS = {
    "already": ("keep ours", "upstream's change here is already in Roboco"),
    "prerequisite": ("port the prerequisite first",
                     "upstream^ code here comes from a commit Roboco hasn't ported: port or include it, then re-run"),
    "removed": ("drop upstream's side", "upstream's side carries code Roboco removed or declined"),
    "rename": ("keep ours", "the sides differ only by the zeron -> roboco rename"),
    "ported": ("keep ours, re-apply by intent",
               "the upstream^ code was ported differently: re-express upstream's change in Roboco's version"),
    "prefork": ("keep ours, re-apply by intent",
                "Roboco rewrote this pre-fork upstream code: re-express upstream's change in Roboco's version"),
    "roboco-only": ("apply upstream's change onto ours",
                    "Roboco-only lines sit here: keep them and carry upstream's diff (upstream^ -> upstream) over"),
    "lacked": ("check for a missing prerequisite",
               "upstream^ code here isn't in Roboco and its origin is unknown (presence.py / git log -S)"),
    "drift": ("either side", "both sides carry the same code, ordered or formatted apart"),
}
ORDER = ("already", "prerequisite", "removed", "rename", "ported", "prefork", "roboco-only", "lacked", "drift")

# Code Roboco removed (ADR 0003/0004/0009, PR #39): a hint, matched per line.
REMOVED_PATTERNS = (
    ("cloud", re.compile(r"(?i)workos|\bedge_(?:token|url|client|session|origin)|\bedge::|edgeclient|"
                         r"\bbilling\b|\borg_id\b|\bcloud\b|sync_?room|cloud_?machine")),
    ("ios", re.compile(r"(?i)\bios\b|\bswift\b|xcode|testflight|\buikit\b")),
    ("browser", re.compile(r"(?i)\bbrowser::|in-app browser|webrtc|\bwebview\b")),
)


def norm(line):
    return line.strip()


def significant(line):
    t = norm(line)
    return len(t) >= 4 and not re.fullmatch(r"[\W_]*", t)


def unrename(line):
    """Fold the rename map's one-way token change so renamed lines compare equal."""
    return re.sub(r"roboco", "zeron", norm(line), flags=re.I).lower()


def parse_regions(data):
    """Conflict regions of merge output: [{start, end, ours, base, theirs}] (1-based lines).

    `base` is None for merge-style (no `|||||||` section) output.
    """
    out, cur, part = [], None, None
    for n, ln in enumerate(data.decode("utf-8", "replace").split("\n"), start=1):
        ln = ln.rstrip("\r")
        if ln.startswith("<<<<<<< "):
            cur, part = {"start": n, "end": n, "ours": [], "base": None, "theirs": []}, "ours"
        elif cur is None:
            continue
        elif ln.startswith("||||||| "):
            cur["base"], part = [], "base"
        elif ln == "=======" or ln.startswith("======= "):
            part = "theirs"
        elif ln.startswith(">>>>>>> "):
            cur["end"] = n
            out.append(cur)
            cur = None
        else:
            cur[part].append(ln)
    return out


class Context:
    """Ledger, fork point and blame for one upstream commit; everything cached and lazy."""

    def __init__(self, repo, sha, target, renames, blame=True):
        self.repo, self.sha, self.target, self.renames = repo, sha, target, renames
        self.do_blame = blame
        self._ledger = self._prefork = None
        self._blame = {}

    @property
    def ledger(self):
        if self._ledger is None:
            data = self.repo.blob(self.target, uc.LEDGER.replace(os.sep, "/"))
            try:
                self._ledger = uc.Ledger.parse(data.decode("utf-8")) if data else uc.Ledger()
            except uc.LedgerError:
                self._ledger = uc.Ledger()
        return self._ledger

    @property
    def prefork(self):
        if self._prefork is None:
            out = self.repo.git("rev-list", uc.FORK_POINT, check=False) if self.repo.rev(uc.FORK_POINT) else ""
            self._prefork = set(out.split())
        return self._prefork

    def blame(self, upath):
        """{normalized translated line: [origin sha, ...]} for the upstream file at sha^."""
        if not self.do_blame or not upath:
            return {}
        if upath not in self._blame:
            out = self.repo.git("blame", "--porcelain", "-w", f"{self.sha}^", "--", upath, check=False)
            found, cur = {}, None
            for ln in out.split("\n"):
                if re.match(r"^[0-9a-f]{40} \d+ \d+", ln):
                    cur = ln[:40]
                elif ln.startswith("\t") and cur:
                    found.setdefault(norm(self.renames.text_str(ln[1:])), []).append(cur)
            self._blame[upath] = found
        return self._blame[upath]

    def origin(self, sha):
        """(bucket, status, subject) of an upstream commit, from the fork point and the ledger."""
        if sha in self.prefork:
            return "prefork", "pre-fork", ""
        try:
            row = self.ledger.find(sha)
        except uc.LedgerError:
            row = None
        if row is None:
            return "unrecorded", "not in the ledger", ""
        st = row["status"]
        if st.startswith("ported"):
            kind = "ported"
        elif st in ("skipped:cloud",):
            kind = "cloud"
        elif st in ("skipped:ios", "android-lane"):
            kind = "ios"
        elif st.startswith("skipped:") or st in ("declined", "superseded"):
            kind = "declined"
        else:
            kind = "pending"
        return kind, st, row["subject"]


def removed_tags(lines):
    tags = set()
    for ln in lines:
        for tag, rx in REMOVED_PATTERNS:
            if rx.search(ln):
                tags.add(tag)
    return sorted(tags)


def analyze(ctx, f, diff3_data, upath_base):
    """Annotated regions of one conflicted file (diff3 output of the merge translate.py made)."""
    ours_file = {norm(x) for x in (f.ours or b"").decode("utf-8", "replace").split("\n")}
    up_file = {norm(x) for x in ((f.base or b"") + b"\n" + (f.theirs or b"")).decode("utf-8", "replace").split("\n")}
    out = []
    for rg in parse_regions(diff3_data):
        o, b, t = rg["ours"], rg["base"] or [], rg["theirs"]
        bset = {norm(x) for x in b}
        new_up = [x for x in t if significant(x) and norm(x) not in bset]
        gone_up = [x for x in b if significant(x) and norm(x) not in {norm(y) for y in t}]
        roboco_only = [x for x in o if significant(x) and norm(x) not in up_file]
        lacked = [x for x in b if significant(x) and norm(x) not in ours_file]
        reasons = {}
        if (new_up and all(norm(x) in ours_file for x in new_up)) or \
                (not new_up and gone_up and not any(norm(x) in ours_file for x in gone_up)):
            reasons["already"] = {}
        origins = {}
        blame = ctx.blame(upath_base) if lacked else {}
        unknown = 0
        for x in lacked:
            shas = blame.get(norm(x))
            if not shas:
                unknown += 1
                continue
            origins[shas[0]] = origins.get(shas[0], 0) + 1
        by_kind = {}
        for sha, n in sorted(origins.items(), key=lambda kv: -kv[1]):
            kind, status, subject = ctx.origin(sha)
            by_kind.setdefault(kind, []).append({"sha": uc.short(sha), "status": status,
                                                 "subject": subject[:80], "lines": n})
        lead = set()  # reasons strong enough to pick the action; weaker ones stay as evidence
        if "pending" in by_kind:
            reasons["prerequisite"] = {"commits": by_kind["pending"]}
            n = sum(c["lines"] for c in by_kind["pending"])
            if n >= 5 or n * 4 >= len(lacked):
                lead.add("prerequisite")
        tags = removed_tags(new_up + lacked)
        tagged = sum(1 for x in new_up + lacked if removed_tags([x]))
        declined = by_kind.get("cloud", []) + by_kind.get("ios", []) + by_kind.get("declined", [])
        if tags or declined:
            reasons["removed"] = {"kinds": tags, "commits": declined}
            if tagged * 2 >= len(new_up + lacked) or sum(c["lines"] for c in declined) * 2 >= max(len(lacked), 1):
                lead.add("removed")
        sig_o = [unrename(x) for x in o if significant(x)]
        sig_t = [unrename(x) for x in t if significant(x)]
        if sig_o and sig_o == sig_t and [norm(x) for x in o if significant(x)] != [norm(x) for x in t if significant(x)]:
            reasons["rename"] = {}
        if "ported" in by_kind:
            reasons["ported"] = {"commits": by_kind["ported"]}
        if "prefork" in by_kind:
            reasons["prefork"] = {"lines": sum(c["lines"] for c in by_kind["prefork"])}
        if roboco_only:
            reasons["roboco-only"] = {"lines": len(roboco_only), "sample": norm(roboco_only[0])[:100]}
        if unknown or "unrecorded" in by_kind:
            reasons["lacked"] = {"lines": unknown + sum(c["lines"] for c in by_kind.get("unrecorded", []))}
        if not reasons:
            reasons["drift"] = {}
        picks = [k for k in ORDER if k in reasons and (k not in ("prerequisite", "removed") or k in lead)]
        first = picks[0] if picks else next(k for k in ORDER if k in reasons)
        action, why = HINTS[first]
        out.append({"start": rg["start"], "end": rg["end"], "hint": first, "action": action, "why": why,
                    "reasons": reasons, "ours_lines": len(o), "upstream_lines": len(t),
                    "lacked_lines": len(lacked), "roboco_only_lines": len(roboco_only)})
    return out


def locate(regions, written):
    """Line numbers of the regions in the file as written (merge-style markers, CRLF, ...)."""
    spans = parse_regions(written) if written is not None else []
    if len(spans) != len(regions):
        for r in regions:
            r["start"] = r["end"] = None
        return regions
    for r, s in zip(regions, spans):
        r["start"], r["end"] = s["start"], s["end"]
    return regions


def reason_text(key, ev):
    if key == "prerequisite" or (key in ("ported", "removed") and ev.get("commits")):
        commits = ", ".join(f"{c['sha']} ({c['status']}, {c['lines']}l)" for c in ev["commits"][:3])
        kinds = f"{'/'.join(ev['kinds'])}; " if ev.get("kinds") else ""
        return f"{key}: {kinds}{commits}"
    if key == "removed":
        return f"removed: {'/'.join(ev['kinds'])}"
    if "lines" in ev:
        return f"{key}: {ev['lines']}l" + (f" e.g. {_code(ev['sample'])}" if ev.get("sample") else "")
    return key


# --- the sidecar report ---------------------------------------------------------------------


def report_dir(wt_repo):
    """`<git dir>/roboco-translate`: per worktree, never part of the tree, never committed."""
    p = wt_repo.git("rev-parse", "--path-format=absolute", "--git-path", "roboco-translate").strip()
    return p


def write(directory, record):
    os.makedirs(directory, exist_ok=True)
    stem = os.path.join(directory, uc.short(record["sha"]))
    with open(stem + ".json", "w", encoding="utf-8", newline="\n") as fh:
        json.dump(record, fh, indent=1)
        fh.write("\n")
    with open(stem + ".md", "w", encoding="utf-8", newline="\n") as fh:
        fh.write(markdown(record))
    return stem + ".md", stem + ".json"


def _cell(s):
    return str(s).replace("|", "\\|").replace("\n", " ")


def _code(s):
    """Inline code that survives backticks in the text."""
    s = str(s).replace("\n", " ")
    return f"`` {s} ``" if "`" in s else f"`{s}`"


def markdown(rec):
    out = [f"# translate.py: {uc.short(rec['sha'])} {rec['subject']}", "",
           f"- target: `{uc.short(rec['target'])}`; class **{rec['class']}**; options: "
           + (", ".join(rec.get("options", [])) or "none"),
           f"- {rec['summary']}",
           "- Hints come from text and blame on the mirror: advice, not proof. Resolve by intent "
           "(docs/reference/upstream-ports.md).", ""]
    conflicts = [f for f in rec["files"] if f.get("regions_detail")]
    if conflicts:
        out += ["## Conflicts", "",
                "| File | Lines | Action | Why | Evidence |", "|---|---|---|---|---|"]
        for f in conflicts:
            for r in f["regions_detail"]:
                lines = f"{r['start']}-{r['end']}" if r["start"] else "?"
                ev = "; ".join(reason_text(k, v) for k, v in r["reasons"].items())
                out.append(f"| `{f['path']}` | {lines} | {_cell(r['action'])} | {_cell(r['why'])} | {_cell(ev)} |")
        out.append("")
        if any(f.get("residual") for f in conflicts):
            out += ["Residual files: where a hunk was already in Roboco, the upstream side of a conflict shows "
                    "the upstream^ text. Don't take upstream's side wholesale there.", ""]
    other = [f for f in rec["files"] if f["state"] == "conflict" and not f.get("regions_detail")]
    if other:
        out += ["Other conflicts: " + ", ".join(f"`{f['path']}` ({f['reason'] or 'binary/delete'})" for f in other), ""]
    dropped = [f for f in rec["files"] if f.get("residual") and f["residual"]["dropped"]]
    if dropped:
        out += ["## Residual mode: hunks skipped as already in Roboco", "",
                "Check these: each was reverted to upstream^ before merging. `lacking` lists significant "
                "lines the hunk adds that Roboco doesn't have (only with --residual-threshold below 1).", ""]
        for f in dropped:
            rs = f["residual"]
            out.append(f"- `{f['path']}`: {len(rs['dropped'])} skipped, {rs['kept']} still to port"
                       + ("" if rs["used"] else f" (residual merge not used: {rs['why']})"))
            for h in rs["dropped"]:
                a, b = h["upstream_lines"]
                where = f"upstream lines {a}-{b}" if a <= b else "upstream^ lines {}-{} (removed)".format(
                    *h["base_lines"])
                lack = f"; lacking: {', '.join(_code(x[:60]) for x in h['lacking'][:3])}" if h["lacking"] else ""
                out.append(f"  - {where} (+{h['added']}/-{h['removed']}): {_code(h['first'])}{lack}")
        out.append("")
    dups = [f for f in rec["files"] if f.get("duplicates")]
    if dups:
        out += ["## Possible duplicates in clean merges", "",
                "Git merged these hunks without a conflict, but Roboco already has them elsewhere in the file, "
                "so the merge may carry two copies. Check each one and delete the extra copy (report only: "
                "nothing was changed for it).", ""]
        for f in dups:
            for h in f["duplicates"]:
                a, b = h["upstream_lines"]
                doubled = ", ".join(_code(x[:60]) for x in h["doubled"][:3])
                out.append(f"- `{f['path']}`: upstream lines {a}-{b}: {_code(h['first'])}; now doubled: {doubled}")
        out.append("")
    mg = [f for f in rec["files"] if "mergiraf" in f.get("how", "")]
    if mg:
        out += ["## Resolved by mergiraf (review each)", ""]
        out += [f"- `{f['path']}`" for f in mg]
        out.append("")
    locks = [f for f in rec["files"] if f.get("lock")]
    if locks:
        out += ["## Cargo.lock: regenerate, don't merge", ""]
        for f in locks:
            out.append(f"- `{f['path']}` kept as Roboco's. " + " ".join(f["lock"]["describe"]))
            out += [f"  - `{c}`" for c in f["lock"]["commands"]]
            for cmd, ok, err in f["lock"].get("ran", []):
                out.append(f"  - ran `{cmd}`: {'ok' if ok else 'FAILED ' + err}")
        out.append("")
    missing = [f for f in rec["files"] if f["state"] == "missing"]
    if missing:
        out += ["## No Roboco file", ""]
        out += [f"- `{f['path']}`: {f['reason']}" for f in missing]
        out.append("")
    return "\n".join(out)
