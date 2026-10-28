#!/usr/bin/env python3
"""Rename-translation prototype: replay an upstream zeron commit onto Roboco.

Throwaway research script. For each upstream commit U and a Roboco target rev T:

  1. List the files U changed (first parent, rename detection on).
  2. Drop files on the standing skip list (iOS, landing, edge, upstream Android...).
  3. Map each upstream path to its Roboco path (apps/zeron -> apps/roboco, ...).
  4. Translate the upstream file text before and after U through the AGENTS.md
     rename map (zeron -> roboco, ZERON_ -> ROBOCO_, sh.zeron -> sh.roboco, ...).
  5. Three-way merge per file: ours = Roboco file at T, base = translated U^ file,
     theirs = translated U file (`git merge-file`). This is what
     `git cherry-pick` would do if the upstream history were already renamed.

Modes (ablation): raw = no path map, no text map (a plain cherry-pick);
paths = path map only; full = path map + text map.

Nothing is written to the repository: blobs are read with `git cat-file --batch`
and merges run on temp files.

Usage:
  python translate_proto.py --repo <roboco> --sample sample.tsv --out results.tsv
  sample.tsv columns: upstream_sha <TAB> target_rev <TAB> label [<TAB> port_sha]
  (port_sha: the real Roboco port commit, used to score how much of it the
  pipeline reproduces; target_rev is then normally port_sha^)
"""
import argparse, os, re, subprocess, sys, tempfile

# --- the rename map (AGENTS.md) -------------------------------------------------

SKIP_PREFIXES = [
    ("apps/ios/", "ios"), ("apps/landing/", "landing"), ("apps/www-redirect/", "landing"),
    ("edge/", "cloud"), ("apps/android/", "upstream-android"),
    ("crates/zatex/", "declined"),
    ("README.es.md", "docs"), ("README.ja.md", "docs"), ("README.ko.md", "docs"),
]

PATH_MAP = [  # applied in order, first prefix match wins, then the generic rename
    ("apps/zeron/", "apps/roboco/"),
    ("crates/mcp/", "crates/roboco-mcp/"),
    ("crates/client/", "crates/mobile-client/"),  # low confidence: different design
]

PROTECT = ["zeronsh", "ZERON_GPU_STATS", "zeron/main"]


def map_path(p, mode):
    if mode == "raw":
        return p
    for a, b in PATH_MAP:
        if p.startswith(a):
            p = b + p[len(a):]
            break
    if mode == "full" or mode == "paths":
        p = p.replace("zeron", "roboco").replace("Zeron", "Roboco")
    return p


def skip_reason(p):
    for pre, why in SKIP_PREFIXES:
        if p.startswith(pre):
            return why
    return None


def translate(data, mode):
    if mode != "full" or b"\0" in data[:8000]:
        return data
    try:
        s = data.decode("utf-8")
    except UnicodeDecodeError:
        return data
    for i, tok in enumerate(PROTECT):
        s = s.replace(tok, f"\x00P{i}\x00")
    s = re.sub(r"(?<![A-Z_])CURSOR_SDK_SHIM_EXECUTABLE", "ROBOCO_CURSOR_SDK_SHIM_EXECUTABLE", s)
    s = s.replace("ZERON", "ROBOCO").replace("Zeron", "Roboco").replace("zeron", "roboco")
    for i, tok in enumerate(PROTECT):
        s = s.replace(f"\x00P{i}\x00", tok)
    return s.encode("utf-8")


# --- git plumbing ---------------------------------------------------------------

class Repo:
    def __init__(self, path):
        self.path = path
        self.cat = subprocess.Popen(["git", "-C", path, "cat-file", "--batch"],
                                    stdin=subprocess.PIPE, stdout=subprocess.PIPE)

    def git(self, *args):
        return subprocess.run(["git", "-C", self.path, *args], capture_output=True,
                              check=True).stdout.decode("utf-8", "replace")

    def blob(self, rev, path):
        self.cat.stdin.write(f"{rev}:{path}\n".encode())
        self.cat.stdin.flush()
        header = self.cat.stdout.readline().decode()
        if header.endswith("missing\n"):
            return None
        size = int(header.split()[2])
        data = self.cat.stdout.read(size)
        self.cat.stdout.read(1)
        return data

    def changes(self, sha):
        out = self.git("diff-tree", "-r", "-M", "--no-commit-id", "--name-status",
                       f"{sha}^", sha)
        rows = []
        for line in out.splitlines():
            parts = line.split("\t")
            st = parts[0][0]
            if st == "R":
                rows.append((st, parts[1], parts[2]))
            else:
                rows.append((st, parts[1], parts[1]))
        return rows


def merge3(ours, base, theirs):
    """Return (merged_bytes, conflict_regions, conflict_lines)."""
    with tempfile.TemporaryDirectory() as d:
        fo, fb, ft = (os.path.join(d, n) for n in ("ours", "base", "theirs"))
        for f, b in ((fo, ours), (fb, base), (ft, theirs)):
            with open(f, "wb") as h:
                h.write(b)
        r = subprocess.run(["git", "merge-file", "-p", *ALGO, fo, fb, ft], capture_output=True)
        if r.returncode < 0:
            raise RuntimeError(r.stderr)
        merged = r.stdout
    regions = lines = 0
    inside = False
    for ln in merged.split(b"\n"):
        if ln.startswith(b"<<<<<<< "):
            regions += 1
            inside = True
        elif ln.startswith(b">>>>>>> "):
            inside = False
        elif inside and not ln.startswith(b"======="):
            lines += 1
    return merged, regions, lines


TRIVIAL_FILES = ("Cargo.lock",)
ALGO = [a for a in os.environ.get("MERGE_ALGO", "").split() if a]


def replay(repo, sha, target, mode):
    res = dict(files=0, skipped=0, clean=0, noop=0, conflicted=0, regions=0,
               clines=0, missing=0, missing_paths=[], conflict_paths=[], merged={},
               skip_why=set())
    for st, src, dst in repo.changes(sha):
        res["files"] += 1
        why = skip_reason(dst) or skip_reason(src)
        if why:
            res["skipped"] += 1
            res["skip_why"].add(why)
            continue
        msrc, mdst = map_path(src, mode), map_path(dst, mode)
        base = translate(repo.blob(f"{sha}^", src), mode) if st != "A" else b""
        theirs = translate(repo.blob(sha, dst), mode) if st != "D" else None
        ours = repo.blob(target, mdst if st != "D" else msrc)
        if st == "A" and ours is None:
            parts = mdst.split("/")
            top = "/".join(parts[:2]) if len(parts) > 2 else parts[0]
            if len(parts) > 1 and repo.blob(target, top) is None:
                res["missing"] += 1  # whole crate/app absent in Roboco
                res["missing_paths"].append(mdst + " (new file in absent tree)")
            else:
                res["clean"] += 1
                res["merged"][mdst] = theirs
            continue
        if st == "D":
            if ours is None or ours == base:
                res["clean"] += 1
                res["merged"][msrc] = None
            else:
                res["conflicted"] += 1; res["regions"] += 1
                res["conflict_paths"].append(msrc + " (delete vs modified)")
            continue
        if ours is None:
            res["missing"] += 1
            res["missing_paths"].append(mdst)
            continue
        if ours == theirs:
            res["noop"] += 1
            continue
        merged, regions, clines = merge3(ours, base, theirs)
        res["merged"][mdst] = merged
        if regions == 0:
            res["clean"] += 1
        else:
            res["conflicted"] += 1
            res["regions"] += regions
            res["clines"] += clines
            res["conflict_paths"].append(f"{mdst} ({regions}r/{clines}l)")
    return res


def classify(r):
    live = r["files"] - r["skipped"]
    if live == 0:
        return "AUTO-SKIP"
    if r["missing"] == 0 and r["conflicted"] == 0:
        return "CLEAN" if r["clean"] else "ALREADY-IN"
    nontrivial = [p for p in r["conflict_paths"] if not p.startswith(TRIVIAL_FILES)]
    if r["missing"] == 0 and (not nontrivial or (r["regions"] <= 3 and r["clines"] <= 30)):
        return "TRIVIAL"
    return "REAL"


def diff_lines(a, b):
    """Changed-line multiset between two blobs (cheap, via difflib)."""
    import difflib
    a = (a or b"").decode("utf-8", "replace").splitlines()
    b = (b or b"").decode("utf-8", "replace").splitlines()
    out = set()
    for ln in difflib.unified_diff(a, b, lineterm="", n=0):
        if ln[:1] in "+-" and not ln.startswith(("+++", "---")) and ln[1:].strip():
            out.add(ln[0] + ln[1:].strip())
    return out


def score_against_port(repo, r, target, port):
    """Share of the real port's changed lines (in files both touched) that the
    pipeline reproduced, and the pipeline lines the real port did not keep."""
    port_files = [f for f in repo.git("diff-tree", "-r", "--no-commit-id", "--name-only",
                                      target, port).splitlines()
                  if not f.startswith(".scratch/")]  # ticket bookkeeping, not code
    hit = tot = extra = 0
    for f in port_files:
        if f not in r["merged"]:
            tot += len(diff_lines(repo.blob(target, f), repo.blob(port, f)))
            continue
        real = diff_lines(repo.blob(target, f), repo.blob(port, f))
        ours = diff_lines(repo.blob(target, f), r["merged"][f])
        tot += len(real); hit += len(real & ours); extra += len(ours - real)
    return hit, tot, extra


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--sample", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    repo = Repo(a.repo)
    rows = []
    for line in open(a.sample, encoding="utf-8"):
        if not line.strip() or line.startswith("#"):
            continue
        cols = line.rstrip("\n").split("\t")
        sha, target, label = cols[0], cols[1], cols[2]
        port = cols[3] if len(cols) > 3 and cols[3] else None
        subj = repo.git("log", "-1", "--format=%s", sha).strip()
        out = {"sha": sha[:9], "label": label, "subject": subj[:70]}
        for mode in ("raw", "paths", "full"):
            r = replay(repo, sha, target, mode)
            out[mode] = classify(r)
            if mode == "full":
                out.update(files=r["files"], skipped=r["skipped"], clean=r["clean"],
                           noop=r["noop"], conflicted=r["conflicted"], regions=r["regions"],
                           clines=r["clines"], missing=r["missing"],
                           why=",".join(sorted(r["skip_why"])),
                           detail="; ".join(r["conflict_paths"] + ["MISSING " + p for p in r["missing_paths"]])[:400])
                if port:
                    hit, tot, extra = score_against_port(repo, r, target, port)
                    out["port_recall"] = f"{hit}/{tot}"
                    out["pipeline_extra"] = extra
        rows.append(out)
        print(f"{out['sha']} {out['raw']:>10} {out['paths']:>10} {out['full']:>10}  "
              f"f={out['files']} s={out['skipped']} c={out['clean']} x={out['conflicted']}"
              f"/{out['regions']}r/{out['clines']}l m={out['missing']} "
              f"{out.get('port_recall','')}  {subj[:50]}", flush=True)
    keys = ["sha", "label", "subject", "raw", "paths", "full", "files", "skipped", "clean",
            "noop", "conflicted", "regions", "clines", "missing", "why", "port_recall",
            "pipeline_extra", "detail"]
    with open(a.out, "w", encoding="utf-8") as h:
        h.write("\t".join(keys) + "\n")
        for o in rows:
            h.write("\t".join(str(o.get(k, "")) for k in keys) + "\n")


if __name__ == "__main__":
    main()
