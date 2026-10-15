package sh.roboco.android.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.Appearance

/**
 * The lab's launch-extra parsing (android-ui-parity #135) — the Android
 * form of the `-turns`/`-autostream`/… arguments. Plain JVM: the map is
 * exactly what `Bundle?.toExtrasMap()` produces for `am start`'s
 * `--ei`/`--ez`/`--es` forms.
 */
class LabOptionsTest {

    @Test
    fun defaultsWhenNoExtras() {
        val o = LabOptions.fromExtras(emptyMap())
        assertEquals(6, o.turns)
        assertFalse(o.autostream)
        assertFalse(o.top)
        assertFalse(o.autoscroll)
        assertFalse(o.meter)
        assertFalse(o.bench)
        assertNull(o.appearance)
    }

    @Test
    fun turnsFromIntOrString() {
        assertEquals(300, LabOptions.fromExtras(mapOf(LAB_EXTRA_TURNS to 300)).turns)
        assertEquals(300, LabOptions.fromExtras(mapOf(LAB_EXTRA_TURNS to "300")).turns)
        assertEquals(12, LabOptions.fromExtras(mapOf(LAB_EXTRA_TURNS to " 12 ")).turns)
        assertEquals(6, LabOptions.fromExtras(mapOf(LAB_EXTRA_TURNS to "abc")).turns)
        assertEquals(6, LabOptions.fromExtras(mapOf(LAB_EXTRA_TURNS to null)).turns)
        assertEquals(0, LabOptions.fromExtras(mapOf(LAB_EXTRA_TURNS to -3)).turns)
        assertEquals(0, LabOptions.fromExtras(mapOf(LAB_EXTRA_TURNS to "-3")).turns)
    }

    @Test
    fun booleansAcceptTrueAndCaseInsensitiveStrings() {
        assertTrue(LabOptions.fromExtras(mapOf(LAB_EXTRA_AUTOSTREAM to true)).autostream)
        assertTrue(LabOptions.fromExtras(mapOf(LAB_EXTRA_AUTOSTREAM to "TRUE")).autostream)
        assertTrue(LabOptions.fromExtras(mapOf(LAB_EXTRA_TOP to "true")).top)
        assertTrue(LabOptions.fromExtras(mapOf(LAB_EXTRA_AUTOSCROLL to true)).autoscroll)
        assertTrue(LabOptions.fromExtras(mapOf(LAB_EXTRA_METER to true)).meter)
        assertTrue(LabOptions.fromExtras(mapOf(LAB_EXTRA_BENCH to true)).bench)
        assertTrue(LabOptions.fromExtras(mapOf(LAB_EXTRA_IMAGES to true)).images)
        assertTrue(LabOptions.fromExtras(mapOf(LAB_EXTRA_IMAGES to "True")).images)
        assertFalse(LabOptions.fromExtras(mapOf(LAB_EXTRA_IMAGES to false)).images)
        assertFalse(LabOptions.fromExtras(emptyMap<String, Any?>()).images)
        assertFalse(LabOptions.fromExtras(mapOf(LAB_EXTRA_TOP to "false")).top)
        assertFalse(LabOptions.fromExtras(mapOf(LAB_EXTRA_TOP to false)).top)
        assertFalse(LabOptions.fromExtras(mapOf(LAB_EXTRA_TOP to null)).top)
        assertFalse(LabOptions.fromExtras(emptyMap<String, Any?>()).top)
    }

    @Test
    fun appearanceParsesCaseInsensitively() {
        assertEquals(Appearance.Dark, LabOptions.fromExtras(mapOf(LAB_EXTRA_APPEARANCE to "Dark")).appearance)
        assertEquals(Appearance.Light, LabOptions.fromExtras(mapOf(LAB_EXTRA_APPEARANCE to "LIGHT")).appearance)
        assertEquals(Appearance.System, LabOptions.fromExtras(mapOf(LAB_EXTRA_APPEARANCE to " system ")).appearance)
        assertNull(LabOptions.fromExtras(mapOf(LAB_EXTRA_APPEARANCE to "x")).appearance)
        assertNull(LabOptions.fromExtras(mapOf(LAB_EXTRA_APPEARANCE to null)).appearance)
    }
}
