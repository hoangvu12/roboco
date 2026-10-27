# 53 — Questions UI: answer chips in the transcript; "Close without answering" on desktop and Android (#1022, part 2)

**Lane:** 4a Features: engine + agents (UI + Android files). **Effort:** M.

**What to build:**
- **Desktop:** a resolved question renders as a chip showing what was
  picked (`InputOutcome`, `AnswerLine`, "header → Not answered";
  `transcript.rs` around upstream `:9959`). The composer's question panel
  gets "Close without answering · Esc" (`composer.rs` `wizard_dismiss`).
- **Mobile core:** `crates/mobile/src/layout/rows.rs` shows the answers.
  Today it says "Answered a question" (`rows.rs:444-446`).
- **Android:** the question panel gets the iPhone's corner ✕ ("Close
  without answering"), a 36 pt glass capsule that the text wraps clear
  of (`composer/QuestionCard.kt`, `QuestionActions.kt`).

**Upstream:** `64a26d359` (#1022), UI/mobile hunks;
`apps/ios/.../Composer/QuestionPanel.swift:5-6,77` (behaviour spec).

**Roboco files:** `crates/ui/src/{transcript,composer}.rs`,
`crates/mobile/src/layout/rows.rs`,
`apps/android/.../composer/{QuestionCard,QuestionActions}.kt`.

**Excluded:** iOS code.

**Proto change:** no (uses 52's).

**Blocked by:** 52, and 28 (`composer.rs` answer card), 32 (`rows.rs`).

**Status:** ready-for-agent

**Source:** `part-07.md` `64a26d359`; `pass3-ios-1.md` #1022;
`pass2-features-android.md` KNOWN table.

- [ ] Desktop chips list answers; Esc closes without answering and the run continues (UI + engine test)
- [ ] The Android ✕ closes without answering; the resolved row lists answers (layout test + golden)
- [ ] Commit records `64a26d359` (UI/mobile half)

## Comments
