"""Build/refresh a renamed mirror of zeron/main with git-filter-repo.

Prototype (2026-10-29 less-recoding research). Needs `pip install git-filter-repo`.
Run it on a THROWAWAY bare clone, never on the main checkout (filter-repo rewrites refs):
  git clone --bare --no-local . C:/tmp/mirror && git -C C:/tmp/mirror remote remove origin
  python renamed-mirror-proto.py C:/tmp/mirror      (~2 min; same SHAs on every run)
usage: renamed-mirror-proto.py MIRROR_CLONE [--incremental]
MIRROR_CLONE: a clone whose branch `zeron/main` holds pristine upstream history.
The rewrite is a pure function of (upstream history, rename-map.tsv, path-rules.tsv):
 - drop files path-rules.tsv excludes (skip + ios rules)
 - map paths and file text through rename-map.tsv (same code translate.py uses)
 - append `Upstream-Commit: <original sha>` to every commit message
Measured: a full rewrite of 1,901 upstream commits takes ~2 min, and rewriting from an
older upstream head yields the same SHAs as today's rewrite (append-only), so a weekly
full re-run never changes existing SHAs. --incremental uses filter-repo's hidden,
experimental --state-branch; it was NOT tested (the full re-run is cheap enough).
"""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "scripts", "upstream"))
import upstream_common as uc
import git_filter_repo as fr

ren, rules = uc.RenameMap(), uc.PathRules()
cache = {}

def file_info(filename, mode, blob_id, value):
    p = filename.decode("utf-8", "surrogateescape")
    if rules.file_reason(p):
        return (None, mode, blob_id)          # drop
    newp = ren.path(p)
    if mode == b"160000":                     # submodule: keep gitlink
        return (newp.encode(), mode, blob_id)
    key = (blob_id, newp.startswith(tuple(ren.verbatim)))
    if key not in cache:
        data = value.get_contents_by_identifier(blob_id)
        new = ren.text_bytes(data, newp)
        cache[key] = blob_id if new == data else value.insert_file_with_contents(new)
    return (newp.encode(), mode, cache[key])

def commit_cb(commit, metadata):
    commit.message = commit.message.rstrip(b"\n") + b"\n\nUpstream-Commit: " + commit.original_id + b"\n"

def main():
    path = sys.argv[1]
    args = ["--force", "--refs", "zeron/main", "--quiet"]
    if "--incremental" in sys.argv:
        args += ["--state-branch", "renamed-mirror-state"]
    os.chdir(path)
    fa = fr.FilteringOptions.parse_args(args)
    fr.RepoFilter(fa, file_info_callback=file_info, commit_callback=commit_cb).run()

main()
