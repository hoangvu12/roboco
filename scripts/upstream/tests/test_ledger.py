import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import upstream_common as uc  # noqa: E402

A = "a" * 40
B = "b" * 39 + "1"
B2 = "b" * 39 + "2"
HEADER = "\t".join(uc.LEDGER_COLUMNS)


def text(*rows):
    return HEADER + "\n" + "".join("\t".join(r) + "\n" for r in rows)


class StatusTest(unittest.TestCase):
    def test_valid(self):
        for s in ("ported", "ported 9d6669f68", "skipped:landing", "skipped:version-bump",
                  "skipped:n/a", "superseded", "declined", "missing", "partial", "android-lane",
                  "new", "later", "ticketed wave10-27", "ticketed wave10-03,21",
                  "ticketed ci-speed-03", "ticketed wave10-05,ci-speed-03", "queued 2026-11-04"):
            self.assertTrue(uc.valid_status(s), s)

    def test_invalid(self):
        for s in ("", "PORTED", "ported xyz", "skipped", "skipped:", "ticketed", "ticketed wave10",
                  "queued", "take", "ported  9d6669f68", "missing "):
            self.assertFalse(uc.valid_status(s), s)

    def test_kind(self):
        self.assertEqual(uc.status_kind("ported 9d6669f68"), "ported")
        self.assertEqual(uc.status_kind("ticketed wave10-03,21"), "ticketed wave10")
        self.assertEqual(uc.status_kind("ticketed ci-speed-03"), "ticketed ci-speed")
        self.assertEqual(uc.status_kind("skipped:landing"), "skipped:landing")
        self.assertEqual(uc.status_kind("queued 2026-11-04"), "queued")


class LedgerTest(unittest.TestCase):
    def sample(self):
        return text([A, "2026-09-15", "#1", "First", "ported 123456789", "PORTED p00", ""],
                    [B, "2026-09-16", "", "Second (#2)", "missing", "MISSING p00", "a note"],
                    [B2, "2026-09-16", "", "Third", "skipped:landing", "SKIP:landing p00", ""])

    def test_round_trip_is_byte_identical(self):
        t = self.sample()
        self.assertEqual(uc.Ledger.parse(t).dumps(), t)

    def test_find(self):
        led = uc.Ledger.parse(self.sample())
        self.assertEqual(led.find(A)["subject"], "First")
        self.assertEqual(led.find("aaaaaaa")["subject"], "First")
        self.assertIsNone(led.find("ccccccc"))
        with self.assertRaises(uc.LedgerError):
            led.find("bbbbbbbbb")  # ambiguous: B and B2
        with self.assertRaises(uc.LedgerError):
            led.find("aaa")
        self.assertIn("aaaaaaaa", led)

    def test_set_status_and_add(self):
        led = uc.Ledger.parse(self.sample())
        row, old = led.set_status(B, "queued 2026-11-04", "take")
        self.assertEqual((old, row["status"], row["note"]), ("missing", "queued 2026-11-04", "take"))
        with self.assertRaises(uc.LedgerError):
            led.set_status(B, "take")
        led.add(dict(upstream_sha="c" * 40, date="2026-10-28", pr="", subject="New", status="new",
                     audit="triage 2026-10-28", note="tab\there"))
        self.assertIn("c" * 7, led)
        self.assertNotIn("tab\there", led.dumps())
        with self.assertRaises(uc.LedgerError):
            led.add(dict(upstream_sha=A, status="new"))
        self.assertEqual(led.counts(), {"ported": 1, "queued": 1, "skipped:landing": 1, "new": 1})

    def test_rejects_bad_files(self):
        bad = [
            "sha\tdate\n",  # wrong header
            text([A, "d", "", "s", "bogus", "", ""]),  # bad status
            text([A[:9], "d", "", "s", "missing", "", ""]),  # short sha
            text([A, "d", "", "s", "missing", ""]),  # too few cells
            text([A, "d", "", "s", "missing", "", ""], [A, "d", "", "s", "missing", "", ""]),  # duplicate
        ]
        for t in bad:
            with self.assertRaises(uc.LedgerError, msg=t):
                uc.Ledger.parse(t)

    def test_crlf_input(self):
        led = uc.Ledger.parse(self.sample().replace("\n", "\r\n"))
        self.assertEqual(len(led.rows), 3)


class CheckedInLedgerTest(unittest.TestCase):
    """The committed ledger and PR list parse and use the documented vocabulary."""

    def test_commit_ledger(self):
        led = uc.Ledger.load(os.path.join(uc.REPO_ROOT, uc.LEDGER))
        self.assertGreaterEqual(len(led.rows), 576)
        with open(os.path.join(uc.REPO_ROOT, uc.LEDGER), encoding="utf-8", newline="") as fh:
            self.assertEqual(fh.read(), led.dumps(), "ledger must round-trip (LF, no stray tabs)")

    def test_pr_ledger(self):
        rows = uc.read_table(os.path.join(uc.REPO_ROOT, uc.PR_LEDGER))
        self.assertEqual(rows[0], ["pr", "state", "date", "title", "status", "audit", "note"])
        for r in rows[1:]:
            self.assertEqual(len(r), 7, r)
            self.assertRegex(r[0], r"^#\d+$")
            self.assertIn(r[1], ("open", "closed", "merged"))
            self.assertTrue(r[4] in ("watch", "already") or uc.valid_status(r[4]), r)


class ConflictCountTest(unittest.TestCase):
    def test_counts(self):
        merged = (b"a\n<<<<<<< roboco\nx\ny\n||||||| upstream^\nb\n=======\nz\n>>>>>>> upstream\n"
                  b"c\n<<<<<<< roboco\n=======\nq\n>>>>>>> upstream\n")
        self.assertEqual(uc.count_conflicts(merged), (2, 5))

    def test_merge3(self):
        base = b"one\ntwo\nthree\n"
        self.assertEqual(uc.merge3(b"one\ntwo\nthree\nfour\n", base, b"zero\none\ntwo\nthree\n")[1:],
                         (0, 0))
        merged, regions, _ = uc.merge3(b"one\nTWO\nthree\n", base, b"one\n2\nthree\n")
        self.assertEqual(regions, 1)
        self.assertIn(b"<<<<<<< roboco", merged)
        self.assertEqual(uc.merge3(b"\0bin", b"\0bin", b"\0new"), (b"\0new", 0, 0))
        self.assertEqual(uc.merge3(b"\0ours", b"\0bin", b"\0new")[1], 1)

    def test_classify(self):
        def res(*states):
            r = uc.Replay("u", "t")
            for path, state, regions, lines in states:
                r.files.append(uc.FileResult("M", path, path, state, regions=regions, lines=lines))
            return r
        self.assertEqual(res(("a", "skipped", 0, 0)).classify(), "AUTO-SKIP")
        self.assertEqual(res(("a", "noop", 0, 0)).classify(), "ALREADY-IN")
        self.assertEqual(res(("a", "clean", 0, 0), ("b", "skipped", 0, 0)).classify(), "CLEAN")
        self.assertEqual(res(("a", "conflict", 3, 30)).classify(), "TRIVIAL")
        self.assertEqual(res(("a", "conflict", 4, 4)).classify(), "REAL")
        self.assertEqual(res(("a", "conflict", 1, 31)).classify(), "REAL")
        self.assertEqual(res(("Cargo.lock", "conflict", 9, 99)).classify(), "TRIVIAL")
        self.assertEqual(res(("a", "clean", 0, 0), ("b", "missing", 0, 0)).classify(), "REAL")


if __name__ == "__main__":
    unittest.main()
