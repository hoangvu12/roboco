"""mergiraf is optional: these tests use a stand-in script, and one test runs the
real binary only when $ROBOCO_MERGIRAF or `mergiraf` on PATH provides it."""
import os
import sys
import tempfile
import textwrap
import types
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import mergiraf  # noqa: E402
import translate  # noqa: E402
import upstream_common as uc  # noqa: E402

# Stand-in for `mergiraf merge BASE LEFT RIGHT -o OUT -p PATH ...`: behaviour picked
# by the first line of LEFT (ours).
FAKE = textwrap.dedent("""
    import sys, time
    args = sys.argv[1:]
    assert args[0] == "merge"
    base, left, right = args[1:4]
    out = args[args.index("-o") + 1]
    mode = open(left, "rb").read().split(b"\\n", 1)[0]
    if mode == b"#crash":
        sys.stderr.write("boom\\n")
        sys.exit(2)
    if mode == b"#slow":
        time.sleep(30)
    data = open(right, "rb").read()
    if mode == b"#partial":
        data = b"<<<<<<< roboco\\na\\n=======\\nb\\n>>>>>>> upstream\\n"
    open(out, "wb").write(data)
""")


class MergirafTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.TemporaryDirectory()
        cls.script = os.path.join(cls.dir.name, "fake_mergiraf.py")
        with open(cls.script, "w", encoding="utf-8") as fh:
            fh.write(FAKE)
        cls.exe = [sys.executable, cls.script]

    @classmethod
    def tearDownClass(cls):
        cls.dir.cleanup()

    def test_supported(self):
        self.assertTrue(mergiraf.supported("crates/ui/src/shell.rs"))
        self.assertTrue(mergiraf.supported("crates/ui/Cargo.toml"))
        self.assertTrue(mergiraf.supported("apps/android/app/build.gradle.kts"))
        self.assertFalse(mergiraf.supported("Cargo.lock"))
        self.assertFalse(mergiraf.supported("docs/reference/upstream-ports.md"))

    def test_find(self):
        with mock.patch.dict(os.environ, {mergiraf.ENV: ""}):
            self.assertIsNone(mergiraf.find(os.path.join(self.dir.name, "nope.exe")))
            self.assertEqual(mergiraf.find(self.script), self.script)
        with mock.patch.dict(os.environ, {mergiraf.ENV: self.script}):
            self.assertEqual(mergiraf.find(), self.script)

    def test_resolved(self):
        data, why = mergiraf.merge(self.exe, "a.rs", b"base\n", b"ours\n", b"theirs\n")
        self.assertEqual((data, why), (b"theirs\n", ""))

    def test_partial_crash_and_timeout_keep_git(self):
        self.assertEqual(mergiraf.merge(self.exe, "a.rs", b"b\n", b"#partial\n", b"t\n"), (None, "left 1 conflict(s)"))
        data, why = mergiraf.merge(self.exe, "a.rs", b"b\n", b"#crash\n", b"t\n")
        self.assertIsNone(data)
        self.assertIn("boom", why)
        data, why = mergiraf.merge(self.exe, "a.rs", b"b\n", b"#slow\n", b"t\n", timeout=1)
        self.assertEqual((data, why), (None, "timed out after 1s"))
        data, why = mergiraf.merge([os.path.join(self.dir.name, "missing.exe")], "a.rs", b"b", b"o", b"t")
        self.assertIsNone(data)
        self.assertIn("could not run", why)

    def test_refine_uses_mergiraf_only_when_it_resolves(self):
        base, theirs = b"fn a() {}\n", b"fn a() {}\nfn b() {}\n"
        res = uc.Replay("a" * 40, "b" * 40)
        files = []
        for path, ours in (("ok.rs", b"fn r() {}\n"), ("partial.rs", b"#partial\n"), ("notes.md", b"x\n")):
            merged, regions, lines = uc.merge3(ours, base, theirs)
            f = uc.FileResult("M", path, path, "conflict", regions=regions, lines=lines, merged=merged,
                              base=base, theirs=theirs, ours=ours)
            files.append(f)
        res.files = files
        opts = types.SimpleNamespace(residual=False, residual_threshold=1.0, mergiraf=self.exe, mergiraf_timeout=30)
        translate.refine(res, opts)
        ok, partial, md = files
        self.assertEqual((ok.state, ok.how, ok.regions, ok.merged), ("clean", "mergiraf", 0, theirs))
        self.assertEqual(partial.state, "conflict")
        self.assertEqual(partial.notes, ["mergiraf: left 1 conflict(s)"])
        self.assertEqual((md.state, md.notes), ("conflict", []))  # not a mergiraf language here
        res.files = [ok]
        self.assertEqual(res.classify(), "CLEAN")
        self.assertIn("mergiraf resolved ok.rs", translate.review_needed(res)[0])

    def test_pick_mergiraf_is_opt_in(self):
        def args(**kw):
            a = dict(no_mergiraf=False, mergiraf=None)
            a.update(kw)
            return types.SimpleNamespace(**a)
        with mock.patch.dict(os.environ, {mergiraf.ENV: ""}):
            self.assertIsNone(translate.pick_mergiraf(args()))
            self.assertEqual(translate.pick_mergiraf(args(mergiraf=self.script)), self.script)
            with self.assertRaises(SystemExit):
                translate.pick_mergiraf(args(mergiraf=os.path.join(self.dir.name, "nope.exe")))
        with mock.patch.dict(os.environ, {mergiraf.ENV: self.script}):
            self.assertEqual(translate.pick_mergiraf(args()), self.script)
            self.assertIsNone(translate.pick_mergiraf(args(no_mergiraf=True)))
        with mock.patch.dict(os.environ, {mergiraf.ENV: os.path.join(self.dir.name, "nope.exe")}), \
                mock.patch("sys.stderr"):
            self.assertIsNone(translate.pick_mergiraf(args()))  # env set but missing: warn, go on


@unittest.skipUnless(mergiraf.find(), "mergiraf not installed (optional)")
class RealMergirafTest(unittest.TestCase):
    def test_resolves_independent_rust_items(self):
        base = b"fn a() {}\n\nfn z() {}\n"
        ours = b"fn a() {}\n\nfn roboco() {}\n\nfn z() {}\n"
        theirs = b"fn a() {}\n\nfn upstream() {}\n\nfn z() {}\n"
        _, regions, _ = uc.merge3(ours, base, theirs)
        self.assertEqual(regions, 1)
        data, why = mergiraf.merge(mergiraf.find(), "src/lib.rs", base, ours, theirs)
        self.assertEqual(why, "")
        self.assertIn(b"fn roboco()", data)
        self.assertIn(b"fn upstream()", data)


if __name__ == "__main__":
    unittest.main()
