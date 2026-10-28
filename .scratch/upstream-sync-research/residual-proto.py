#!/usr/bin/env python3
"""Residual-mode translated cherry-pick (prototype, 2026-10-29 less-recoding research).

Same as `scripts/upstream/translate.py <sha>` (report only), plus two optional passes per
conflicted file:

1. residual: drop every upstream hunk Roboco already has (>= 80% of the hunk's
   significant added lines already in the Roboco file, and its removed lines already
   gone), then redo the 3-way merge. Conflicts on already-ported code disappear, so a
   "remainder" ticket only shows what is still missing.
2. --mergiraf PATH: if the file still conflicts and is Rust/TOML/Kotlin, try mergiraf
   (structural merge) and keep its output only when it resolves the whole file.

Writes nothing. Run from a Roboco checkout:
  python .scratch/upstream-sync-research/residual-proto.py 2e151ca65 [--target main] [--mergiraf C:/tmp/mergiraf.exe]
"""
import argparse
import collections
import difflib
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "scripts", "upstream"))
import upstream_common as uc  # noqa: E402

THRESH = 0.8


def _sig(line):
    t = line.strip()
    return len(t) >= 6 and any(ch.isalnum() for ch in t)


def residual_theirs(ours, base, theirs):
    """`theirs` with the hunks Roboco already carries reverted to `base`."""
    ol = ours.decode("utf-8", "replace").split("\n")
    bl = base.decode("utf-8", "replace").split("\n")
    tl = theirs.decode("utf-8", "replace").split("\n")
    have = collections.Counter(x.strip() for x in ol)
    out, dropped, kept = [], 0, 0
    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, bl, tl, autojunk=False).get_opcodes():
        if tag == "equal":
            out += bl[i1:i2]
            continue
        add = [x for x in tl[j1:j2] if _sig(x)]
        rem = [x for x in bl[i1:i2] if _sig(x)]
        present = sum(1 for x in add if have[x.strip()])
        gone = sum(1 for x in rem if not have[x.strip()])
        already = (add or rem) and (not add or present / len(add) >= THRESH) \
            and (not rem or gone / len(rem) >= THRESH)
        if already:
            out += bl[i1:i2]
            dropped += 1
        else:
            out += tl[j1:j2]
            kept += 1
    return "\n".join(out).encode("utf-8"), dropped, kept


def mergiraf(exe, path, base, ours, theirs):
    name = os.path.basename(path)
    with tempfile.TemporaryDirectory() as d:
        files = []
        for tag, data in (("base", base), ("ours", ours), ("theirs", theirs)):
            p = os.path.join(d, f"{tag}_{name}")
            with open(p, "wb") as fh:
                fh.write(data)
            files.append(p)
        out = os.path.join(d, "out")
        subprocess.run([exe, "merge", *files, "-o", out, "-p", path], capture_output=True, timeout=900)
        if not os.path.exists(out):
            return None
        with open(out, "rb") as fh:
            data = fh.read()
    return data if uc.count_conflicts(data)[0] == 0 else None


def main():
    uc.utf8_stdio()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("shas", nargs="+")
    ap.add_argument("--target")
    ap.add_argument("--mergiraf", help="path to a mergiraf binary (optional second pass)")
    args = ap.parse_args()
    repo = uc.Repo(".")
    renames, rules = uc.RenameMap(), uc.PathRules()
    for rev in args.shas:
        sha = repo.rev(rev)
        target = args.target or repo.default_target()
        res = uc.replay(repo, sha, target, renames, rules)
        before = res.classify()
        for f in res.files:
            if f.state != "conflict" or f.ours is None:
                continue
            t2, dropped, kept = residual_theirs(f.ours, f.base, f.theirs)
            merged, regions, lines = uc.merge3(f.ours, f.base, t2)
            how = "residual"
            if regions and args.mergiraf and f.path.endswith((".rs", ".toml", ".kt", ".kts")):
                m = mergiraf(args.mergiraf, f.path, f.base, f.ours, t2)
                if m is not None:
                    merged, regions, lines, how = m, 0, 0, "residual+mergiraf"
            print(f"    {f.path}: {f.regions}r -> {regions}r ({dropped} hunk(s) already in Roboco, "
                  f"{kept} still to port{', ' + how if not regions else ''})")
            f.regions, f.lines, f.merged = regions, lines, merged
            if not regions:
                f.state = "noop" if kept == 0 else "clean"
        print(f"{uc.short(sha)}  {before} -> {res.classify()}  {repo.subject(sha)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
