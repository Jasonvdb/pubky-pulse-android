package org.pubky.pulse.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Core questionnaire model + wire-format tests. Runs under Robolectric because
 * the decode/encode paths exercise real `org.json` (plain JVM unit tests stub it
 * to no-ops). Mirrors the Swift SDK's questionnaire model/codable coverage.
 */
@RunWith(RobolectricTestRunner::class)
class QuestionnaireModelTest {

    private fun fullSchemaJson(): String = """
        {
          "id": "q_1",
          "slug": "post-onboarding",
          "name": "Post Onboarding",
          "description": "Tell us how it went",
          "schema": {
            "version": 1,
            "questions": [
              { "type": "text", "id": "q_text", "title": "Thoughts?", "required": true, "placeholder": "Type here", "multiline": true },
              { "type": "single_choice", "id": "q_single", "title": "Pick one", "required": false,
                "options": [ { "id": "a", "label": "Apple" }, { "id": "b", "label": "Banana" } ] },
              { "type": "multi_choice", "id": "q_multi", "title": "Pick some", "required": false,
                "options": [ { "id": "x", "label": "X" }, { "id": "y", "label": "Y" } ] },
              { "type": "rating", "id": "q_rating", "title": "Rate", "required": true, "scale": 5 },
              { "type": "nps", "id": "q_nps", "title": "Recommend?", "required": false }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun decodesFullQuestionnaire() {
        val q = PulseQuestionnaire.fromJson(JSONObject(fullSchemaJson()))
        assertEquals("q_1", q.id)
        assertEquals("post-onboarding", q.slug)
        assertEquals("Post Onboarding", q.name)
        assertEquals("Tell us how it went", q.description)
        assertEquals(1, q.schema.version)
        assertEquals(5, q.schema.questions.size)

        val text = q.schema.questions[0] as PulseQuestionnaireQuestion.Text
        assertEquals("q_text", text.id)
        assertTrue(text.required)
        assertTrue(text.multiline)
        assertEquals("Type here", text.placeholder)

        val single = q.schema.questions[1] as PulseQuestionnaireQuestion.SingleChoice
        assertEquals(2, single.options.size)
        assertEquals("Apple", single.options[0].label)

        val rating = q.schema.questions[3] as PulseQuestionnaireQuestion.Rating
        assertEquals(5, rating.scale)

        assertTrue(q.schema.questions[4] is PulseQuestionnaireQuestion.Nps)
    }

    @Test
    fun nullDescriptionDecodesAsNull() {
        val json = JSONObject(
            """{ "id": "i", "slug": "s", "name": "n", "schema": { "version": 1, "questions": [] } }""",
        )
        val q = PulseQuestionnaire.fromJson(json)
        assertNull(q.description)
    }

    @Test(expected = PulseQuestionnaireParseException::class)
    fun unknownQuestionTypeThrows() {
        val json = JSONObject(
            """{ "id": "i", "slug": "s", "name": "n",
                  "schema": { "version": 1, "questions": [ { "type": "slider", "id": "x", "title": "t", "required": false } ] } }""",
        )
        PulseQuestionnaire.fromJson(json)
    }

    @Test
    fun encodesAnswersToWireShape() {
        val answers = linkedMapOf<String, PulseQuestionnaireAnswerValue>(
            "q_text" to PulseQuestionnaireAnswerValue.TextValue("hello"),
            "q_single" to PulseQuestionnaireAnswerValue.ChoiceValue("a"),
            "q_multi" to PulseQuestionnaireAnswerValue.ChoicesValue(listOf("x", "y")),
            "q_rating" to PulseQuestionnaireAnswerValue.RatingValue(4),
            "q_nps" to PulseQuestionnaireAnswerValue.NpsValue(9),
        )
        val obj = encodeAnswers(answers)
        assertEquals("hello", obj.getString("q_text"))
        assertEquals("a", obj.getString("q_single"))
        assertEquals(2, obj.getJSONArray("q_multi").length())
        assertEquals("x", obj.getJSONArray("q_multi").getString(0))
        assertEquals(4, obj.getInt("q_rating"))
        assertEquals(9, obj.getInt("q_nps"))
    }

    @Test
    fun hydrateDraftAnswersProjectsByQuestionType() {
        val q = PulseQuestionnaire.fromJson(JSONObject(fullSchemaJson()))
        val raw = JSONObject(
            """{ "q_text": "draft text", "q_single": "b",
                  "q_multi": ["x"], "q_rating": 3, "q_nps": 7,
                  "q_unknown": "ignored" }""",
        )
        val hydrated = hydrateDraftAnswers(raw, q.schema)
        assertEquals(PulseQuestionnaireAnswerValue.TextValue("draft text"), hydrated["q_text"])
        assertEquals(PulseQuestionnaireAnswerValue.ChoiceValue("b"), hydrated["q_single"])
        assertEquals(PulseQuestionnaireAnswerValue.ChoicesValue(listOf("x")), hydrated["q_multi"])
        assertEquals(PulseQuestionnaireAnswerValue.RatingValue(3), hydrated["q_rating"])
        assertEquals(PulseQuestionnaireAnswerValue.NpsValue(7), hydrated["q_nps"])
        // Unknown keys (not in schema) are dropped.
        assertFalse(hydrated.containsKey("q_unknown"))
    }

    @Test
    fun hydrateDraftAnswersSkipsShapeMismatches() {
        val q = PulseQuestionnaire.fromJson(JSONObject(fullSchemaJson()))
        // q_rating expects an int but the draft has a string; drop it.
        val raw = JSONObject("""{ "q_rating": "not-an-int", "q_text": "ok" }""")
        val hydrated = hydrateDraftAnswers(raw, q.schema)
        assertFalse(hydrated.containsKey("q_rating"))
        assertEquals(PulseQuestionnaireAnswerValue.TextValue("ok"), hydrated["q_text"])
    }
}
