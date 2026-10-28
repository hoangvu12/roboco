package sh.roboco.android.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Wave-10 43: the trim levels that shrink the warm sessions' budget. */
class MemoryTrimTest {

    @Test
    fun theLevelsMapToNoTrimAShrunkBudgetOrCritical() {
        assertNull(trimIsCritical(TrimLevels.RUNNING_MODERATE))
        assertEquals(false, trimIsCritical(TrimLevels.RUNNING_LOW))
        assertEquals(true, trimIsCritical(TrimLevels.RUNNING_CRITICAL))
        assertNull("backgrounding is the lifecycle observer's", trimIsCritical(TrimLevels.UI_HIDDEN))
        assertEquals(true, trimIsCritical(TrimLevels.BACKGROUND))
        assertEquals(true, trimIsCritical(TrimLevels.MODERATE))
        assertEquals(true, trimIsCritical(TrimLevels.COMPLETE))
        // The framework's values (several constants are deprecated from API 34).
        assertEquals(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, TrimLevels.RUNNING_LOW)
        assertEquals(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, TrimLevels.UI_HIDDEN)
    }
}
