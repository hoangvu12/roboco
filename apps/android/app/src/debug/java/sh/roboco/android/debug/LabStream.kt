package sh.roboco.android.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uniffi.roboco_core.DebugEntry
import kotlin.random.Random

/**
 * The pure constants and fixture recipe of the Transcript Lab
 * (android-ui-parity #135) — the values the iOS view controller carries
 * as literals (`TranscriptLabViewController.swift:57-77, 182-186`), kept
 * verbatim so the Roboco lab streams exactly like the iOS one.
 */
object LabScript {

    /** `prompts`, `TranscriptLabViewController.swift:182-186`, verbatim. */
    val Prompts = listOf(
        "How does the layout engine avoid measuring text on the main thread?",
        "Show me the plan again with the code sample and the table.",
        "Can you explain why rows never jump when content streams in above the viewport?",
    )

    /** The `-autostream` message (`:76`). */
    const val AutostreamPrompt = "Stream the plan again, slowly."

    /** `Timer.scheduledTimer(withTimeInterval: 0.045, …)` (`:113`). */
    const val TickMillis = 45L

    /** `-top` fires 0.6 s after the first composition (`:71`). */
    const val TopDelayMillis = 600L

    /** `-autostream` fires 1.0 s after the first composition (`:75`). */
    const val AutostreamDelayMillis = 1_000L

    /** `-autoscroll`/`-bench` fire 1.5 s after the first composition (`:79, :82`). */
    const val AutoscrollDelayMillis = 1_500L

    /** One `autoscroll` leg (`UIView.animate(withDuration: 4, …)`, `:139/:143`). */
    const val AutoscrollLegMillis = 4_000L

    /** The pause between the two legs (`delay: 0.3`, `:143`). */
    const val AutoscrollPauseMillis = 300L

    /**
     * The fixture turns (`:57-62`): turn *i* is the user prompt
     * `Prompts[i % 3]` followed by the full fixture markdown, ids
     * `"u$i"` / `"a$i"`, nothing streaming. A negative turn count reads
     * as zero.
     */
    fun initialEntries(turns: Int, fixture: String): List<DebugEntry> =
        (0 until turns.coerceAtLeast(0)).flatMap { i ->
            listOf(
                DebugEntry(id = "u$i", user = true, text = Prompts[i % Prompts.size], streaming = false),
                DebugEntry(id = "a$i", user = false, text = fixture, streaming = false),
            )
        }
}

/**
 * The lab's entry list and stream script (android-ui-parity #135) — the
 * port of `TranscriptLabViewController.swift:102-134` as pure state:
 * [seed] is the launch fixture, [begin] is `send(_:)` minus the ticker
 * and the scroll, [tick] is one timer fire and [stop] is `stopStream()`
 * minus the timer cancel. The controller owns the timing and the
 * `scrollToBottom`; the push callback hands every change to
 * `TranscriptView.setDebugEntries`.
 *
 * Pure Kotlin: no Android, no native calls (constructing a [DebugEntry]
 * loads nothing), so the unit tests run on the plain JVM. Entries are
 * **replaced**, never mutated in place — the core owns the pushed list.
 */
class LabStream(
    private val fixture: String,
    private val push: (entries: List<DebugEntry>, working: Boolean) -> Unit,
    private val nextStep: () -> Int = { Random.nextInt(2, 6) },
) {
    /** The fixture words: `split(" ")`, which keeps empty strings (iOS `omittingEmptySubsequences: false`). */
    private val words: List<String> = fixture.split(" ")

    /** The lab's entry list — a new list on every change. */
    var entries: List<DebugEntry> = emptyList()
        private set

    /** iOS `composer.running`: drives the Send/Stop button state. */
    var running by mutableStateOf(false)
        private set

    /** The next word index to stream (`i`, `TranscriptLabViewController.swift:118`). */
    private var cursor = 0

    /** The launch fixture: `entries` plus the one `working = false` push. [extra] (137) appends after the fixture turns — the lab's image prompts. */
    fun seed(turns: Int, extra: List<DebugEntry> = emptyList()) {
        entries = LabScript.initialEntries(turns, fixture) + extra
        push(entries, false)
    }

    /**
     * Steps 1–5 of `send(_:)` minus the scroll: `stopStream()` (the old
     * live entry finishes with one `working = false` push, `:112`), then
     * append the user row `u<n>` and the empty streaming `a<n>` where
     * `n = entries.size`. **Does not push** — the new rows reach the
     * engine on the first tick, 45 ms later, together with the first
     * chunk (iOS `:102-110`, a ticket test pins this).
     */
    fun begin(text: String) {
        stop()
        val n = entries.size
        entries = entries + listOf(
            DebugEntry(id = "u$n", user = true, text = text, streaming = false),
            DebugEntry(id = "a$n", user = false, text = "", streaming = true),
        )
        cursor = 0
        running = true
    }

    /**
     * One timer fire (`:115-131`): 2–5 fixture words joined with `" "`
     * appended to the live entry, `working` while words remain. Returns
     * `true` while the stream is still running; the fire that consumes
     * the last word pushes `working = false` and returns `false`. A
     * no-op returning `false` when not running.
     */
    fun tick(): Boolean {
        if (!running) return false
        val step = nextStep()
        val next = words.subList(cursor, minOf(words.size, cursor + step)).joinToString(" ")
        cursor += step
        val last = entries.last()
        val text = last.text + (if (last.text.isEmpty()) "" else " ") + next
        val streaming = cursor < words.size
        entries = entries.dropLast(1) + last.copy(text = text, streaming = streaming)
        push(entries, streaming)
        if (!streaming) running = false
        return streaming
    }

    /**
     * `stopStream()` minus the timer cancel (`:124-134`): `running`
     * off, and if the last entry was streaming it is frozen where it is
     * and one `working = false` push lands. Idempotent — a second call
     * pushes nothing.
     */
    fun stop() {
        running = false
        val last = entries.lastOrNull() ?: return
        if (last.streaming) {
            entries = entries.dropLast(1) + last.copy(streaming = false)
            push(entries, false)
        }
    }
}
