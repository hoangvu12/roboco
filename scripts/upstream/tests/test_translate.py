"""End to end on a throwaway repository: an upstream history, a renamed Roboco
branch that already ported part of the next upstream commit, and translate.py
applying that commit into a worktree."""
import contextlib
import io
import os
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import translate  # noqa: E402

LOCK = """version = 4

[[package]]
name = "serde"
version = "{serde}"
source = "registry+https://github.com/rust-lang/crates.io-index"

[[package]]
name = "{crate}"
version = "0.1.0"
"""
A = "pub fn a() -> u32 {\n    1\n}\n\n"
B = "pub fn b() -> u32 {\n    let two = 1 + 1;\n    two\n}\n\n"
C = "\npub fn c() -> u32 {\n    let three = 3;\n    three\n}\n"
Z = "pub fn z() -> u32 {\n    26\n}\n"
ROBOCO_ONLY = "pub fn roboco_only() -> &'static str {\n    \"windows paths\"\n}\n\n"


class TranslateEndToEnd(unittest.TestCase):
    def setUp(self):
        env = mock.patch.dict(os.environ, {"ROBOCO_MERGIRAF": ""})  # mergiraf stays opt-in
        env.start()
        self.addCleanup(env.stop)
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = os.path.join(self.tmp.name, "repo")
        self.wt = os.path.join(self.tmp.name, "wt")
        os.makedirs(self.repo)
        self.git("init", "-q", "-b", "upstream")
        self.git("config", "core.autocrlf", "false")
        self.write({"crates/zeron-core/src/lib.rs": A + Z,
                    "Cargo.lock": LOCK.format(serde="1.0.1", crate="zeron-core"),
                    "docs/notes.md": "line one\nold wording\n"})
        self.u0 = self.commit("Upstream base")
        self.write({"crates/zeron-core/src/lib.rs": A + B + Z + C,
                    "Cargo.lock": LOCK.format(serde="1.0.2", crate="zeron-core")})
        self.u1 = self.commit("Core: add b and c (#7)")
        self.write({"crates/zeron-core/src/extra.rs": "pub fn extra() {}\n"})
        self.u2 = self.commit("Core: extra (#8)")
        self.write({"docs/notes.md": "line one\nnew wording\n"})
        self.u3 = self.commit("Docs: reword (#9)")
        # Roboco: renamed, a Roboco-only function, b already ported, its own lockfile.
        self.git("checkout", "-q", "-b", "main", self.u0)
        self.git("mv", "crates/zeron-core", "crates/roboco-core")
        self.write({"docs/notes.md": "Roboco notes\nline one\nnew wording\n",
                    "crates/roboco-core/src/lib.rs": A + ROBOCO_ONLY + B + Z,
                    "Cargo.lock": LOCK.format(serde="1.0.1", crate="roboco-core") + "\n# roboco\n"})
        self.commit("Rebrand; port b")
        self.git("worktree", "add", "-q", "-b", "port", self.wt, "main")

    def tearDown(self):
        subprocess.run(["git", "-C", self.repo, "worktree", "remove", "--force", self.wt], capture_output=True)
        self.tmp.cleanup()

    def git(self, *args, cwd=None):
        r = subprocess.run(["git", "-C", cwd or self.repo, "-c", "user.name=t", "-c", "user.email=t@example.com",
                            *args], capture_output=True, check=True)
        return r.stdout.decode()

    def write(self, files):
        for rel, data in files.items():
            path = os.path.join(self.repo, *rel.split("/"))
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(data)

    def commit(self, msg):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", msg)
        return self.git("rev-parse", "HEAD").strip()

    def run_translate(self, *args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            translate.main(list(args))
        return out.getvalue()

    def read(self, rel):
        with open(os.path.join(self.wt, *rel.split("/")), encoding="utf-8") as fh:
            return fh.read()

    def report_dir(self):
        return self.git("rev-parse", "--path-format=absolute", "--git-path", "roboco-translate", cwd=self.wt).strip()

    def test_plain_replay_conflicts_and_annotates(self):
        out = self.run_translate(self.u1, "--repo", self.repo, "--target", "main", "--annotate", "--no-residual")
        self.assertIn("TRIVIAL", out.splitlines()[0])
        self.assertIn("REGENERATE (kept ours)", out)
        self.assertIn("CONFLICT", out)
        self.assertIn("upstream bumped: serde 1.0.1 -> 1.0.2", out)
        self.assertIn("cargo update -p serde@1.0.1 --precise 1.0.2", out)

    def test_residual_is_the_default(self):
        out = self.run_translate(self.u1, "--apply", "--worktree", self.wt, "--commit")
        self.assertIn("TRIVIAL", out.splitlines()[0])  # only the lockfile is left: cargo's job
        self.assertIn("residual: 1 hunk(s) already in Roboco, 1 to port", out)
        self.assertIn("not committing a TRIVIAL result", out)
        lib = self.read("crates/roboco-core/src/lib.rs")
        self.assertEqual(lib, A + ROBOCO_ONLY + B + Z + C)  # b not doubled, c added, Roboco's code kept
        self.assertIn("# roboco", self.read("Cargo.lock"))  # never merged
        self.assertEqual(self.git("status", "--porcelain", cwd=self.wt).split(), ["M", "crates/roboco-core/src/lib.rs"])
        md = os.path.join(self.report_dir(), self.u1[:9] + ".md")
        with open(md, encoding="utf-8") as fh:
            report = fh.read()
        self.assertIn("## Residual mode: hunks skipped as already in Roboco", report)
        self.assertIn("`pub fn b() -> u32 {`", report)
        self.assertIn("## Cargo.lock: regenerate, don't merge", report)
        self.assertTrue(os.path.exists(md[:-3] + ".json"))

    def test_plain_apply_writes_annotated_conflicts_outside_the_tree(self):
        self.run_translate(self.u1, "--apply", "--worktree", self.wt, "--no-residual")
        lib = self.read("crates/roboco-core/src/lib.rs")
        self.assertIn("<<<<<<< roboco", lib)
        self.assertIn("||||||| upstream^", lib)
        with open(os.path.join(self.report_dir(), self.u1[:9] + ".md"), encoding="utf-8") as fh:
            report = fh.read()
        self.assertIn("| `crates/roboco-core/src/lib.rs` |", report)
        self.assertNotIn("roboco-translate", self.git("status", "--porcelain", "--ignored", cwd=self.wt))

    def test_clean_commit_keeps_the_trailer(self):
        out = self.run_translate(self.u2, "--apply", "--worktree", self.wt, "--commit")
        self.assertIn("committed", out)
        msg = self.git("log", "-1", "--format=%B", cwd=self.wt)
        self.assertEqual(msg.splitlines()[0], "Core: extra (zeron#8)")
        self.assertIn(f"Upstream-Commit: {self.u2}", msg)
        self.assertFalse(os.path.exists(os.path.join(self.report_dir(), self.u2[:9] + ".md")))

    def test_change_roboco_already_has_is_noop(self):
        out = self.run_translate(self.u3, "--repo", self.repo, "--target", "main")
        self.assertIn("ALREADY-IN", out.splitlines()[0])
        self.assertIn("NOOP", out)

    def test_threshold_needs_residual(self):
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            translate.main([self.u1, "--repo", self.repo, "--target", "main",
                            "--no-residual", "--residual-threshold", "0.8"])

    def test_unknown_commit(self):
        with self.assertRaises(SystemExit) as e, contextlib.redirect_stdout(io.StringIO()):
            translate.main(["deadbeef", "--repo", self.repo, "--target", "main"])
        self.assertIn("unknown commit", str(e.exception))


if __name__ == "__main__":
    unittest.main()
