package sh.roboco.android.session

/**
 * Fills [SessionChrome.questions] from the composer state's open input
 * request (android-ui-parity #91) — the port of
 * `ios:Session/CoreSessionSource.swift:90-94`: `openInput` present →
 * `Questions(requestId, items)` with one `Question` per FFI
 * `UserInputQuestion`, field for field; absent → `null` (the panel
 * keeps its last items while the slot collapses — 74).
 *
 * The `entryId` of the FFI `InputRequest` is dropped, as on iOS. No FFI
 * call of its own: everything it needs is already in [ChromeInput].
 * Roboco's wire has no `prefill`/`multiline` (android-app#08 maps them
 * to `null`/`false`), but both are carried verbatim for a newer engine.
 */
class QuestionsChromeFiller : ChromeFiller {
    override fun fill(chrome: SessionChrome, input: ChromeInput): SessionChrome =
        chrome.copy(
            questions = input.composer.openInput?.let { r ->
                SessionChrome.Questions(
                    requestId = r.requestId,
                    items = r.questions.map { q ->
                        SessionChrome.Question(
                            id = q.id,
                            header = q.header,
                            text = q.question,
                            options = q.options,
                            multiSelect = q.multiSelect,
                            prefill = q.prefill,
                            multiline = q.multiline,
                        )
                    },
                )
            },
        )
}
