package org.pubky.pulse.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Feedback API parity tests, mirroring the Swift `FeedbackTests`:
 *  - [EventTransport.submitFeedback] is a one-shot POST to `/v1/feedback`: it
 *    parses `{ id, created_at }` into a receipt on 2xx, returns a typed
 *    [PulseFeedbackError.ServerError] (with the body verbatim) on non-2xx, a
 *    [PulseFeedbackError.TransportFailure] when the HTTP client throws, and does
 *    **not** retry on 5xx (single attempt — unlike ingest).
 *  - [Pulse.sendFeedback] throws [PulseFeedbackError.EmptyMessage] for a blank
 *    message before any network call, [PulseFeedbackError.NotConfigured] before
 *    configure, and on success returns the receipt + emits an
 *    `sdk:feedback_submitted` audit event tagged `has_email`/`has_name`.
 *  - the wire body carries the trimmed message, contact details (omitted when
 *    blank), and the auto-attached session/device/sdk fields.
 *
 * Robolectric for real org.json + the SharedPreferences-backed identity store; a
 * recording [HttpClient] (the Android analog of the Swift `URLProtocol` stub)
 * captures the feedback + ingest requests.
 */
@RunWith(RobolectricTestRunner::class)
class FeedbackTest {

    /** Records every request; replies to /v1/feedback from a script, ingest/claim with 2xx. */
    private class RecordingHttpClient : HttpClient {
        val requests = CopyOnWriteArrayList<HttpRequest>()

        /** Scripted feedback responses; when exhausted, falls back to [feedbackDefault]. */
        val feedbackScript = ArrayDeque<Any>() // HttpResponse or Throwable
        var feedbackDefault: HttpResponse =
            HttpResponse(200, """{"id":"fb_123","created_at":"2026-06-04T12:34:56.789Z"}""")

        override fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            val path = request.url.path
            return when {
                path.endsWith("/v1/feedback") -> when (val next = feedbackScript.removeFirstOrNull()) {
                    null -> feedbackDefault
                    is HttpResponse -> next
                    is Throwable -> throw next
                    else -> feedbackDefault
                }
                path.endsWith("/v1/identity/claim") -> HttpResponse(200, """{"claimed":true}""")
                path.endsWith("/v1/identity/properties") -> HttpResponse(200, "{}")
                path.endsWith("/v1/ingest") -> HttpResponse(200, """{"accepted":1,"rejected":0}""")
                else -> HttpResponse(200, "{}")
            }
        }

        fun feedbackRequests(): List<HttpRequest> =
            requests.filter { it.url.path.endsWith("/v1/feedback") }

        fun feedbackBodies(): List<JSONObject> =
            feedbackRequests().mapNotNull { it.body?.toString(Charsets.UTF_8) }.map { JSONObject(it) }

        fun ingestEvents(): List<JSONObject> {
            val out = ArrayList<JSONObject>()
            for (req in requests) {
                if (!req.url.path.endsWith("/v1/ingest")) continue
                val text = req.body?.toString(Charsets.UTF_8) ?: continue
                val arr = JSONObject(text).getJSONArray("events")
                for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
            }
            return out
        }
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var http: RecordingHttpClient

    @Before
    fun setUp() {
        Pulse.resetForTesting()
        http = RecordingHttpClient()
        Pulse.httpClientOverrideForTesting = http
        IdentityStore(context).clearUserId()
    }

    @After
    fun tearDown() {
        Pulse.resetForTesting()
        Pulse.httpClientOverrideForTesting = null
    }

    private fun configure() {
        Pulse.configure(
            context = context,
            endpoint = "https://ingest.example.com",
            apiKey = "pulse_client_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            compressionEnabled = false,
            networkTrackingEnabled = false,
            consoleLogging = false,
            attributionEnabled = false,
        )
    }

    private fun pollUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
    }

    // ---- EventTransport.submitFeedback (transport-level, no Pulse) ----

    private fun transport(http: HttpClient): EventTransport {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val dir = java.io.File.createTempFile("pulse-fb", "").let { it.delete(); it.mkdirs(); it }
        return EventTransport(
            endpoint = URL("https://ingest.example.com"),
            apiKey = "pulse_client_abc",
            bundleId = "com.example.app",
            compressionEnabled = false,
            offlineQueue = OfflineQueue(dir, scope),
            networkMonitor = object : Reachability { override val isConnected = true },
            scope = scope,
            httpClient = http,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
    }

    private fun feedbackBody() = FeedbackRequestBody(
        bundleId = "com.example.app",
        message = "hello",
        sessionId = "sess",
        userId = "pulse_anon_x",
        submitterName = null,
        submitterEmail = null,
        appVersion = "1.0",
        sdkName = "pubky-pulse-android",
        sdkVersion = "0.1.0",
        environment = "android",
        deviceModel = "Pixel",
        osVersion = "14",
        isDev = false,
    )

    @Test
    fun `submitFeedback posts to the feedback endpoint and parses the receipt`() = runBlocking {
        val tx = transport(http)
        val result = tx.submitFeedback(feedbackBody())

        assertTrue(result is FeedbackResult.Success)
        val receipt = (result as FeedbackResult.Success).receipt
        assertEquals("fb_123", receipt.id)
        assertNotNull(receipt.createdAt)

        val req = http.feedbackRequests().single()
        assertEquals("POST", req.method)
        assertTrue(req.url.toString().endsWith("/v1/feedback"))
        assertEquals("Bearer pulse_client_abc", req.headers["Authorization"])
        assertEquals("application/json", req.headers["Content-Type"])
        val body = JSONObject(req.body!!.toString(Charsets.UTF_8))
        assertEquals("com.example.app", body.getString("bundle_id"))
        assertEquals("hello", body.getString("message"))
        assertFalse(body.getBoolean("is_dev"))
    }

    @Test
    fun `submitFeedback omits unavailable bundle metadata`() = runBlocking {
        val result = transport(http).submitFeedback(feedbackBody().copy(bundleId = ""))

        assertTrue(result is FeedbackResult.Success)
        val request = http.feedbackRequests().single()
        val body = JSONObject(request.body!!.toString(Charsets.UTF_8))
        assertFalse(body.has("bundle_id"))
        assertEquals("hello", body.getString("message"))
        assertEquals("Bearer pulse_client_abc", request.headers["Authorization"])
    }

    @Test
    fun `submitFeedback returns ServerError with the body verbatim on non-2xx`() = runBlocking {
        http.feedbackScript.add(HttpResponse(422, """{"error":"message too long"}"""))
        val tx = transport(http)
        val result = tx.submitFeedback(feedbackBody())

        assertTrue(result is FeedbackResult.Failure)
        val error = (result as FeedbackResult.Failure).error
        assertTrue(error is PulseFeedbackError.ServerError)
        val server = error as PulseFeedbackError.ServerError
        assertEquals(422, server.statusCode)
        assertEquals("""{"error":"message too long"}""", server.body)
    }

    @Test
    fun `submitFeedback returns TransportFailure when the HTTP client throws`() = runBlocking {
        http.feedbackScript.add(java.io.IOException("network down"))
        val tx = transport(http)
        val result = tx.submitFeedback(feedbackBody())

        assertTrue(result is FeedbackResult.Failure)
        assertTrue((result as FeedbackResult.Failure).error is PulseFeedbackError.TransportFailure)
    }

    @Test
    fun `submitFeedback does not retry on 5xx — single attempt`() = runBlocking {
        http.feedbackDefault = HttpResponse(503, "unavailable")
        val tx = transport(http)
        val result = tx.submitFeedback(feedbackBody())

        assertTrue(result is FeedbackResult.Failure)
        // Unlike ingest (5 attempts), feedback is one-shot.
        assertEquals(1, http.feedbackRequests().size)
    }

    @Test
    fun `submitFeedback omits null contact fields from the wire body`() = runBlocking {
        val tx = transport(http)
        tx.submitFeedback(feedbackBody()) // name/email null

        val body = http.feedbackBodies().single()
        assertFalse("name omitted when null", body.has("submitter_name"))
        assertFalse("email omitted when null", body.has("submitter_email"))
    }

    // ---- Pulse.sendFeedback (public API) ----

    @Test
    fun `sendFeedback throws EmptyMessage for a blank message`() = runBlocking {
        configure()
        try {
            Pulse.sendFeedback("   \n  ")
            fail("expected EmptyMessage")
        } catch (e: PulseFeedbackError.EmptyMessage) {
            // expected
        }
        assertEquals("no network call for an empty message", 0, http.feedbackRequests().size)
    }

    @Test
    fun `sendFeedback throws NotConfigured before configure`() = runBlocking {
        try {
            Pulse.sendFeedback("hi there")
            fail("expected NotConfigured")
        } catch (e: PulseFeedbackError.NotConfigured) {
            // expected
        }
    }

    @Test
    fun `sendFeedback returns the receipt and trims the message`() = runBlocking {
        configure()
        val receipt = Pulse.sendFeedback("  please add dark mode  ")

        assertEquals("fb_123", receipt.id)
        val body = http.feedbackBodies().single()
        assertEquals("please add dark mode", body.getString("message"))
        // Auto-attached fields from the configured state.
        assertEquals("pubky-pulse-android", body.getString("sdk_name"))
        assertEquals("android", body.getString("environment"))
        assertTrue(body.has("session_id"))
        assertTrue(body.has("user_id"))
    }

    @Test
    fun `sendFeedback carries trimmed contact details`() = runBlocking {
        configure()
        Pulse.sendFeedback(message = "great app", name = "  Ada  ", email = " ada@example.com ")

        val body = http.feedbackBodies().single()
        assertEquals("Ada", body.getString("submitter_name"))
        assertEquals("ada@example.com", body.getString("submitter_email"))
    }

    @Test
    fun `sendFeedback drops blank contact details`() = runBlocking {
        configure()
        Pulse.sendFeedback(message = "great app", name = "   ", email = "")

        val body = http.feedbackBodies().single()
        assertFalse(body.has("submitter_name"))
        assertFalse(body.has("submitter_email"))
    }

    @Test
    fun `sendFeedback emits an audit event with has_email and has_name`() = runBlocking {
        configure()
        Pulse.sendFeedback(message = "love it", name = "Ada", email = "ada@example.com")

        // The audit event is enqueued through the normal log pipeline; force a
        // drain via setUser (claim → flushAll), then poll for the ingest POST.
        Pulse.setUser("real-user-${System.nanoTime()}")
        pollUntil(5_000) {
            http.ingestEvents().any { it.optString("message") == "sdk:feedback_submitted" }
        }

        val audit = http.ingestEvents().single { it.optString("message") == "sdk:feedback_submitted" }
        assertEquals("info", audit.optString("level"))
        val attrs = audit.getJSONObject("custom_attributes")
        assertEquals("true", attrs.getString("has_email"))
        assertEquals("true", attrs.getString("has_name"))
    }

    @Test
    fun `sendFeedback audit event flags missing contact details`() = runBlocking {
        configure()
        Pulse.sendFeedback(message = "anonymous feedback")

        Pulse.setUser("real-user-${System.nanoTime()}")
        pollUntil(5_000) {
            http.ingestEvents().any { it.optString("message") == "sdk:feedback_submitted" }
        }

        val audit = http.ingestEvents().single { it.optString("message") == "sdk:feedback_submitted" }
        val attrs = audit.getJSONObject("custom_attributes")
        assertEquals("false", attrs.getString("has_email"))
        assertEquals("false", attrs.getString("has_name"))
    }

    @Test
    fun `sendFeedback rethrows a server error and emits no audit event`() = runBlocking {
        configure()
        http.feedbackScript.add(HttpResponse(500, "boom"))
        try {
            Pulse.sendFeedback("this will fail")
            fail("expected ServerError")
        } catch (e: PulseFeedbackError.ServerError) {
            assertEquals(500, e.statusCode)
        }

        // No audit event for a failed submission.
        Pulse.setUser("real-user-${System.nanoTime()}")
        pollUntil(1_000) { false } // let any stray drain settle
        assertTrue(
            "no feedback audit on failure",
            http.ingestEvents().none { it.optString("message") == "sdk:feedback_submitted" },
        )
    }

    // ---- PulseFeedbackError + receipt parsing ----

    @Test
    fun `PulseFeedbackError messages mirror the Swift errorDescription strings`() {
        assertEquals(
            "Pubky Pulse is not configured. Call Pulse.configure(...) before sending feedback.",
            PulseFeedbackError.NotConfigured.message,
        )
        assertEquals("Feedback message is empty.", PulseFeedbackError.EmptyMessage.message)
        assertEquals(
            "Server returned 500: boom",
            PulseFeedbackError.ServerError(500, "boom").message,
        )
        assertEquals(
            "Server returned 500",
            PulseFeedbackError.ServerError(500, null).message,
        )
        assertEquals(
            "Server returned 500",
            PulseFeedbackError.ServerError(500, "").message,
        )
        assertEquals("offline", PulseFeedbackError.TransportFailure("offline").message)
    }

    @Test
    fun `PulseFeedbackReceipt parses ISO8601 created_at and falls back to now on garbage`() {
        val good = PulseFeedbackReceipt.fromJson(
            JSONObject("""{"id":"a","created_at":"2026-06-04T12:34:56.789Z"}"""),
        )
        assertEquals("a", good.id)
        // 2026-06-04T12:34:56.789Z == 1780576496789 ms.
        assertEquals(1780576496789L, good.createdAt.time)

        val garbage = PulseFeedbackReceipt.fromJson(JSONObject("""{"id":"b","created_at":"not-a-date"}"""))
        assertEquals("b", garbage.id)
        assertNotNull(garbage.createdAt) // fell back to "now"
    }

    @Test
    fun `PulseFeedbackReceipt tolerates a created_at without fractional seconds`() {
        val receipt = PulseFeedbackReceipt.fromJson(
            JSONObject("""{"id":"c","created_at":"2026-06-04T12:34:56Z"}"""),
        )
        assertEquals(1780576496000L, receipt.createdAt.time)
    }
}
