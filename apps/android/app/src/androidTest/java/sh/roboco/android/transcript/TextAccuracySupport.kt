package sh.roboco.android.transcript

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import sh.roboco.android.core.TextEngine
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.LayoutListener
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.TranscriptView

/**
 * Shared plumbing for the two text-accuracy instrumented tests (ticket 115):
 * the drift corpus (`LineBreakAccuracyTests.swift:9-27`, the 10 literals
 * plus every qualifying fixture paragraph), the shared [TextSystem], and
 * one logging channel — `Log.i` under the `RobocoTextAccuracy` tag plus
 * `println`, so `adb logcat -s RobocoTextAccuracy` and the instrumentation
 * output both carry every line. Logcat truncates near 4 000 characters,
 * so callers log one line at a time, never a joined report.
 *
 * CI-only: no emulator or device runs in the worker slots, so nothing here
 * is executed locally. Everything runs on the instrumentation thread —
 * never `@UiThreadTest`: [TextEngine.shared] and the FFI calls must stay
 * off the main thread (`debugLineStarts` drives the platform measurer
 * synchronously on the calling thread).
 */
internal object TextAccuracy {

    private const val TAG: String = "RobocoTextAccuracy"

    /**
     * The 10 corpus literals, verbatim from
     * `upstream/main:apps/ios/ZeronTests/LineBreakAccuracyTests.swift:10-21`
     * (UTF-16 lengths 116, 120, 121, 111, 115, 58, 96, 120, 121, 112).
     * The only Roboco change is string 4: `zeron-text` → `roboco-text`
     * (rename mapping; the crate is `roboco-text`). String 6's invisible
     * joiners are written as explicit escapes so no copy-paste can drop
     * them: U+200D (ZWJ, twice), U+FE0F (VS-16) and U+20E3 (combining
     * keycap); the visible emoji stay raw. Nothing else is escaped (no
     * `$` occurs in any string).
     */
    val CORPUS_LITERALS: List<String> = listOf(
        "The transcript is laid out analytically: every row's height is known before it is shown, so scrolling never guesses.",
        "Inline code spans get chips, links are tappable, and old ideas are struck through when they no longer apply to the plan.",
        "Nested bullet with a very/long/path/that/must/wrap/somewhere/in/the/middle/because/it/is/too/wide.rs and then more words.",
        "See https://example.com/a/very/long/url/that/keeps/going/and/going?query=parameters&more=stuff for the details.",
        "Run cargo test -p roboco-text --release -- --nocapture, then compare the numbers against the previous baseline run.",
        "CJK: 日本語のテキストも正しく折り返されます。中文也可以正确换行，不需要空格。한국어 문장도 줄바꿈이 됩니다.",
        "Emoji sequences 👩\u200D💻 🧑🏽\u200D🚀 🇯🇵 1\uFE0F\u20E3 never split, even when a line is tight 🚀✨🔥 around them.",
        "Numbers like 3,100 and 1.5×, dates like 2026-09-26, and times like 12:48 stay intact; so do e.g. and i.e. abbreviations.",
        "A sentence with an em—dash, an en–dash, “smart quotes”, and (parentheses) [brackets] {braces} that should break sensibly.",
        "supercalifragilisticexpialidocious_is_a_single_identifier_that_is_longer_than_any_phone_screen_is_wide_by_itself",
    )

    /**
     * The literals plus every fixture paragraph that qualifies
     * (`LineBreakAccuracyTests.swift:23-26`): split on blank lines, keep
     * `length > 40`, drop code blocks (leading three backticks) and
     * tables (leading `|`), and fold each newline into one space. Kotlin
     * `String.length` is UTF-16 — equivalent to Swift's `count` here, since
     * the excluded parts are ASCII and the CJK paragraph is far above 40
     * either way. The fixture is pinned to LF (android-app#05), so the
     * split is stable; a `\r` would yield fewer paragraphs and fail the
     * corpus-size precondition in the tests.
     */
    fun corpus(fixture: String): List<String> =
        CORPUS_LITERALS +
            fixture.split("\n\n")
                .filter { it.length > 40 && !it.startsWith("```") && !it.startsWith("|") }
                .map { it.replace("\n", " ") }

    /**
     * The process-wide engine (ticket 60). `RobocoApplication.onCreate`
     * has already run `FontGate.check` + `TextEngine.init` + `prewarm`;
     * the idempotent [TextEngine.init] call only guards a runner that
     * somehow started without the application, and [TextEngine.await]
     * builds on `Dispatchers.Default` if the prewarm has not finished.
     */
    fun textSystem(): TextSystem {
        TextEngine.init(InstrumentationRegistry.getInstrumentation().targetContext)
        return runBlocking { TextEngine.await() }
    }

    /** One report line to logcat (tag [TAG]) and stdout. */
    fun log(line: String) {
        Log.i(TAG, line)
        println(line)
    }

    /** The device locale of the run, recorded in every report header. */
    fun localeTag(): String = Locale.getDefault().toLanguageTag()
}

/**
 * A [LayoutListener] that ignores frame-ready notifications: the accuracy
 * tests poll [TranscriptView.frame] themselves on the instrumentation
 * thread, so the (layout-thread) callback only has to exist.
 */
internal object NoopListener : LayoutListener {
    override fun frameReady(revision: ULong) {}
}

/**
 * Polls [TranscriptView.frame] every 10 ms until it is the frame laid out
 * at [width] with at least one row, then hands it to the caller (who owns
 * closing it). Every frame fetched on the way — including non-matching
 * ones — is closed here, so no Rust handle leaks while waiting. Fails
 * after [timeoutMs] with `no frame at width …`.
 */
internal fun TranscriptView.awaitFrame(width: Float, timeoutMs: Long = 10_000): LayoutFrame {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (true) {
        val candidate = frame()
        if (candidate.width() == width && candidate.rowCount() > 0u) return candidate
        candidate.close()
        if (SystemClock.elapsedRealtime() >= deadline) fail("no frame at width $width")
        Thread.sleep(10)
    }
}

/**
 * `frame.use { … }` for fetched [LayoutFrame]s, the ticket's closing rule.
 * Declared locally because the stdlib's `use` for a bare `AutoCloseable`
 * lives in `kotlin.jdk7` (kotlin.io's applies to `java.io.Closeable`, which
 * [LayoutFrame] is not) and the generated bindings declare their own on
 * `Disposable`; an extension on the exact receiver type outranks every
 * one of them, so this can never be ambiguous — whichever of those is in
 * scope.
 */
internal inline fun <R> LayoutFrame.use(block: (LayoutFrame) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }
