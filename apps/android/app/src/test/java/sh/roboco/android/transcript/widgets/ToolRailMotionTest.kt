package sh.roboco.android.transcript.widgets

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The rail's pure decisions and timings on the plain JVM (ticket 121; no
 * Robolectric — nothing here touches Android): the [planRail] state table,
 * [ToolRailMemory]'s quiet window and `revealed` bookkeeping with a fake
 * clock, the [railSchedule] lists, [railValue] against both of the ticket's
 * reference tables (within 1e-3) and [railTotalMs].
 *
 * Every test installs a fake [ToolRailMemory.clock] before anything can
 * read it: the android.jar stub would otherwise throw.
 */
class ToolRailMotionTest {

    @Before
    fun reset() = ToolRailMemory.resetForTest()

    // ------------------------------------------------------------- planRail

    @Test
    fun planRailReturnsTheStateTable() {
        // quiet: always static, brandNew = known == null.
        assertEquals(RailPlan(null, true), planRail(known = null, count = 3, live = true, quiet = true))
        assertEquals(RailPlan(null, false), planRail(known = 2, count = 3, live = true, quiet = true))
        assertEquals(RailPlan(null, false), planRail(known = 3, count = 3, live = true, quiet = true))
        // not quiet, never seen: live groups draw in, finished groups are static.
        assertEquals(RailPlan(0, true), planRail(known = null, count = 3, live = true, quiet = false))
        assertEquals(RailPlan(null, true), planRail(known = null, count = 3, live = false, quiet = false))
        // known and grown: only the new rows animate — live is not consulted.
        assertEquals(RailPlan(2, false), planRail(known = 2, count = 5, live = true, quiet = false))
        assertEquals(RailPlan(2, false), planRail(known = 2, count = 5, live = false, quiet = false))
        // known with the same or a smaller count: static.
        assertEquals(RailPlan(null, false), planRail(known = 3, count = 3, live = true, quiet = false))
        assertEquals(RailPlan(null, false), planRail(known = 4, count = 3, live = true, quiet = false))
    }

    // -------------------------------------------------------- ToolRailMemory

    @Test
    fun theQuietWindowCoversAttachPlus799ButNot800() {
        var now = 1_000L
        ToolRailMemory.clock = { now }
        ToolRailMemory.onTranscriptAttached()
        assertEquals(1_000L + ToolRailMemory.QuietWindowMs, ToolRailMemory.quietUntilMs)
        assertEquals(800L, ToolRailMemory.QuietWindowMs)

        now += 799
        assertEquals("attach + 799 is quiet", RailPlan(null, true), ToolRailMemory.plan(7uL, 3, live = true, reduceMotion = false))
        now += 1
        assertEquals("attach + 800 is not quiet", RailPlan(0, true), ToolRailMemory.plan(8uL, 3, live = true, reduceMotion = false))
    }

    @Test
    fun revealedIsRecordedEvenWhileQuiet() {
        var now = 5_000L
        ToolRailMemory.clock = { now }
        ToolRailMemory.onTranscriptAttached()
        assertEquals("quiet: static", RailPlan(null, true), ToolRailMemory.plan(9uL, 4, live = true, reduceMotion = false))
        now += ToolRailMemory.QuietWindowMs
        // The same key was recorded as fully revealed while quiet.
        assertEquals("known == count: static", RailPlan(null, false), ToolRailMemory.plan(9uL, 4, live = true, reduceMotion = false))
        // Growing it later animates only the new rows.
        assertEquals(RailPlan(4, false), ToolRailMemory.plan(9uL, 6, live = false, reduceMotion = false))
    }

    @Test
    fun revealedIsRecordedUnderReduceMotion() {
        ToolRailMemory.clock = { 100L }
        assertEquals(RailPlan(null, true), ToolRailMemory.plan(10uL, 2, live = true, reduceMotion = true))
        assertEquals("still fully revealed", RailPlan(null, false), ToolRailMemory.plan(10uL, 2, live = true, reduceMotion = false))
        assertEquals("only the new row", RailPlan(2, false), ToolRailMemory.plan(10uL, 3, live = true, reduceMotion = false))
    }

    // ---------------------------------------------------------- railSchedule

    @Test
    fun theGrownGroupScheduleMatchesTheAcceptanceList() {
        assertEquals(
            listOf(
                RailAnim(RailPart.Continuation, 0, 0, 0.0, 0.45),
                RailAnim(RailPart.Incoming, 1, 0, 0.45, 0.72),
                RailAnim(RailPart.Branch, 1, 0, 0.68, 1.0),
                RailAnim(RailPart.Cover, 1, 0, 0.68, 1.0),
            ),
            railSchedule(first = 1, count = 2, brandNew = false),
        )
    }

    @Test
    fun theBrandNewScheduleLeadsInRow0AndStaggersRow1() {
        val s = railSchedule(first = 0, count = 2, brandNew = true)
        assertEquals(7, s.size)
        // Row 0 at 90 ms: no continuation, the "first" windows.
        assertEquals(RailAnim(RailPart.Incoming, 0, 90, 0.0, 0.62), s[0])
        assertEquals(RailAnim(RailPart.Branch, 0, 90, 0.58, 1.0), s[1])
        assertEquals(RailAnim(RailPart.Cover, 0, 90, 0.58, 1.0), s[2])
        // Row 1 at 155 ms, drawing row 0's continuation with the child windows.
        assertEquals(RailAnim(RailPart.Continuation, 0, 155, 0.0, 0.45), s[3])
        assertEquals(RailAnim(RailPart.Incoming, 1, 155, 0.45, 0.72), s[4])
        assertEquals(RailAnim(RailPart.Branch, 1, 155, 0.68, 1.0), s[5])
        assertEquals(RailAnim(RailPart.Cover, 1, 155, 0.68, 1.0), s[6])
    }

    @Test
    fun aBrandNewGroupIsFirstOnlyForRow0OfTheGroup() {
        // Three new rows: row 0 uses the first windows, rows 1 and 2 the
        // child windows — `isFirst` is `i == 0`, not `i == first`.
        val s = railSchedule(first = 0, count = 3, brandNew = true)
        assertEquals(RailAnim(RailPart.Incoming, 0, 90, 0.0, 0.62), s[0])
        assertEquals(RailAnim(RailPart.Incoming, 1, 155, 0.45, 0.72), s[4])
        assertEquals(RailAnim(RailPart.Incoming, 2, 220, 0.45, 0.72), s[8])
        assertEquals(RailAnim(RailPart.Continuation, 1, 220, 0.0, 0.45), s[7])
    }

    // ----------------------------------------------------------- railTotalMs

    @Test
    fun theTotalsMatchTheAcceptance() {
        assertEquals(700, railTotalMs(0, 3, brandNew = true))
        assertEquals(480, railTotalMs(2, 3, brandNew = false))
        assertEquals(635, railTotalMs(0, 2, brandNew = true))
    }

    // ------------------------------------------------------------ railValue

    /** The one-row parts of the ticket's first reference table. */
    private val cont = RailAnim(RailPart.Continuation, 1, 0, 0.0, 0.45)
    private val incFirst = RailAnim(RailPart.Incoming, 0, 0, 0.0, 0.62)
    private val incChild = RailAnim(RailPart.Incoming, 1, 0, 0.45, 0.72)
    private val brFirst = RailAnim(RailPart.Branch, 0, 0, 0.58, 1.0)
    private val brChild = RailAnim(RailPart.Branch, 1, 0, 0.68, 1.0)

    @Test
    fun railValueMatchesTheOneRowReferenceTable() {
        val parts = listOf(cont, incFirst, incChild, brFirst, brChild)
        // e (ms), then cont, incFirst, incChild, brFirst, brChild.
        val table = listOf(
            listOf(0f, 0f, 0f, 0f, 0f, 0f),
            listOf(20f, 0.4023f, 0.2920f, 0f, 0f, 0f),
            listOf(40f, 0.7614f, 0.5526f, 0f, 0f, 0f),
            listOf(60f, 1f, 0.7771f, 0.1177f, 0f, 0f),
            listOf(80f, 1f, 0.9638f, 0.5464f, 0.0417f, 0f),
            listOf(96f, 1f, 0.9928f, 0.8234f, 0.2198f, 0.0275f),
            listOf(120f, 1f, 1f, 1f, 0.4402f, 0.2652f),
            listOf(144f, 1f, 1f, 1f, 0.5982f, 0.4726f),
            listOf(192f, 1f, 1f, 1f, 0.8007f, 0.7385f),
            listOf(240f, 1f, 1f, 1f, 0.9081f, 0.8793f),
            listOf(360f, 1f, 1f, 1f, 0.9926f, 0.9903f),
            listOf(480f, 1f, 1f, 1f, 1f, 1f),
        )
        for (row in table) {
            val e = row[0]
            for (i in parts.indices) {
                assertEquals("e=${e}ms, ${parts[i].part}", row[i + 1], railValue(parts[i], e), 1e-3f)
            }
        }
    }

    @Test
    fun beforeTheStartStrokesAreZeroAndCoversOne() {
        val lateCont = RailAnim(RailPart.Continuation, 0, 65, 0.0, 0.45)
        val lateCover = RailAnim(RailPart.Cover, 1, 65, 0.68, 1.0)
        assertEquals(0f, railValue(lateCont, 0f), 1e-3f)
        assertEquals(0f, railValue(lateCont, 64.999f), 1e-3f)
        assertEquals(1f, railValue(lateCover, 0f), 1e-3f)
        assertEquals(1f, railValue(lateCover, 64.999f), 1e-3f)
    }

    @Test
    fun atAndAfterStartPlus480StrokesAreOneAndCoversZero() {
        val inc = RailAnim(RailPart.Incoming, 1, 65, 0.45, 0.72)
        val cover = RailAnim(RailPart.Cover, 1, 65, 0.68, 1.0)
        assertEquals(1f, railValue(inc, 65f + 480f), 1e-3f)
        assertEquals(0f, railValue(cover, 65f + 480f), 1e-3f)
        assertEquals(1f, railValue(inc, 65f + 10_000f), 1e-3f)
        assertEquals(0f, railValue(cover, 65f + 10_000f), 1e-3f)
    }

    /** One row of the ticket's whole-group example: a brand-new live 2-row group. */
    private data class GroupRow(
        val elapsed: Float,
        val inc0: Float,
        val br0: Float,
        val cover0: Float,
        val cont0: Float,
        val inc1: Float,
        val br1: Float,
        val cover1: Float,
    )

    @Test
    fun theTwoRowGroupMatchesTheWholeGroupExample() {
        // Row 0 starts at 90 ms, row 1 at 155 ms (the ticket's table).
        val s = railSchedule(first = 0, count = 2, brandNew = true)
        val table = listOf(
            GroupRow(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            GroupRow(90f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            GroupRow(110f, 0.2920f, 0f, 1f, 0f, 0f, 0f, 1f),
            GroupRow(175f, 0.9728f, 0.0974f, 0.9026f, 0.4023f, 0f, 0f, 1f),
            GroupRow(210f, 1f, 0.4402f, 0.5598f, 0.9404f, 0.0883f, 0f, 1f),
            GroupRow(275f, 1f, 0.7791f, 0.2209f, 1f, 1f, 0.2652f, 0.7348f),
            GroupRow(400f, 1f, 0.9747f, 0.0253f, 1f, 1f, 0.8881f, 0.1119f),
            GroupRow(635f, 1f, 1f, 0f, 1f, 1f, 1f, 0f),
        )
        for (r in table) {
            assertEquals("elapsed ${r.elapsed}: inc0", r.inc0, railValue(s[0], r.elapsed), 1e-3f)
            assertEquals("elapsed ${r.elapsed}: br0", r.br0, railValue(s[1], r.elapsed), 1e-3f)
            assertEquals("elapsed ${r.elapsed}: cover0", r.cover0, railValue(s[2], r.elapsed), 1e-3f)
            assertEquals("elapsed ${r.elapsed}: cont0", r.cont0, railValue(s[3], r.elapsed), 1e-3f)
            assertEquals("elapsed ${r.elapsed}: inc1", r.inc1, railValue(s[4], r.elapsed), 1e-3f)
            assertEquals("elapsed ${r.elapsed}: br1", r.br1, railValue(s[5], r.elapsed), 1e-3f)
            assertEquals("elapsed ${r.elapsed}: cover1", r.cover1, railValue(s[6], r.elapsed), 1e-3f)
        }
    }
}
