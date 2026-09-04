package org.pubky.pulse.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [PulseQuestionnaireState] (SharedPreferences-backed counters) and the
 * pure [PulseQuestionnaireTrigger] / [PulseQuestionnaireCondition] evaluation.
 * Robolectric supplies a real [SharedPreferences]. Mirrors the Swift
 * `PulseQuestionnaireState` + trigger tests.
 */
@RunWith(RobolectricTestRunner::class)
class QuestionnaireStateTriggerTest {

    private lateinit var state: PulseQuestionnaireState

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test.questionnaire.state", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        state = PulseQuestionnaireState(prefs)
    }

    @Test
    fun markConfiguredOnceBumpsLaunchAndIsIdempotentPerProcess() {
        assertEquals(0, state.launchCount)
        assertNull(state.firstLaunchAt)

        state.markConfiguredOnce(now = 1_000L)
        assertEquals(1, state.launchCount)
        assertEquals(1_000L, state.firstLaunchAt)

        // Second call in the same process is a no-op.
        state.markConfiguredOnce(now = 2_000L)
        assertEquals(1, state.launchCount)
        assertEquals(1_000L, state.firstLaunchAt) // first_launch_at not overwritten
    }

    @Test
    fun incrementForegroundCounts() {
        assertEquals(0, state.foregroundCount)
        state.incrementForeground()
        state.incrementForeground()
        assertEquals(2, state.foregroundCount)
    }

    @Test
    fun snapshotDerivesElapsedTime() {
        state.markConfiguredOnce(now = 0L)
        val snap = state.snapshot(now = 2 * 86_400_000L) // 2 days later
        assertEquals(2.0, snap.daysSinceFirstLaunch(), 0.001)
        assertEquals(48.0, snap.hoursSinceFirstLaunch(), 0.001)
    }

    @Test
    fun snapshotWithoutFirstLaunchIsZeroElapsed() {
        val snap = state.snapshot(now = 9_999L)
        assertEquals(0.0, snap.daysSinceFirstLaunch(), 0.001)
        assertEquals(0.0, snap.hoursSinceFirstLaunch(), 0.001)
    }

    @Test
    fun debugResetClearsCountersAndConfiguredFlag() {
        state.markConfiguredOnce(now = 1L)
        state.incrementForeground()
        state.debugReset()
        assertEquals(0, state.launchCount)
        assertEquals(0, state.foregroundCount)
        assertNull(state.firstLaunchAt)
        // After reset, markConfiguredOnce works again (didMarkConfigured cleared).
        state.markConfiguredOnce(now = 5L)
        assertEquals(1, state.launchCount)
    }

    @Test
    fun conditionsEvaluateAgainstSnapshot() {
        val snap = PulseQuestionnaireState.Snapshot(
            launchCount = 3,
            foregroundCount = 5,
            firstLaunchAt = 0L,
            now = 10 * 86_400_000L, // 10 days
        )
        assertTrue(PulseQuestionnaireCondition.Launches(3).isSatisfied(snap))
        assertFalse(PulseQuestionnaireCondition.Launches(4).isSatisfied(snap))
        assertTrue(PulseQuestionnaireCondition.Foregrounds(5).isSatisfied(snap))
        assertTrue(PulseQuestionnaireCondition.DaysSinceFirstLaunch(7).isSatisfied(snap))
        assertFalse(PulseQuestionnaireCondition.DaysSinceFirstLaunch(11).isSatisfied(snap))
        assertTrue(PulseQuestionnaireCondition.HoursSinceFirstLaunch(240).isSatisfied(snap))
    }

    @Test
    fun triggerAndsConditionsAndManualNeverFires() {
        val snap = PulseQuestionnaireState.Snapshot(launchCount = 3, foregroundCount = 0, firstLaunchAt = null, now = 0L)

        val both = PulseQuestionnaireTrigger.whenAll(
            PulseQuestionnaireCondition.Launches(2),
            PulseQuestionnaireCondition.Launches(5),
        )
        assertFalse(both.isSatisfied(snap)) // second condition fails → ANDed false

        val ok = PulseQuestionnaireTrigger.whenAll(PulseQuestionnaireCondition.Launches(2))
        assertTrue(ok.isSatisfied(snap))

        assertFalse(PulseQuestionnaireTrigger.manual.isSatisfied(snap))
        assertTrue(PulseQuestionnaireTrigger.afterLaunch.isSatisfied(snap))
        assertTrue(PulseQuestionnaireTrigger.afterLaunches(3).isSatisfied(snap))
    }

    @Test
    fun ineligibleReasonMapsFromWire() {
        assertEquals(
            PulseQuestionnaireIneligibleReason.ALREADY_RESPONDED,
            PulseQuestionnaireIneligibleReason.fromWire("already_responded"),
        )
        assertEquals(
            PulseQuestionnaireIneligibleReason.GLOBALLY_DISMISSED,
            PulseQuestionnaireIneligibleReason.fromWire("globally_dismissed"),
        )
        assertNull(PulseQuestionnaireIneligibleReason.fromWire("nonsense"))
        assertNull(PulseQuestionnaireIneligibleReason.fromWire(null))
    }
}
