package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stale-row decision (ticket 117; iOS
 * `ios:Transcript/TranscriptListView.swift:377-385`): all eight rows of
 * the Spec's table. `cur*` are the painted model's display fields,
 * `null` before the first build. Plain JVM.
 */
class ModelDecisionTest {

    @Test
    fun aRowWithoutAPaintedModelUsesTheCacheWhenPresentElseBuildsSynchronously() {
        assertEquals(ModelAction.CACHED, decideModel(null, null, null, 5uL, 9uL, 411f, cached = true))
        assertEquals(ModelAction.SYNC, decideModel(null, null, null, 5uL, 9uL, 411f, cached = false))
    }

    @Test
    fun anUnchangedPaintedModelIsKeptEvenWhenTheCacheMisses() {
        assertEquals(ModelAction.KEEP, decideModel(5uL, 9uL, 411f, 5uL, 9uL, 411f, cached = false))
        assertEquals(ModelAction.KEEP, decideModel(5uL, 9uL, 411f, 5uL, 9uL, 411f, cached = true))
    }

    @Test
    fun sameKeySameWidthNewVersionUpgradesAsyncUnlessCached() {
        assertEquals(ModelAction.CACHED, decideModel(5uL, 9uL, 411f, 5uL, 10uL, 411f, cached = true))
        assertEquals(ModelAction.UPGRADE, decideModel(5uL, 9uL, 411f, 5uL, 10uL, 411f, cached = false))
    }

    @Test
    fun aWidthChangeBuildsSynchronouslyUnlessCached() {
        assertEquals(ModelAction.SYNC, decideModel(5uL, 9uL, 411f, 5uL, 10uL, 390f, cached = false))
        assertEquals(ModelAction.CACHED, decideModel(5uL, 9uL, 411f, 5uL, 10uL, 390f, cached = true))
    }

    @Test
    fun aDifferentKeyBuildsSynchronously() {
        assertEquals(ModelAction.SYNC, decideModel(5uL, 9uL, 411f, 6uL, 9uL, 411f, cached = false))
        assertEquals(ModelAction.CACHED, decideModel(5uL, 9uL, 411f, 6uL, 9uL, 411f, cached = true))
    }

    @Test
    fun aKeptRowNeverReachesTheCacheBranches() {
        // KEEP is decided before the cached flag is even consulted.
        assertEquals(ModelAction.KEEP, decideModel(0uL, 0uL, 0f, 0uL, 0uL, 0f, cached = true))
    }
}
