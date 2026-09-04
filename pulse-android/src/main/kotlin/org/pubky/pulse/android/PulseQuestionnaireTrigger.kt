package org.pubky.pulse.android

/**
 * A single condition that must hold for an auto-triggered questionnaire to
 * present. Conditions read from the persistent [PulseQuestionnaireState] (launch /
 * foreground counts) and a wall-clock `now`. The Kotlin analog of Swift's
 * `PulseQuestionnaireCondition` enum — modeled as a sealed class so [isSatisfied]
 * is exhaustive.
 */
public sealed class PulseQuestionnaireCondition {
    /** Number of times `Pulse.configure(...)` has completed (one bump per process). */
    public data class Launches(public val atLeast: Int) : PulseQuestionnaireCondition()

    /** Number of foreground transitions since install. */
    public data class Foregrounds(public val atLeast: Int) : PulseQuestionnaireCondition()

    /** Days since the very first `Pulse.configure(...)` call. */
    public data class DaysSinceFirstLaunch(public val atLeast: Int) : PulseQuestionnaireCondition()

    /** Hours since the very first `Pulse.configure(...)` call. */
    public data class HoursSinceFirstLaunch(public val atLeast: Int) : PulseQuestionnaireCondition()

    /** Pure evaluator used by the trigger gate and unit tests. */
    public fun isSatisfied(state: PulseQuestionnaireState.Snapshot): Boolean = when (this) {
        is Launches -> state.launchCount >= atLeast
        is Foregrounds -> state.foregroundCount >= atLeast
        is DaysSinceFirstLaunch -> state.daysSinceFirstLaunch() >= atLeast.toDouble()
        is HoursSinceFirstLaunch -> state.hoursSinceFirstLaunch() >= atLeast.toDouble()
    }
}

/**
 * A composable trigger for the Compose `pulseQuestionnaire(...)` modifier. All
 * [conditions] are ANDed — for OR logic, use the `isEligible` predicate or split
 * into two modifier applications. [isManual] opts out of auto-trigger entirely.
 * The Kotlin analog of Swift's `PulseQuestionnaireTrigger`.
 */
public data class PulseQuestionnaireTrigger(
    public val conditions: List<PulseQuestionnaireCondition>,
    public val isManual: Boolean,
) {
    /**
     * Evaluate against a state snapshot. Returns true only when every condition
     * is satisfied; [manual] always returns false (handled separately by the
     * gate). Mirrors Swift's `isSatisfied(state:)`.
     */
    public fun isSatisfied(state: PulseQuestionnaireState.Snapshot): Boolean {
        if (isManual) return false
        return conditions.all { it.isSatisfied(state) }
    }

    public companion object {
        /**
         * Never auto-trigger. The consumer drives presentation directly via
         * `PulseQuestionnaireView` or by binding to a state flag.
         */
        public val manual: PulseQuestionnaireTrigger =
            PulseQuestionnaireTrigger(conditions = emptyList(), isManual = true)

        /** Shortcut for `Launches(atLeast = 1)` — fire on first launch. */
        public val afterLaunch: PulseQuestionnaireTrigger =
            PulseQuestionnaireTrigger(
                conditions = listOf(PulseQuestionnaireCondition.Launches(atLeast = 1)),
                isManual = false,
            )

        /** Shortcut for `Launches(atLeast = n)`. */
        public fun afterLaunches(n: Int): PulseQuestionnaireTrigger =
            PulseQuestionnaireTrigger(
                conditions = listOf(PulseQuestionnaireCondition.Launches(atLeast = n)),
                isManual = false,
            )

        /**
         * Composable form — ALL conditions must evaluate true (ANDed). An empty
         * argument list means "always", which combined with `isEligible` is the
         * hook for fully custom gating. Mirrors Swift's variadic `when(_:)`.
         */
        public fun whenAll(vararg conditions: PulseQuestionnaireCondition): PulseQuestionnaireTrigger =
            PulseQuestionnaireTrigger(conditions = conditions.toList(), isManual = false)
    }
}
