import os
import sys
import types
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import annotate  # noqa: E402
import residual  # noqa: E402
import translate  # noqa: E402
import upstream_common as uc  # noqa: E402


def text(*lines):
    return ("\n".join(lines) + "\n").encode("utf-8")


# Modelled on wave-10 ticket 06 (upstream 2a38baa4e, crates/ui/src/workspace_links.rs):
# Roboco ported `FileLinkResolution` earlier (cc84b5f9a) with its own tweaks; the
# upstream commit adds the enum and a resolver next to a change Roboco still lacks.
BASE = text(
    "use std::path::{Component, Path};",
    "",
    "pub(crate) fn resolve_workspace_file_link(target: &str, root: &str) -> Option<WorkspaceFileLink> {",
    "    let link = classify_file_link(target)?;",
    "    Some(link)",
    "}",
)
ENUM = (
    "/// Where a classified file link sits relative to a surface's ordered roots.",
    "#[derive(Debug, PartialEq, Eq)]",
    "pub(crate) enum FileLinkResolution {",
    "    Owned {",
    "        root: usize,",
    "        link: WorkspaceFileLink,",
    "    },",
    "    Outside(WorkspaceFileLink),",
    "}",
)
THEIRS = text(
    "use std::path::{Component, Path, PathBuf};",
    "",
    *ENUM,
    "",
    "pub(crate) fn resolve_workspace_file_link(target: &str, root: &str) -> Option<WorkspaceFileLink> {",
    "    let link = classify_file_link(target)?;",
    "    Some(WorkspaceFileLink { outside: link.path.starts_with('/'), ..link })",
    "}",
)
# Roboco: the ported enum (with a Windows-only comment of its own) and the new
# import, but not the `outside` change in the resolver.
OURS = text(
    "use std::path::{Component, Path, PathBuf};",
    "",
    *ENUM[:3],
    "    // Roboco: roots may be Windows drive paths.",
    *ENUM[3:],
    "",
    "pub(crate) fn resolve_workspace_file_link(target: &str, root: &str) -> Option<WorkspaceFileLink> {",
    "    let link = classify_windows_file_link(target)?;",
    "    Some(link)",
    "}",
)


class ResidualTest(unittest.TestCase):
    def test_skips_what_roboco_has_and_keeps_the_rest(self):
        plain, regions, _ = uc.merge3(OURS, BASE, THEIRS)
        self.assertGreater(regions, 0)
        r = residual.residual_theirs(OURS, BASE, THEIRS)
        enum_at = THEIRS.decode().splitlines().index("pub(crate) enum FileLinkResolution {")
        self.assertTrue(any(h.j1 <= enum_at < h.j2 for h in r.dropped))
        self.assertEqual([h for h in r.dropped if h.lacking], [])
        # The resolver change Roboco lacks is still merged.
        self.assertIn("outside: link.path.starts_with('/')", r.theirs.decode())
        self.assertEqual(len(r.kept), 1)
        merged, regions2, _ = uc.merge3(OURS, BASE, r.theirs)
        self.assertLess(regions2, regions)
        # Roboco's ported enum is never lost (no copy, no removal of its own lines).
        out = merged.decode()
        self.assertEqual(out.count("pub(crate) enum FileLinkResolution {"), 1)
        self.assertIn("// Roboco: roots may be Windows drive paths.", out)

    def test_known_false_positive_new_variant_is_not_skipped(self):
        # The prototype (80% of the lines anywhere in the file) skipped this hunk:
        # Roboco has the enum, but upstream's version adds a `Remote` variant.
        theirs = text(
            "use std::path::{Component, Path};",
            "",
            *ENUM[:-1],
            "    Remote(WorkspaceFileLink),",
            "}",
            "",
            *BASE.decode().splitlines()[2:],
        )
        ours = text("use std::path::{Component, Path};", "", *ENUM, "", *BASE.decode().splitlines()[2:])
        r = residual.residual_theirs(ours, BASE, theirs)
        self.assertEqual(r.dropped, [])
        self.assertIn(b"Remote(WorkspaceFileLink)", r.theirs)
        merged, _, _ = uc.merge3(ours, BASE, r.theirs)
        self.assertIn(b"Remote(WorkspaceFileLink)", merged)
        # A looser threshold does skip it, and says what it skipped without evidence.
        loose = residual.residual_theirs(ours, BASE, theirs, threshold=0.8)
        self.assertEqual(len(loose.dropped), 1)
        self.assertEqual(loose.dropped[0].lacking, ["Remote(WorkspaceFileLink),"])
        self.assertEqual(loose.dropped_dicts()[0]["lacking"], ["Remote(WorkspaceFileLink),"])

    def test_repeated_lines_need_as_many_copies(self):
        base = text("fn a() {}", "")
        theirs = text("fn a() {}", "", "#[test]", "fn first() { check(); }", "#[test]", "fn second() { check(); }")
        ours = text("fn a() {}", "", "#[test]", "fn first() { check(); }", "fn roboco() {}")
        r = residual.residual_theirs(ours, base, theirs)
        self.assertEqual(r.dropped, [])  # one `#[test]` in Roboco can't vouch for two

    def test_removed_lines_still_in_roboco_keep_the_hunk(self):
        base = text("let a = old_call(1);", "let b = 2;")
        theirs = text("let a = new_call(1);", "let b = 2;")
        ours_has_new = text("let a = new_call(1);", "let b = 2;", "let c = 3;")
        ours_kept_old = text("let a = new_call(1);", "let a = old_call(1);", "let b = 2;")
        self.assertEqual(len(residual.residual_theirs(ours_has_new, base, theirs).dropped), 1)
        self.assertEqual(residual.residual_theirs(ours_kept_old, base, theirs).dropped, [])

    def test_insignificant_hunks_are_never_skipped(self):
        base = text("fn a() {", "    x();", "}")
        theirs = text("fn a() {", "    x();", "", "}")
        r = residual.residual_theirs(base, base, theirs)
        self.assertEqual(r.dropped, [])
        self.assertEqual(r.theirs, theirs)

    def test_not_utf8(self):
        self.assertIsNone(residual.residual_theirs(b"\xff\xfe", b"a\n", b"b\n"))

    def test_no_worse(self):
        self.assertTrue(residual.no_worse((3, 40), (1, 50)))
        self.assertTrue(residual.no_worse((3, 40), (3, 40)))
        self.assertFalse(residual.no_worse((3, 40), (3, 41)))
        self.assertFalse(residual.no_worse((3, 40), (5, 10)))


def opts(**kw):
    o = dict(residual=True, residual_threshold=residual.DEFAULT_THRESHOLD, mergiraf=None, mergiraf_timeout=5)
    o.update(kw)
    return types.SimpleNamespace(**o)


def conflict(path, ours, base, theirs):
    merged, regions, lines = uc.merge3(ours, base, theirs)
    return uc.FileResult("M", path, path, "conflict", regions=regions, lines=lines, merged=merged,
                         base=base, theirs=theirs, ours=ours)


class RefineTest(unittest.TestCase):
    def test_residual_pass_records_what_it_skipped(self):
        res = uc.Replay("a" * 40, "b" * 40)
        f = conflict("crates/ui/src/workspace_links.rs", OURS, BASE, THEIRS)
        before = f.regions
        res.files.append(f)
        translate.refine(res, opts())
        self.assertEqual(f.how, "residual")
        self.assertTrue(f.residual["used"])
        self.assertEqual(f.residual["before"][0], before)
        self.assertTrue(f.residual["dropped"])
        self.assertEqual(f.residual["kept"], 1)
        self.assertNotEqual(f.merged_theirs, THEIRS)
        rec = translate.as_json(res, "subject")["files"][0]
        self.assertIn("residual", rec)
        self.assertEqual(translate.review_needed(res), [])

    def test_residual_pass_falls_back_when_worse(self):
        res = uc.Replay("a" * 40, "b" * 40)
        f = conflict("x.rs", OURS, BASE, THEIRS)
        res.files.append(f)
        orig = residual.no_worse
        residual.no_worse = lambda before, after: False
        try:
            translate.refine(res, opts())
        finally:
            residual.no_worse = orig
        self.assertFalse(f.residual["used"])
        self.assertEqual(f.how, "")
        self.assertEqual(f.merged_theirs, THEIRS)
        self.assertIn("kept the plain merge", f.residual["why"])

    def test_lacking_lines_need_review_before_commit(self):
        res = uc.Replay("a" * 40, "b" * 40)
        f = uc.FileResult("M", "x.rs", "x.rs", "clean", merged=b"x")
        f.how = "residual"
        f.residual = {"used": True, "dropped": [{"lacking": ["Remote(WorkspaceFileLink),"]}]}
        res.files.append(f)
        self.assertEqual(res.classify(), "CLEAN")
        self.assertIn("lines Roboco lacks", translate.review_needed(res)[0])
        f.residual["dropped"][0]["lacking"] = []
        self.assertEqual(translate.review_needed(res), [])


FN_A = ("pub fn a() -> u32 {", "    1", "}", "")
FN_B = ("pub fn b() -> u32 {", "    let two = 1 + 1;", "    two", "}", "")
FN_C = ("pub fn c() -> u32 {", "    let three = 3;", "    three", "}", "")
FN_Z = ("pub fn z() -> u32 {", "    26", "}")


class DuplicatesTest(unittest.TestCase):
    def test_clean_merge_that_doubles_a_port_is_flagged(self):
        # Roboco ported `b` below `z`; upstream adds it above: git merges both copies cleanly.
        base, theirs, ours = text(*FN_A, *FN_Z), text(*FN_A, *FN_B, *FN_Z), text(*FN_A, *FN_Z, "", *FN_B)
        merged, regions, _ = uc.merge3(ours, base, theirs)
        self.assertEqual(regions, 0)
        self.assertEqual(merged.decode().count("pub fn b()"), 2)
        (h,) = residual.duplicates(ours, base, theirs, merged)
        self.assertEqual(h["first"], "pub fn b() -> u32 {")
        self.assertIn("let two = 1 + 1;", h["doubled"])

    def test_new_code_and_single_copies_are_not_flagged(self):
        base, theirs = text(*FN_A, *FN_Z), text(*FN_A, *FN_C, *FN_Z)
        ours = text(*FN_A, *FN_Z, "", *FN_B)
        merged, _, _ = uc.merge3(ours, base, theirs)
        self.assertEqual(residual.duplicates(ours, base, theirs, merged), [])
        # Roboco has `b` at the same place: one copy after the merge, nothing to flag.
        ours = text(*FN_A, *FN_B, *FN_Z)
        merged, _, _ = uc.merge3(ours, base, text(*FN_A, *FN_B, *FN_Z, "// upstream note"))
        self.assertEqual(residual.duplicates(ours, base, text(*FN_A, *FN_B, *FN_Z, "// upstream note"), merged), [])

    def test_refine_reports_but_never_blocks(self):
        base, theirs, ours = text(*FN_A, *FN_Z), text(*FN_A, *FN_B, *FN_Z), text(*FN_A, *FN_Z, "", *FN_B)
        merged, _, _ = uc.merge3(ours, base, theirs)
        res = uc.Replay("a" * 40, "b" * 40)
        f = uc.FileResult("M", "lib.rs", "lib.rs", "clean", merged=merged, base=base, theirs=theirs, ours=ours)
        res.files.append(f)
        translate.refine(res, opts(residual=False))
        self.assertEqual(len(f.duplicates), 1)
        self.assertEqual((f.state, f.merged), ("clean", merged))
        self.assertEqual(res.classify(), "CLEAN")
        self.assertEqual(translate.review_needed(res), [])
        rec = translate.as_json(res, "subject")
        self.assertEqual(rec["files"][0]["duplicates"][0]["first"], "pub fn b() -> u32 {")
        md = annotate.markdown(rec)
        self.assertIn("## Possible duplicates in clean merges", md)
        self.assertIn("`lib.rs`: upstream lines", md)


if __name__ == "__main__":
    unittest.main()
