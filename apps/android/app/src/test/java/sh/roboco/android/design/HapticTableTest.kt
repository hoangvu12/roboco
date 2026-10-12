package sh.roboco.android.design

import android.os.VibrationEffect
import android.os.VibrationEffect.Composition as C
import android.view.HapticFeedbackConstants as H
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rendering table (android-ui-parity #05): every spec value, the view
 * constants per SDK, and every cell of the expected-plans grid over the six
 * capability fixtures. The values below are the ticket's tables, not the
 * implementation's, so a wrong ladder or a mislabeled constant fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HapticTableTest {

    /** The `ALL` fixture: the 8 probed primitives. */
    private val all = setOf(
        C.PRIMITIVE_CLICK, C.PRIMITIVE_TICK, C.PRIMITIVE_LOW_TICK, C.PRIMITIVE_THUD,
        C.PRIMITIVE_SPIN, C.PRIMITIVE_QUICK_RISE, C.PRIMITIVE_SLOW_RISE, C.PRIMITIVE_QUICK_FALL,
    )

    // Fixtures from the ticket.
    private val f31 = HapticCapabilities(31, all, true)
    private val f33 = HapticCapabilities(33, all, true)
    private val f34 = HapticCapabilities(34, all, true)
    private val bare = HapticCapabilities(31, emptySet(), true)
    private val noView = HapticCapabilities(34, all, false)
    private val clickOnly = HapticCapabilities(31, setOf(C.PRIMITIVE_CLICK), true)
    private val devices = listOf(f31, f33, f34, bare, noView, clickOnly)

    @Test
    fun theVocabularyIsTheElevenMomentsInOrder() {
        assertEquals(
            listOf("Select", "Confirm", "Stop", "Light", "LongPress", "Threshold", "ToggleOn", "ToggleOff", "Success", "Attention", "Error"),
            Haptic.entries.map { it.name },
        )
        assertEquals(listOf("Done", "Request", "Attention"), Cue.entries.map { it.name })
    }

    @Test
    fun everyHapticHasASpec() {
        assertEquals(Haptic.entries.size, HapticTable.all.size)
        for (h in Haptic.entries) {
            val s = HapticTable.spec(h)
            assertEquals(h, s.haptic)
            assertTrue("$h needs compositions", s.compositions.isNotEmpty())
            assertTrue("$h gap must be positive", s.minGapMs > 0)
        }
        assertEquals(ALL_PRIMITIVES, all.toList())
    }

    @Test
    fun theRenderingTableIsVerbatim() {
        // haptic, preferView, compositions, predefined, priority, minGapMs
        val table = listOf(
            row(Haptic.Select, true, listOf(listOf(Step(C.PRIMITIVE_TICK, 0.7f)), listOf(Step(C.PRIMITIVE_CLICK, 0.35f))), VibrationEffect.EFFECT_TICK, 0, 50L),
            row(Haptic.Confirm, true, listOf(listOf(Step(C.PRIMITIVE_CLICK, 0.65f)), listOf(Step(C.PRIMITIVE_TICK, 0.9f))), VibrationEffect.EFFECT_CLICK, 1, 120L),
            row(Haptic.Stop, false, listOf(listOf(Step(C.PRIMITIVE_CLICK, 0.9f)), listOf(Step(C.PRIMITIVE_TICK, 1.0f))), VibrationEffect.EFFECT_CLICK, 1, 120L),
            row(Haptic.Light, false, listOf(listOf(Step(C.PRIMITIVE_CLICK, 0.4f)), listOf(Step(C.PRIMITIVE_TICK, 0.6f))), VibrationEffect.EFFECT_TICK, 1, 80L),
            row(Haptic.LongPress, true, listOf(listOf(Step(C.PRIMITIVE_CLICK, 0.7f)), listOf(Step(C.PRIMITIVE_TICK, 1.0f))), VibrationEffect.EFFECT_CLICK, 1, 300L),
            row(Haptic.Threshold, true, listOf(listOf(Step(C.PRIMITIVE_CLICK, 0.55f)), listOf(Step(C.PRIMITIVE_TICK, 0.9f))), VibrationEffect.EFFECT_CLICK, 1, 150L),
            row(Haptic.ToggleOn, true, listOf(listOf(Step(C.PRIMITIVE_LOW_TICK, 0.5f), Step(C.PRIMITIVE_TICK, 0.8f, 20)), listOf(Step(C.PRIMITIVE_TICK, 0.5f), Step(C.PRIMITIVE_CLICK, 0.6f, 25))), VibrationEffect.EFFECT_CLICK, 1, 80L),
            row(Haptic.ToggleOff, true, listOf(listOf(Step(C.PRIMITIVE_TICK, 0.7f), Step(C.PRIMITIVE_LOW_TICK, 0.4f, 20)), listOf(Step(C.PRIMITIVE_CLICK, 0.5f), Step(C.PRIMITIVE_TICK, 0.4f, 25))), VibrationEffect.EFFECT_TICK, 1, 80L),
            row(Haptic.Success, false, listOf(listOf(Step(C.PRIMITIVE_QUICK_RISE, 0.45f), Step(C.PRIMITIVE_LOW_TICK, 0.55f, 40)), listOf(Step(C.PRIMITIVE_CLICK, 0.5f), Step(C.PRIMITIVE_TICK, 0.5f, 60))), VibrationEffect.EFFECT_CLICK, 2, 400L),
            row(Haptic.Attention, false, listOf(listOf(Step(C.PRIMITIVE_TICK, 0.85f), Step(C.PRIMITIVE_TICK, 0.85f, 90)), listOf(Step(C.PRIMITIVE_CLICK, 0.6f), Step(C.PRIMITIVE_CLICK, 0.6f, 90))), VibrationEffect.EFFECT_DOUBLE_CLICK, 2, 400L),
            row(Haptic.Error, false, listOf(listOf(Step(C.PRIMITIVE_CLICK, 1.0f), Step(C.PRIMITIVE_THUD, 0.8f, 60)), listOf(Step(C.PRIMITIVE_CLICK, 1.0f), Step(C.PRIMITIVE_CLICK, 0.9f, 70))), VibrationEffect.EFFECT_HEAVY_CLICK, 3, 400L),
        )
        for (r in table) {
            val s = HapticTable.spec(r.haptic)
            assertEquals("${r.haptic} preferView", r.preferView, s.preferView)
            assertEquals("${r.haptic} compositions", r.compositions, s.compositions)
            assertEquals("${r.haptic} predefined", r.predefined, s.predefined)
            assertEquals("${r.haptic} priority", r.priority, s.priority)
            assertEquals("${r.haptic} minGapMs", r.minGapMs, s.minGapMs)
        }
    }

    @Test
    fun viewConstantsPerSdk() {
        for (s in 31..34) {
            assertEquals("Select $s", if (s >= 34) H.SEGMENT_TICK else H.CONTEXT_CLICK, HapticTable.spec(Haptic.Select).view(s))
            assertEquals("Confirm $s", H.CONFIRM, HapticTable.spec(Haptic.Confirm).view(s))
            assertEquals("Stop $s", H.REJECT, HapticTable.spec(Haptic.Stop).view(s))
            assertEquals("Light $s", H.KEYBOARD_TAP, HapticTable.spec(Haptic.Light).view(s))
            assertEquals("LongPress $s", H.LONG_PRESS, HapticTable.spec(Haptic.LongPress).view(s))
            assertEquals("Success $s", H.CONFIRM, HapticTable.spec(Haptic.Success).view(s))
            assertEquals("Attention $s", H.REJECT, HapticTable.spec(Haptic.Attention).view(s))
            assertEquals("Error $s", H.REJECT, HapticTable.spec(Haptic.Error).view(s))
            if (s >= 34) {
                assertEquals("Threshold $s", H.GESTURE_THRESHOLD_ACTIVATE, HapticTable.spec(Haptic.Threshold).view(s))
                assertEquals("ToggleOn $s", H.TOGGLE_ON, HapticTable.spec(Haptic.ToggleOn).view(s))
                assertEquals("ToggleOff $s", H.TOGGLE_OFF, HapticTable.spec(Haptic.ToggleOff).view(s))
            } else {
                assertNull("Threshold $s", HapticTable.spec(Haptic.Threshold).view(s))
                assertNull("ToggleOn $s", HapticTable.spec(Haptic.ToggleOn).view(s))
                assertNull("ToggleOff $s", HapticTable.spec(Haptic.ToggleOff).view(s))
            }
        }
    }

    @Test
    fun plansOnApi31WithAllPrimitivesAndAView() {
        assertEquals(HapticPlan.ViewConstant(H.CONTEXT_CLICK), HapticPlanner.plan(Haptic.Select, f31))
        assertEquals(HapticPlan.ViewConstant(H.CONFIRM), HapticPlanner.plan(Haptic.Confirm, f31))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 0.9f))), HapticPlanner.plan(Haptic.Stop, f31))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 0.4f))), HapticPlanner.plan(Haptic.Light, f31))
        assertEquals(HapticPlan.ViewConstant(H.LONG_PRESS), HapticPlanner.plan(Haptic.LongPress, f31))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 0.55f))), HapticPlanner.plan(Haptic.Threshold, f31))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_LOW_TICK, 0.5f), Step(C.PRIMITIVE_TICK, 0.8f, 20))), HapticPlanner.plan(Haptic.ToggleOn, f31))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_TICK, 0.7f), Step(C.PRIMITIVE_LOW_TICK, 0.4f, 20))), HapticPlanner.plan(Haptic.ToggleOff, f31))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_QUICK_RISE, 0.45f), Step(C.PRIMITIVE_LOW_TICK, 0.55f, 40))), HapticPlanner.plan(Haptic.Success, f31))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_TICK, 0.85f), Step(C.PRIMITIVE_TICK, 0.85f, 90))), HapticPlanner.plan(Haptic.Attention, f31))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 1.0f), Step(C.PRIMITIVE_THUD, 0.8f, 60))), HapticPlanner.plan(Haptic.Error, f31))
    }

    @Test
    fun plansOnApi33MatchApi31() {
        for (h in Haptic.entries) assertEquals(h.toString(), HapticPlanner.plan(h, f31), HapticPlanner.plan(h, f33))
    }

    @Test
    fun plansOnApi34UseTheDedicatedConstants() {
        assertEquals(HapticPlan.ViewConstant(H.SEGMENT_TICK), HapticPlanner.plan(Haptic.Select, f34))
        assertEquals(HapticPlan.ViewConstant(H.CONFIRM), HapticPlanner.plan(Haptic.Confirm, f34))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 0.9f))), HapticPlanner.plan(Haptic.Stop, f34))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 0.4f))), HapticPlanner.plan(Haptic.Light, f34))
        assertEquals(HapticPlan.ViewConstant(H.LONG_PRESS), HapticPlanner.plan(Haptic.LongPress, f34))
        assertEquals(HapticPlan.ViewConstant(H.GESTURE_THRESHOLD_ACTIVATE), HapticPlanner.plan(Haptic.Threshold, f34))
        assertEquals(HapticPlan.ViewConstant(H.TOGGLE_ON), HapticPlanner.plan(Haptic.ToggleOn, f34))
        assertEquals(HapticPlan.ViewConstant(H.TOGGLE_OFF), HapticPlanner.plan(Haptic.ToggleOff, f34))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_QUICK_RISE, 0.45f), Step(C.PRIMITIVE_LOW_TICK, 0.55f, 40))), HapticPlanner.plan(Haptic.Success, f34))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_TICK, 0.85f), Step(C.PRIMITIVE_TICK, 0.85f, 90))), HapticPlanner.plan(Haptic.Attention, f34))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 1.0f), Step(C.PRIMITIVE_THUD, 0.8f, 60))), HapticPlanner.plan(Haptic.Error, f34))
    }

    @Test
    fun plansOnAPrimitivelessDeviceFallBackToViewConstants() {
        assertEquals(HapticPlan.ViewConstant(H.CONTEXT_CLICK), HapticPlanner.plan(Haptic.Select, bare))
        assertEquals(HapticPlan.ViewConstant(H.CONFIRM), HapticPlanner.plan(Haptic.Confirm, bare))
        assertEquals(HapticPlan.ViewConstant(H.REJECT), HapticPlanner.plan(Haptic.Stop, bare))
        assertEquals(HapticPlan.ViewConstant(H.KEYBOARD_TAP), HapticPlanner.plan(Haptic.Light, bare))
        assertEquals(HapticPlan.ViewConstant(H.LONG_PRESS), HapticPlanner.plan(Haptic.LongPress, bare))
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_CLICK), HapticPlanner.plan(Haptic.Threshold, bare))
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_CLICK), HapticPlanner.plan(Haptic.ToggleOn, bare))
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_TICK), HapticPlanner.plan(Haptic.ToggleOff, bare))
        assertEquals(HapticPlan.ViewConstant(H.CONFIRM), HapticPlanner.plan(Haptic.Success, bare))
        assertEquals(HapticPlan.ViewConstant(H.REJECT), HapticPlanner.plan(Haptic.Attention, bare))
        assertEquals(HapticPlan.ViewConstant(H.REJECT), HapticPlanner.plan(Haptic.Error, bare))
    }

    @Test
    fun plansWithoutAViewUseCompositions() {
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_TICK, 0.7f))), HapticPlanner.plan(Haptic.Select, noView))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 0.65f))), HapticPlanner.plan(Haptic.Confirm, noView))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 0.9f))), HapticPlanner.plan(Haptic.Stop, noView))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 0.4f))), HapticPlanner.plan(Haptic.Light, noView))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 0.7f))), HapticPlanner.plan(Haptic.LongPress, noView))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 0.55f))), HapticPlanner.plan(Haptic.Threshold, noView))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_LOW_TICK, 0.5f), Step(C.PRIMITIVE_TICK, 0.8f, 20))), HapticPlanner.plan(Haptic.ToggleOn, noView))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_TICK, 0.7f), Step(C.PRIMITIVE_LOW_TICK, 0.4f, 20))), HapticPlanner.plan(Haptic.ToggleOff, noView))
        for (h in listOf(Haptic.Success, Haptic.Attention, Haptic.Error)) {
            assertEquals(h.toString(), HapticPlanner.plan(h, f34), HapticPlanner.plan(h, noView))
        }
    }

    @Test
    fun plansOnAClickOnlyDevice() {
        assertEquals(HapticPlan.ViewConstant(H.CONTEXT_CLICK), HapticPlanner.plan(Haptic.Select, clickOnly))
        assertEquals(HapticPlan.ViewConstant(H.CONFIRM), HapticPlanner.plan(Haptic.Confirm, clickOnly))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 0.9f))), HapticPlanner.plan(Haptic.Stop, clickOnly))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 0.4f))), HapticPlanner.plan(Haptic.Light, clickOnly))
        assertEquals(HapticPlan.ViewConstant(H.LONG_PRESS), HapticPlanner.plan(Haptic.LongPress, clickOnly))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 0.55f))), HapticPlanner.plan(Haptic.Threshold, clickOnly))
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_CLICK), HapticPlanner.plan(Haptic.ToggleOn, clickOnly))
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_TICK), HapticPlanner.plan(Haptic.ToggleOff, clickOnly))
        assertEquals(HapticPlan.ViewConstant(H.CONFIRM), HapticPlanner.plan(Haptic.Success, clickOnly))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 0.6f), Step(C.PRIMITIVE_CLICK, 0.6f, 90))), HapticPlanner.plan(Haptic.Attention, clickOnly))
        assertEquals(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_CLICK, 1.0f), Step(C.PRIMITIVE_CLICK, 0.9f, 70))), HapticPlanner.plan(Haptic.Error, clickOnly))
    }

    @Test
    fun noCompositionPlanContainsAnUnsupportedPrimitive() {
        for (h in Haptic.entries) for (caps in devices) {
            val plan = HapticPlanner.plan(h, caps)
            if (plan is HapticPlan.Composition) {
                assertTrue("$h on $caps", plan.steps.all { it.primitive in caps.primitives })
            }
        }
    }

    @Test
    fun toggleOnRisesAndToggleOffFalls() {
        val on = HapticTable.spec(Haptic.ToggleOn).compositions.first()
        val off = HapticTable.spec(Haptic.ToggleOff).compositions.first()
        assertTrue("ToggleOn rises", on.last().scale > on.first().scale)
        assertTrue("ToggleOff falls", off.last().scale < off.first().scale)
        assertNotEquals(HapticPlanner.plan(Haptic.ToggleOn, f31), HapticPlanner.plan(Haptic.ToggleOff, f31))
    }

    @Test
    fun describeNamesPlansTheWayTheGalleryAndLogsPrintThem() {
        assertEquals("View SEGMENT_TICK", HapticPlanner.describe(HapticPlan.ViewConstant(H.SEGMENT_TICK)))
        assertEquals("View CONTEXT_CLICK", HapticPlanner.describe(HapticPlan.ViewConstant(H.CONTEXT_CLICK)))
        assertEquals("Comp CLICK 0.90", HapticPlanner.describe(HapticPlanner.plan(Haptic.Stop, noView)))
        assertEquals(
            "Comp LOW_TICK 0.50 + TICK 0.80 @20",
            HapticPlanner.describe(HapticPlan.Composition(listOf(Step(C.PRIMITIVE_LOW_TICK, 0.5f), Step(C.PRIMITIVE_TICK, 0.8f, 20)))),
        )
        assertEquals("Predef EFFECT_CLICK", HapticPlanner.describe(HapticPlan.Predefined(VibrationEffect.EFFECT_CLICK)))
        assertEquals(
            listOf("CLICK", "TICK", "LOW_TICK", "THUD", "SPIN", "QUICK_RISE", "SLOW_RISE", "QUICK_FALL"),
            ALL_PRIMITIVES.map { HapticPlanner.primitiveName(it) },
        )
    }

    @Test
    fun iosSourceNamesWhereTheMomentComesFrom() {
        assertEquals("selection · ComposerBar.swift:371", HapticTable.iosSource(Haptic.Select))
        assertEquals("impact .medium · ComposerBar.swift:653", HapticTable.iosSource(Haptic.Confirm))
        assertEquals("impact .rigid · ComposerBar.swift:642", HapticTable.iosSource(Haptic.Stop))
        assertEquals("impact .light · RowView.swift:335", HapticTable.iosSource(Haptic.Light))
        assertEquals("UIKit context menu", HapticTable.iosSource(Haptic.LongPress))
        assertEquals("UIKit full swipe / refresh", HapticTable.iosSource(Haptic.Threshold))
        assertEquals("UISwitch on", HapticTable.iosSource(Haptic.ToggleOn))
        assertEquals("UISwitch off", HapticTable.iosSource(Haptic.ToggleOff))
        assertEquals("reserved (android-app#28)", HapticTable.iosSource(Haptic.Success))
        assertEquals("reserved (android-app#28)", HapticTable.iosSource(Haptic.Attention))
        assertEquals("reserved (android-app#28)", HapticTable.iosSource(Haptic.Error))
    }

    /** One expected row of the rendering table. */
    private data class Row(
        val haptic: Haptic,
        val preferView: Boolean,
        val compositions: List<List<Step>>,
        val predefined: Int,
        val priority: Int,
        val minGapMs: Long,
    )

    private fun row(haptic: Haptic, preferView: Boolean, compositions: List<List<Step>>, predefined: Int, priority: Int, minGapMs: Long) =
        Row(haptic, preferView, compositions, predefined, priority, minGapMs)
}
