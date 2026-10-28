import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import upstream_common as uc  # noqa: E402


class PathRulesTest(unittest.TestCase):
    def setUp(self):
        self.r = uc.PathRules()

    def test_file_reasons(self):
        f = self.r.file_reason
        self.assertEqual(f("apps/landing/src/index.tsx"), "landing")
        self.assertEqual(f("apps/www-redirect/wrangler.jsonc"), "landing")
        self.assertEqual(f("edge/src/rooms.ts"), "cloud")
        self.assertEqual(f("apps/android/app/build.gradle.kts"), "upstream-android")
        self.assertEqual(f("crates/zatex/src/lib.rs"), "declined")
        self.assertEqual(f(".github/workflows/macos.yml"), "upstream-ci")
        self.assertEqual(f(".github/workflows/testflight.yml"), "upstream-ci")
        self.assertEqual(f(".github/workflows/deploy.yml"), "cloud")
        self.assertEqual(f("README.ja.md"), "docs-only")
        self.assertEqual(f("apps/ios/Zeron/App.swift"), "ios")

    def test_portable_files(self):
        f = self.r.file_reason
        for p in ("crates/ui/src/shell.rs", "README.md", ".github/workflows/windows.yml",
                  "crates/mobile/src/layout/rows.rs", "apps/zeron/src/main.rs", "docs/README.ja.md",
                  "edgeless/x.rs", "crates/zatexx/a.rs"):
            self.assertIsNone(f(p), p)

    def test_auto_skip(self):
        self.assertEqual(self.r.route("Landing: new hero", ["apps/landing/a.tsx", "edge/b.ts"]),
                         ("auto-skip", "cloud,landing"))
        self.assertEqual(self.r.route("Bump version to v0.2.128", ["Cargo.toml", "Cargo.lock"]),
                         ("auto-skip", "version-bump"))
        self.assertEqual(self.r.route("Release v0.2.73", ["Cargo.toml"]), ("auto-skip", "version-bump"))
        self.assertEqual(self.r.route("CI: macOS runner", [".github/workflows/macos.yml"]),
                         ("auto-skip", "upstream-ci"))

    def test_release_subject_needs_a_version(self):
        self.assertEqual(self.r.route("Release notes for the updater", ["crates/update/src/lib.rs"])[0],
                         "review")

    def test_ios_goes_to_android_lane_never_skip(self):
        self.assertEqual(self.r.route("iOS: composer", ["apps/ios/Zeron/Composer.swift"]),
                         ("android-lane", "ios"))
        # iOS plus an otherwise-skipped file is still the Android lane, not a skip
        self.assertEqual(self.r.route("iOS + landing", ["apps/ios/Zeron/A.swift", "apps/landing/b.tsx"]),
                         ("android-lane", "ios"))

    def test_mixed_ios_is_review_with_a_flag(self):
        lane, note = self.r.route("Mobile captions", ["apps/ios/Zeron/A.swift", "crates/mobile/src/a.rs"])
        self.assertEqual(lane, "review")
        self.assertIn("iOS", note)

    def test_plain_review(self):
        self.assertEqual(self.r.route("Fix a thing (#1)", ["crates/ui/src/shell.rs"]), ("review", ""))
        self.assertEqual(self.r.route("Empty", []), ("review", "no file changes"))


if __name__ == "__main__":
    unittest.main()
