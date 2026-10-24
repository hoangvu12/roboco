package sh.roboco.android.session

/**
 * The ordered [ChromeFiller] factories (android-ui-parity #76 — ticket
 * 51's "a list composed by ticket 76"). Register at app start, before
 * the first session can open: the filler tickets (73 banner, 78 chips,
 * 79 canWrite, 82 queue, 89 chip menus, 91 questions) call [register]
 * from `RobocoApplication.onCreate`.
 *
 * [create] runs once per opened route, so each filler is a fresh
 * instance per session — per-session filler state (73's sticky send
 * failure) is never shared between two screens of the same chat. Screen
 * code reaches a route's own instance through 51's
 * `controller.filler<T>()`, never through this registry.
 */
class ChromeFillerRegistry {

    private data class Entry(
        val slot: Int,
        val seq: Int,
        val factory: (SessionCore) -> ChromeFiller,
    )

    private val entries = java.util.concurrent.CopyOnWriteArrayList<Entry>()
    private val seq = java.util.concurrent.atomic.AtomicInteger()

    /** Registers [factory] at [slot]; equal slots keep registration order. */
    fun register(slot: Int, factory: (SessionCore) -> ChromeFiller) {
        entries += Entry(slot, seq.getAndIncrement(), factory)
    }

    /**
     * One fresh filler per registration, in ascending slot order (ties:
     * registration order) — the iOS order of one `refresh()`
     * (`CoreSessionSource.swift:50-102`, see the ticket's slot table).
     * Called once per opened route, on the open thread (off main).
     */
    fun create(core: SessionCore): List<ChromeFiller> =
        entries.sortedWith(compareBy({ it.slot }, { it.seq })).map { it.factory(core) }

    companion object {
        /**
         * Ticket 79 (`canWrite`): first, so later fillers can read
         * `chrome.canWrite`.
         */
        const val CAN_WRITE = 10

        /** Ticket 78 (chips) — iOS `refresh()` runs the chips first. */
        const val CHIPS = 20

        /** Ticket 89 (chip menu state): right after the chips it decorates. */
        const val CHIP_MENUS = 21

        /** android-next#05 (plan usage): after the chip menus — it re-tints
         * #01's context chip at the worse of the two readings and feeds the
         * context menu's account half; it owns no chip of its own. */
        const val USAGE_CHIP = 22

        /** The failover port (orchestration v2 ticket 09 + 07's pills):
         * after the usage chip, before 73's banner — the Limited card's
         * chrome derivation and its actions. */
        const val LIMITED = 25

        /** Ticket 73 (banner). */
        const val BANNER = 30

        /** Ticket 91 (questions). */
        const val QUESTIONS = 40

        /** Ticket 82 (queue). */
        const val QUEUE = 50
    }
}
