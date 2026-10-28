#!/usr/bin/env python3
"""Rename-aware cherry-pick of upstream zeron commits onto Roboco.

For each upstream commit: list its files (renames detected), drop the ones
path-rules.tsv excludes (iOS, edge, landing, upstream Android, ...), map
paths and text through rename-map.tsv (the AGENTS.md rename map), then
3-way merge each file: ours = the Roboco file, base/theirs = the translated
upstream file before/after the commit. That is `git cherry-pick` against an
already-renamed upstream, done per file in memory.

Each commit is reported CLEAN / TRIVIAL / REAL (also AUTO-SKIP when every
file is excluded, ALREADY-IN when Roboco already matches), with per-file
states: CLEAN, ADDED, DELETED, NOOP, CONFLICT <regions>r/<lines>l,
MISSING (no Roboco file to land on), SKIPPED <reason>.
TRIVIAL = no missing targets, and conflicts only in Cargo.lock or at most
3 regions / 30 lines in total.

Default is a report: nothing is written. `--apply --worktree DIR` writes
the merge (conflict markers included) into a Roboco worktree; `--commit`
also commits a CLEAN result with an `Upstream-Commit:` trailer. The mirror
branch is never written: apply refuses a worktree on zeron/main or on any
commit of upstream history.

Examples:
  python scripts/upstream/translate.py 85006f4b4
  python scripts/upstream/translate.py 85006f4b4 4f465702d --target main --quiet
  python scripts/upstream/translate.py 4f465702d --apply --worktree ../roboco-batch --commit
  python scripts/upstream/translate.py --sample sample.tsv --out results.tsv
"""
import argparse
import difflib
import json
import os
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import upstream_common as uc  # noqa: E402


def commit_message(renames, subject, sha):
    subject = renames.text_str(subject)
    subject = re.sub(r"\(#(\d+)\)\s*$", r"(zeron#\1)", subject)
    return f"{subject}\n\nUpstream-Commit: {sha}\n"


def same_name_hints(repo, target, path, cache):
    """Roboco files with the same basename as a missing target (moved files)."""
    if "names" not in cache:
        names = {}
        for p in repo.git("ls-tree", "-r", "--name-only", target).splitlines():
            names.setdefault(p.rsplit("/", 1)[-1], []).append(p)
        cache["names"] = names
    hits = cache["names"].get(path.rsplit("/", 1)[-1], [])
    return [h for h in hits if h != path][:3]


def report(repo, res, subject, quiet, cache):
    cls = res.classify()
    print(f"{uc.short(res.sha)}  {cls:<10} {subject}")
    print(f"           {res.summary()}")
    if quiet:
        return
    for f in res.files:
        extra = ""
        if f.state == "missing":
            hints = same_name_hints(repo, res.target, f.path, cache)
            if hints:
                extra = "  (same name: " + ", ".join(hints) + ")"
        moved = f"  (from {f.old_path})" if f.old_path else ""
        print(f"    {f.label():<28} {f.path}{moved}{extra}")


def as_json(res, subject):
    return {
        "sha": res.sha, "target": res.target, "subject": subject, "class": res.classify(),
        "files": [{"path": f.path, "upstream_path": f.upath, "status": f.status, "state": f.state,
                   "reason": f.reason, "regions": f.regions, "lines": f.lines,
                   "from": f.old_path} for f in res.files],
    }


# --- apply -------------------------------------------------------------------------------


def check_worktree(wt, allow_dirty):
    repo = uc.Repo(wt)
    try:
        top = repo.toplevel()
    except uc.GitError as e:
        sys.exit(f"--worktree: {e}")
    branch = repo.git("rev-parse", "--abbrev-ref", "HEAD").strip()
    if branch == uc.MIRROR or branch.endswith("/" + uc.MIRROR):
        sys.exit(f"refusing to write: {top} is on the mirror branch {uc.MIRROR}")
    if repo.rev(uc.MIRROR) and is_ancestor(repo, "HEAD", uc.MIRROR):
        sys.exit(f"refusing to write: HEAD of {top} is upstream history (an ancestor of {uc.MIRROR})")
    if not allow_dirty and repo.git("status", "--porcelain", "--untracked-files=no").strip():
        sys.exit(f"refusing to write: {top} has uncommitted changes (commit them or pass --allow-dirty)")
    return repo, top


def is_ancestor(repo, a, b):
    r = subprocess.run(["git", "-C", repo.path, "merge-base", "--is-ancestor", a, b], capture_output=True)
    return r.returncode == 0


def write_file(top, rel, data):
    path = os.path.join(top, *rel.split("/"))
    if os.path.exists(path):
        with open(path, "rb") as fh:
            if b"\r\n" in fh.read(65536) and not uc.is_binary(data):
                data = re.sub(rb"(?<!\r)\n", b"\r\n", data)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(data)


def remove_file(top, rel):
    path = os.path.join(top, *rel.split("/"))
    if os.path.exists(path):
        os.remove(path)


def apply(repo, wt_repo, top, res, style, renames, do_commit, subject):
    cls = res.classify()
    written, removed, execs = [], [], []
    for f in res.files:
        if f.state in ("clean", "added", "conflict") and f.merged is not None:
            data = f.merged
            if f.state == "conflict" and style == "diff3":
                data, _, _ = uc.merge3(f.ours, f.base, f.theirs, style="diff3")
            write_file(top, f.path, data)
            written.append(f.path)
            if f.mode == "100755" and f.status in "AR":
                execs.append(f.path)
            if f.old_path:
                remove_file(top, f.old_path)
                removed.append(f.old_path)
        elif f.state == "deleted":
            remove_file(top, f.path)
            removed.append(f.path)
    msg = commit_message(renames, subject, res.sha)
    print(f"  wrote {len(written)} file(s), removed {len(removed)} in {top}")
    conflicted = [f.path for f in res.files if f.state == "conflict"]
    missing = [f.path for f in res.files if f.state == "missing"]
    if conflicted:
        print("  resolve conflict markers in: " + ", ".join(conflicted))
    if missing:
        print("  no Roboco file (port by hand or drop): " + ", ".join(missing))
    if execs:
        print("  new executable(s): " + ", ".join(execs))
    if not do_commit:
        print("  commit message:\n" + "".join("    " + ln + "\n" for ln in msg.splitlines()))
        return
    if cls not in ("CLEAN",):
        print(f"  not committing a {cls} result; resolve, review, then commit with the message above")
        print("  commit message:\n" + "".join("    " + ln + "\n" for ln in msg.splitlines()))
        return
    paths = written + removed
    wt_repo.git("add", "-A", "--", *paths)
    for p in execs:
        wt_repo.git("update-index", "--chmod=+x", "--", p)
    with tempfile.NamedTemporaryFile("w", delete=False, encoding="utf-8", suffix=".txt") as fh:
        fh.write(msg)
        name = fh.name
    try:
        wt_repo.git("commit", "-q", "-F", name)
    finally:
        os.unlink(name)
    print(f"  committed {wt_repo.git('rev-parse', '--short=9', 'HEAD').strip()}: {msg.splitlines()[0]}")


# --- research sample (the 30-commit prototype run) ------------------------------------------


def diff_lines(a, b):
    a = (a or b"").decode("utf-8", "replace").splitlines()
    b = (b or b"").decode("utf-8", "replace").splitlines()
    out = set()
    for ln in difflib.unified_diff(a, b, lineterm="", n=0):
        if ln[:1] in "+-" and not ln.startswith(("+++", "---")) and ln[1:].strip():
            out.add(ln[0] + ln[1:].strip())
    return out


def score_against_port(repo, res, target, port):
    """Share of a real port's changed lines (outside .scratch/) the pipeline reproduced."""
    merged = {}
    for f in res.files:
        if f.state in ("clean", "added", "conflict"):
            merged[f.path] = f.merged
        elif f.state == "deleted":
            merged[f.path] = None
    files = [p for p in repo.git("diff-tree", "-r", "--no-commit-id", "--name-only", target, port).splitlines()
             if not p.startswith(".scratch/")]
    hit = tot = extra = 0
    for p in files:
        real = diff_lines(repo.blob(target, p), repo.blob(port, p))
        tot += len(real)
        if p in merged:
            ours = diff_lines(repo.blob(target, p), merged[p])
            hit += len(real & ours)
            extra += len(ours - real)
    return hit, tot, extra


def run_sample(repo, args, renames, rules):
    keys = ["sha", "label", "subject", "raw", "paths", "full", "files", "skipped", "clean",
            "noop", "conflicted", "regions", "clines", "missing", "why", "port_recall",
            "pipeline_extra", "detail"]
    rows = []
    with open(args.sample, encoding="utf-8") as fh:
        lines = [ln.rstrip("\n").split("\t") for ln in fh if ln.strip() and not ln.startswith("#")]
    for cols in lines:
        sha, target, label = cols[0], cols[1], cols[2]
        port = cols[3] if len(cols) > 3 and cols[3] else None
        subject = repo.subject(sha)
        out = {"sha": sha[:9], "label": label, "subject": subject[:70]}
        for mode in ("raw", "paths", "full"):
            res = uc.replay(repo, sha, target, renames, rules, mode=mode)
            out[mode] = res.classify()
            if mode != "full":
                continue
            out.update(files=len(res.files), skipped=res.count("skipped"),
                       clean=res.count("clean") + res.count("added") + res.count("deleted"),
                       noop=res.count("noop"), conflicted=res.count("conflict"),
                       regions=res.regions, clines=res.lines, missing=res.count("missing"),
                       why=",".join(sorted({f.reason for f in res.files if f.state == "skipped"})),
                       detail="; ".join([f"{f.path} ({f.regions}r/{f.lines}l)" for f in res.files
                                         if f.state == "conflict"]
                                        + [f"MISSING {f.path}" for f in res.files if f.state == "missing"])[:400])
            if port:
                hit, tot, extra = score_against_port(repo, res, target, port)
                out["port_recall"] = f"{hit}/{tot}"
                out["pipeline_extra"] = extra
        rows.append(out)
        print(f"{out['sha']} {out['raw']:>10} {out['paths']:>10} {out['full']:>10}  "
              f"f={out['files']} s={out['skipped']} c={out['clean']} x={out['conflicted']}"
              f"/{out['regions']}r/{out['clines']}l m={out['missing']} "
              f"{out.get('port_recall', '')}  {subject[:50]}", flush=True)
    if args.out:
        with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\t".join(keys) + "\n")
            for o in rows:
                fh.write("\t".join(str(o.get(k, "")) for k in keys) + "\n")
    totals = {m: {} for m in ("raw", "full")}
    for o in rows:
        for m in totals:
            totals[m][o[m]] = totals[m].get(o[m], 0) + 1
    print("totals  plain cherry-pick:", totals["raw"], " with rename map:", totals["full"])


def main(argv=None):
    uc.utf8_stdio()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("shas", nargs="*", help="upstream commits (any rev git understands)")
    ap.add_argument("--repo", default=".", help="repository to read objects from (default: cwd)")
    ap.add_argument("--target", help="Roboco rev to merge onto (default: the worktree's HEAD with "
                                     "--apply, else main, else origin/main)")
    ap.add_argument("--mode", choices=("full", "paths", "raw"), default="full",
                    help="full = paths + text renames (default); paths = paths only; raw = plain cherry-pick")
    ap.add_argument("--quiet", action="store_true", help="one summary per commit, no per-file lines")
    ap.add_argument("--json", action="store_true", help="print JSON instead of text")
    ap.add_argument("--apply", action="store_true", help="write the merge into --worktree (else report only)")
    ap.add_argument("--worktree", help="Roboco worktree to write into (with --apply)")
    ap.add_argument("--conflict-style", choices=("merge", "diff3"), default="diff3",
                    help="conflict markers written by --apply (default diff3: shows the upstream base)")
    ap.add_argument("--commit", action="store_true",
                    help="with --apply, commit a CLEAN result with an Upstream-Commit: trailer")
    ap.add_argument("--allow-dirty", action="store_true", help="with --apply, allow uncommitted changes")
    ap.add_argument("--sample", help="research mode: TSV of upstream_sha, target_rev, label[, port_sha]")
    ap.add_argument("--out", help="research mode: write the results TSV here")
    args = ap.parse_args(argv)

    renames, rules = uc.RenameMap(), uc.PathRules()
    repo = uc.Repo(args.repo)
    if args.sample:
        run_sample(repo, args, renames, rules)
        return 0
    if not args.shas:
        ap.error("give at least one upstream commit (or --sample)")
    if args.commit and not args.apply:
        ap.error("--commit needs --apply")
    wt_repo = top = None
    if args.apply:
        if not args.worktree:
            ap.error("--apply needs --worktree")
        if len(args.shas) > 1 and not args.commit:
            ap.error("--apply without --commit takes one commit at a time")
        if args.target:
            ap.error("--target can't be combined with --apply (the worktree's HEAD is the target)")
        wt_repo, top = check_worktree(args.worktree, args.allow_dirty)
        repo = wt_repo  # worktrees share objects; read through the one we write
    results, cache = [], {}
    for rev in args.shas:
        sha = repo.rev(rev)
        if not sha:
            sys.exit(f"unknown commit {rev}")
        target = wt_repo.rev("HEAD") if wt_repo else (args.target or repo.default_target())
        if not repo.rev(target):
            sys.exit(f"unknown target {target}")
        subject = repo.subject(sha)
        res = uc.replay(repo, sha, target, renames, rules, mode=args.mode, style="merge")
        if args.json:
            results.append(as_json(res, subject))
        else:
            report(repo, res, subject, args.quiet, cache)
        if wt_repo:
            apply(repo, wt_repo, top, res, args.conflict_style, renames, args.commit, subject)
            if args.commit and res.classify() != "CLEAN" and len(args.shas) > 1:
                sys.exit("stopping: resolve this commit before applying the next one")
    if args.json:
        print(json.dumps(results, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
