# 09 — Rename the user-facing "Previews" label

**What to build:** The list shows every server that any chat's agent or terminal started, not only agent "previews". Rename the user-facing label. **Do not rename** wire names, RPC methods, Rust/Kotlin types, crates, test-tag constants or the glossary concept **Preview** (ADR 0005).

**Open question for the user:** **"Ports"** or **"Running servers"**? The spec recommends "Ports". See the spec's Open questions for the trade-offs.

**Surfaces (with "Ports" → `X` below standing for the chosen label)**
- Android `apps/android/app/src/main/java/sh/roboco/android/browser/PreviewsCopy.kt`: `PageTitle = "X"` (the panel's nav title and TalkBack pane title, which yields "X · <chat>") and `SessionMenuRow = "X"`. Keep `RunningOnYourDevice`, `Loading`, `EmptyRemote` and the rest unless the user asks. They do not say "Previews". Optional, only if the user agrees: `Unsupported` → "…too old to show running servers…".
- Android tests that pin the string: `SideChatsEntryTest.kt:173`, `SessionMenuTest.kt:300`, `SessionMenuScreenshotTest` (KDoc ~48 plus a re-recorded screenshot baseline if it captures the row), `PreviewFlowTest.kt` (comments at 27/67, plus any `onNodeWithText("Previews")` lookup), and the KDoc in `SessionMenu.kt:80`, `PreviewPanelRoute.kt:13` and `ShellRoutes.kt:104`.
- Desktop: the ports card heading is already `"Ports"` (`crates/ui/src/shell/session_info.rs:573`). With "Ports", nothing changes on desktop. With "Running servers", change that heading and any test/selector that matches it.
- Docs (coordinate with 10): user-facing prose in `docs/preview-networking.md`, `README.md` and `docs/reference/remote-access.md` that calls the list "Previews" uses the new label. Glossary prose keeps **Preview** for the concept.
- Leave `.github/workflows/*.yml` comments that say "agent previews" (historical timing notes) alone.

**Blocked by:** 08, and the user's answer

**Status:** done (Kotlin compiles and tests only in CI: no Android SDK on the implementing host; golden re-record is a manual follow-up)

**Parent:** `.scratch/web-removal/spec.md`

- [x] The user picked the label (record it in Comments with the date)
- [ ] Android ⋯ row, panel title and TalkBack pane title show the new label. String-pinning unit tests are updated and green (`./gradlew :app:testDebugUnitTest`).
- [ ] `PreviewFlowTest` finds the row by its new text. `android-emulator` CI is green.
- [x] Desktop heading matches the chosen label
- [x] `git grep -n '"Previews"' -- apps crates` → nothing user-facing (code comments describing Compose `@Preview` galleries are unrelated and stay)

## Comments

- 2026-10-28 (implementer): the user picked **"Ports"** (spec, "User decisions (2026-10-28)").
- Android `PreviewsCopy.kt`: `PageTitle = "Ports"` (the nav title and TalkBack pane title, so "Ports · <chat>") and `SessionMenuRow = "Ports"`. `RunningOnYourDevice`, `Loading`, `EmptyRemote`, `Unsupported` and the rest are unchanged. Its KDoc's `ui/src/browser/view.rs` reference now says "at tag `web-final`".
- String-pinning tests: `SessionMenuTest` and `SideChatsEntryTest` assert `"Ports"`. KDoc/comments updated in `SessionMenu.kt`, `PreviewPanelRoute.kt`, `ShellRoutes.kt`, `SessionMenuScreenshotTest.kt` and `PreviewFlowTest.kt`. `PreviewFlowTest` finds the row by its test tag (`session-menu-previews`, a test-tag constant, kept per ADR 0005), not its text, so only its comments changed.
- **Golden not re-recorded:** `apps/android/app/src/test/screenshots/session/session_menu_{light_unpinned,dark_pinned,light_readonly}.png` still show "Previews". No CI job runs `verifyRoborazziDebug`, so CI stays green, but the baseline is stale. Re-record on a host with the Android SDK: `cd apps/android && ./gradlew :app:recordRoborazziDebug --tests '*SessionMenuScreenshotTest*'`. If the parity manifest (`apps/android/parity/manifest.json`) pins those goldens' sha256, refresh it too. The screenshot test's KDoc says so.
- Desktop: the ports card heading is already `"Ports"` (`session_info.rs`); nothing changed. Docs: `docs/preview-networking.md` calls the list the Ports card / Android's Ports panel (ticket 10 commit).
- `git grep -n '"Previews"' -- apps crates` → only the screenshot test's KDoc note about the stale golden. Nothing user-facing.
- Not run here: `./gradlew :app:testDebugUnitTest` and the `android-emulator` job (CI on the PR).
