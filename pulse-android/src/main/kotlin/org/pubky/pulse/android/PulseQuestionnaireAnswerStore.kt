package org.pubky.pulse.android

/**
 * Pure value collector for the questionnaire flow's per-question answers. Lives
 * in the core module (not the Compose layer) so the validation + wire-encoding
 * logic is unit-testable without a Compose runtime — the Kotlin analog of
 * Swift's `PulseQuestionnaireAnswerStore` struct, which Swift keeps separate from
 * the SwiftUI container for the same reason.
 *
 * Public so the Compose flow container (a separate module) can own one as its
 * answer state. The maps are exposed `var` so the Compose bindings can write
 * straight through; recomposition is driven by the container holding the store
 * in `mutableStateOf` and replacing it via [copyWith*] on every edit (data-class
 * copy = new identity), mirroring how the Swift `@State` store re-publishes.
 */
public data class PulseQuestionnaireAnswerStore(
    public val text: Map<String, String> = emptyMap(),
    public val single: Map<String, String> = emptyMap(),
    public val multi: Map<String, Set<String>> = emptyMap(),
    public val rating: Map<String, Int> = emptyMap(),
    public val nps: Map<String, Int> = emptyMap(),
) {
    /**
     * Return a copy with [text] for [questionId] set (or removed when null).
     * The immutable-update analog of Swift's `answers.text[id] = value`.
     */
    public fun withText(questionId: String, value: String?): PulseQuestionnaireAnswerStore =
        copy(text = text.mutate { if (value == null) remove(questionId) else put(questionId, value) })

    /** Copy with the single-choice answer for [questionId] set/removed. */
    public fun withSingle(questionId: String, value: String?): PulseQuestionnaireAnswerStore =
        copy(single = single.mutate { if (value == null) remove(questionId) else put(questionId, value) })

    /** Copy with the multi-choice answer set for [questionId] replaced. */
    public fun withMulti(questionId: String, value: Set<String>): PulseQuestionnaireAnswerStore =
        copy(multi = multi.mutate { put(questionId, value) })

    /** Copy that toggles [optionId] in the multi-choice set for [questionId]. */
    public fun togglingMulti(questionId: String, optionId: String): PulseQuestionnaireAnswerStore {
        val current = multi[questionId] ?: emptySet()
        val next = if (optionId in current) current - optionId else current + optionId
        return withMulti(questionId, next)
    }

    /** Copy with the rating answer for [questionId] set/removed. */
    public fun withRating(questionId: String, value: Int?): PulseQuestionnaireAnswerStore =
        copy(rating = rating.mutate { if (value == null) remove(questionId) else put(questionId, value) })

    /** Copy with the NPS answer for [questionId] set/removed. */
    public fun withNps(questionId: String, value: Int?): PulseQuestionnaireAnswerStore =
        copy(nps = nps.mutate { if (value == null) remove(questionId) else put(questionId, value) })

    /**
     * Hydrate a store from server-side draft state (the `in_progress` payload of
     * the eligibility envelope). Unknown question shapes are silently skipped —
     * pre-fill is best-effort and the server prunes stale keys when the user
     * completes. Mirrors Swift's `prefill(from:)`.
     */
    public fun prefilled(answers: Map<String, PulseQuestionnaireAnswerValue>): PulseQuestionnaireAnswerStore {
        val t = LinkedHashMap(text)
        val s = LinkedHashMap(single)
        val m = LinkedHashMap(multi)
        val r = LinkedHashMap(rating)
        val n = LinkedHashMap(nps)
        for ((key, value) in answers) {
            when (value) {
                is PulseQuestionnaireAnswerValue.TextValue -> t[key] = value.value
                is PulseQuestionnaireAnswerValue.ChoiceValue -> s[key] = value.value
                is PulseQuestionnaireAnswerValue.ChoicesValue -> m[key] = value.value.toSet()
                is PulseQuestionnaireAnswerValue.RatingValue -> r[key] = value.value
                is PulseQuestionnaireAnswerValue.NpsValue -> n[key] = value.value
            }
        }
        return PulseQuestionnaireAnswerStore(text = t, single = s, multi = m, rating = r, nps = n)
    }

    /**
     * Index of the first question whose id is not answered yet. Returns
     * `questions.size - 1` when every question is answered (so the flow lands on
     * the last page with Submit live). Returns 0 for an empty schema. Mirrors
     * Swift's `firstUnansweredIndex(in:)`.
     */
    public fun firstUnansweredIndex(schema: PulseQuestionnaireSchema): Int {
        val questions = schema.questions
        for ((i, q) in questions.withIndex()) {
            if (!isAnswered(q)) return i
        }
        return maxOf(0, questions.size - 1)
    }

    /** Whether [question] has a non-empty answer. Mirrors Swift's `isAnswered(_:)`. */
    public fun isAnswered(question: PulseQuestionnaireQuestion): Boolean = when (question) {
        is PulseQuestionnaireQuestion.Text -> (text[question.id] ?: "").trim().isNotEmpty()
        is PulseQuestionnaireQuestion.SingleChoice -> single[question.id] != null
        is PulseQuestionnaireQuestion.MultiChoice -> !(multi[question.id]?.isEmpty() ?: true)
        is PulseQuestionnaireQuestion.Rating -> rating[question.id] != null
        is PulseQuestionnaireQuestion.Nps -> nps[question.id] != null
    }

    /** Whether every `required` question is answered. Mirrors Swift's `hasAllRequired`. */
    public fun hasAllRequired(schema: PulseQuestionnaireSchema): Boolean =
        schema.questions.filter { it.required }.all { isAnswered(it) }

    /**
     * Collect the strongly-typed answer map to POST. Trims text, drops empty
     * answers, and sorts multi-choice ids for a stable wire shape. Mirrors
     * Swift's `collected(_:)`.
     */
    public fun collected(schema: PulseQuestionnaireSchema): Map<String, PulseQuestionnaireAnswerValue> {
        val out = LinkedHashMap<String, PulseQuestionnaireAnswerValue>()
        for (q in schema.questions) {
            when (q) {
                is PulseQuestionnaireQuestion.Text -> {
                    val raw = (text[q.id] ?: "").trim()
                    if (raw.isNotEmpty()) out[q.id] = PulseQuestionnaireAnswerValue.TextValue(raw)
                }
                is PulseQuestionnaireQuestion.SingleChoice ->
                    single[q.id]?.let { out[q.id] = PulseQuestionnaireAnswerValue.ChoiceValue(it) }
                is PulseQuestionnaireQuestion.MultiChoice -> {
                    val set = multi[q.id]
                    if (!set.isNullOrEmpty()) {
                        out[q.id] = PulseQuestionnaireAnswerValue.ChoicesValue(set.sorted())
                    }
                }
                is PulseQuestionnaireQuestion.Rating ->
                    rating[q.id]?.let { out[q.id] = PulseQuestionnaireAnswerValue.RatingValue(it) }
                is PulseQuestionnaireQuestion.Nps ->
                    nps[q.id]?.let { out[q.id] = PulseQuestionnaireAnswerValue.NpsValue(it) }
            }
        }
        return out
    }
}

/** Apply a mutation to a copy of this map, returning the new immutable map. */
private inline fun <K, V> Map<K, V>.mutate(block: MutableMap<K, V>.() -> Unit): Map<K, V> {
    val copy = LinkedHashMap(this)
    copy.block()
    return copy
}
