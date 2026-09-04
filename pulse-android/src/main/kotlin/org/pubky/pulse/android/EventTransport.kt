package org.pubky.pulse.android

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Batches, buffers, retries, and POSTs events to the Pubky Pulse ingest API. The
 * Android analog of the Swift SDK's `EventTransport` actor.
 *
 * Swift's `EventTransport` is an `actor` (serialized access to `buffer`), with a
 * background flush `Task` looping every 5 s, batching 20 events per `/v1/ingest`
 * POST, gzip-compressing bodies ≥ 512 B, retrying transport/5xx/429 failures up
 * to 5 times with exponential backoff capped at 30 s (raised to a server-sent
 * `Retry-After`, itself capped at 60 s), and routing undelivered batches to the
 * [OfflineQueue]. This port mirrors all of that:
 *  - a single [Mutex] serializes `buffer` and in-flight-batch mutation (the
 *    actor analog under the coroutines-only dependency rule),
 *  - a flush loop launched on [scope] (`while isActive { delay(5s); flush() }`)
 *    is the analog of Swift's flush `Task`,
 *  - HTTP runs on [HttpURLConnection] (the framework-only analog of `URLSession`)
 *    dispatched to [Dispatchers.IO].
 *
 * `claimIdentity` reproduces the production-critical in-flight-send drain: it
 * waits for every parallel `/v1/ingest` POST started by the auto-flush /
 * periodic flush / flushAll loop to return before POSTing the claim, so the
 * server's `UPDATE events` can't run while ingest POSTs are mid-transaction and
 * orphan rows under the anon id. See CLAUDE.md "Identity".
 *
 * A batch handed to [send] has already left `buffer`, and the retry ladder can
 * hold it for up to ~61 s. [inFlightBatches] keeps those batches reachable so
 * [persistBufferToDisk] can park them on the [OfflineQueue] before the process
 * dies — otherwise a batch that is mid-retry when Android kills the process is
 * simply lost. Leaving `buffer` and entering [inFlightBatches] is one atomic
 * step under [stateMutex], so a persist can never observe the batch in neither.
 */
internal class EventTransport(
    endpoint: URL,
    private val apiKey: String,
    private val bundleId: String,
    private val compressionEnabled: Boolean,
    private val offlineQueue: OfflineQueue,
    private val networkMonitor: Reachability,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient = DefaultHttpClient,
    // The dispatcher blocking HTTP runs on — [Dispatchers.IO] in production,
    // overridable in tests so HTTP shares the test scheduler and stays
    // deterministic. The analog of injecting a `URLSession` into the Swift
    // transport to make sends synchronous under test.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val ingestUrl = endpoint.appendPath("v1/ingest")
    private val claimUrl = endpoint.appendPath("v1/identity/claim")
    private val propertiesUrl = endpoint.appendPath("v1/identity/properties")
    private val feedbackUrl = endpoint.appendPath("v1/feedback")
    private val questionnaireDismissUrl = endpoint.appendPath("v1/questionnaires/dismiss")

    // Held so the questionnaire URLs (which interpolate the slug + query string)
    // can be built per-call. The endpoint base is normalized once here.
    private val endpointBase: URL = endpoint

    // One mutex guards `buffer` AND the in-flight state below, because a batch
    // has to leave `buffer` and enter [inFlightBatches] in a single critical
    // section: with two mutexes a concurrent [persistBufferToDisk] can land in
    // the gap, see the batch in neither place, write nothing, and lose it to
    // process death. One lock makes that handoff atomic and removes any
    // lock-ordering question along with it.
    private val stateMutex = Mutex()
    private val buffer = ArrayList<LogEvent>()
    private var flushJob: Job? = null

    // In-flight ingest sends. `claimIdentity` waits for this to drain to zero
    // before POSTing the claim — mirrors Swift's `inFlightSendCount` +
    // `sendDrainContinuations`. Guarded by [stateMutex].
    private var inFlightSendCount = 0
    private val sendDrainContinuations = ArrayList<Continuation<Unit>>()

    // The batches that have left `buffer` but are not yet delivered or
    // re-queued, keyed by a monotonic id. Insertion order (a LinkedHashMap) is
    // oldest-first, which is the order [persistBufferToDisk] parks them in.
    // Guarded by [stateMutex].
    private val inFlightBatches = LinkedHashMap<Long, InFlightBatch>()
    private var nextInFlightBatchId = 0L

    /** A batch inside [send]; [persisted] flips once it is on the offline queue. */
    private class InFlightBatch(val events: List<LogEvent>) {
        var persisted = false
    }

    /**
     * A batch taken out of [buffer] and registered in [inFlightBatches] in the
     * same [stateMutex] section, carried by its owner until [retire].
     */
    private class PendingBatch(val id: Long, val events: List<LogEvent>)

    // Test seam: invoked at the top of [send], i.e. once the batch has left
    // `buffer`, so a unit test can drive a concurrent [persistBufferToDisk]
    // through the handoff window. Always null in production.
    internal var beforeSendHook: (suspend () -> Unit)? = null

    companion object {
        private const val TAG = "PubkyPulse.transport"

        private const val BATCH_SIZE = 20
        private const val MAX_BUFFER_SIZE = 10_000
        private const val FLUSH_INTERVAL_MS = 5_000L
        private const val MAX_RETRIES = 5
        private const val MAX_BACKOFF_SECONDS = 30.0
        // Ceiling on a server-requested `Retry-After`; a hostile or mistaken
        // header must not park a send for minutes.
        private const val MAX_RETRY_AFTER_SECONDS = 60.0
        private const val COMPRESSION_THRESHOLD = 512
        private const val STATUS_TOO_MANY_REQUESTS = 429
        private const val STATUS_SERVICE_UNAVAILABLE = 503
    }

    /** Start the periodic flush loop. Idempotent. Mirrors Swift's `start()`. */
    fun start() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                // Guard each tick so one bad flush logs and the loop keeps running
                // for subsequent events. Without this a single throw would unwind
                // the loop (and reach the scope's exception handler) — silencing
                // the crash but also stopping ALL future periodic flushing for the
                // process lifetime. Rethrow CancellationException so shutdown() and
                // scope cancellation still tear the loop down cleanly.
                try {
                    flush()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    Log.w(TAG, "Periodic flush failed; will retry next interval.", t)
                }
            }
        }
    }

    /** Cancel the flush loop and drain everything. Mirrors Swift's `shutdown()`. */
    suspend fun shutdown() {
        flushJob?.cancel()
        flushJob = null
        flushAll()
    }

    /** Buffer one event, auto-flushing at [BATCH_SIZE]. Mirrors Swift's `enqueue(_:)`. */
    suspend fun enqueue(event: LogEvent) {
        val shouldFlush = stateMutex.withLock {
            buffer.add(event)
            trimBuffer()
            buffer.size >= BATCH_SIZE
        }
        if (shouldFlush) scope.launch { flush() }
    }

    /** Buffer a batch, auto-flushing at [BATCH_SIZE]. Mirrors Swift's `enqueue(_:[])`. */
    suspend fun enqueue(events: List<LogEvent>) {
        if (events.isEmpty()) return
        val shouldFlush = stateMutex.withLock {
            buffer.addAll(events)
            trimBuffer()
            buffer.size >= BATCH_SIZE
        }
        if (shouldFlush) scope.launch { flush() }
    }

    /** Caller holds [stateMutex]. Drop the oldest past [MAX_BUFFER_SIZE]. */
    private fun trimBuffer() {
        if (buffer.size > MAX_BUFFER_SIZE) {
            val overflow = buffer.size - MAX_BUFFER_SIZE
            repeat(overflow) { buffer.removeAt(0) }
        }
    }

    /**
     * Caller holds [stateMutex]. Move the next [BATCH_SIZE] events out of
     * [buffer] and into [inFlightBatches] in one step, so the batch is never
     * invisible to [persistBufferToDisk]. Null when the buffer is empty.
     */
    private fun takeBatchLocked(): PendingBatch? {
        if (buffer.isEmpty()) return null
        val take = min(BATCH_SIZE, buffer.size)
        val events = ArrayList(buffer.subList(0, take))
        repeat(take) { buffer.removeAt(0) }
        val id = nextInFlightBatchId++
        inFlightBatches[id] = InFlightBatch(events)
        return PendingBatch(id, events)
    }

    /**
     * Flush one batch. Prepends any offline-queued events, takes the first
     * [BATCH_SIZE], and POSTs them — routing back to the offline queue on
     * failure or when offline. Mirrors Swift's `flush()`.
     */
    suspend fun flush() {
        val offlineEvents = offlineQueue.drain()

        val pending = stateMutex.withLock {
            if (offlineEvents.isNotEmpty()) buffer.addAll(0, offlineEvents)
            takeBatchLocked() ?: return
        }

        val delivered = networkMonitor.isConnected && send(pending)
        retire(pending, delivered)
    }

    /**
     * Drain everything in batches until the buffer is empty. Used on shutdown
     * and before an identity claim. Mirrors Swift's `flushAll()`.
     *
     * [maxAttemptsPerBatch] caps each batch's retry ladder. Foreground callers
     * take the default full ladder. The background (ON_STOP) caller passes 1:
     * a 429/503 `Retry-After` can park the ladder for up to a minute per
     * attempt, and Android is free to kill a backgrounded process long before
     * that — so the background path takes one shot per batch and routes
     * anything undelivered to the offline queue immediately, leaving
     * [persistBufferToDisk] free to run while the process is still alive.
     */
    suspend fun flushAll(maxAttemptsPerBatch: Int = MAX_RETRIES) {
        val offlineEvents = offlineQueue.drain()
        stateMutex.withLock {
            if (offlineEvents.isNotEmpty()) buffer.addAll(0, offlineEvents)
        }

        while (true) {
            val pending = stateMutex.withLock { takeBatchLocked() ?: return }

            if (!networkMonitor.isConnected) {
                // Offline mid-drain: push this batch + the remainder back to the
                // offline queue and stop, matching Swift's `batch + buffer` path.
                val undelivered = stateMutex.withLock {
                    val out = ArrayList<LogEvent>()
                    val parked = inFlightBatches.remove(pending.id)?.persisted == true
                    if (!parked) out.addAll(pending.events)
                    out.addAll(buffer)
                    buffer.clear()
                    out
                }
                handleUndelivered(undelivered)
                return
            }

            retire(pending, send(pending, maxAttemptsPerBatch))
        }
    }

    /**
     * Drop a batch's in-flight registration and, unless it was delivered or
     * already parked by [persistBufferToDisk], route it to the offline queue.
     * Skipping a parked batch is what stops a persist during the send from
     * appending a second copy.
     */
    private suspend fun retire(pending: PendingBatch, delivered: Boolean) {
        val parked = stateMutex.withLock {
            inFlightBatches.remove(pending.id)?.persisted == true
        }
        if (delivered || parked) return
        handleUndelivered(pending.events)
    }

    /** Route an undelivered batch to the offline queue. Mirrors Swift's `handleUndelivered`. */
    private suspend fun handleUndelivered(batch: List<LogEvent>) {
        if (batch.isEmpty()) return
        offlineQueue.enqueue(batch)
    }

    /**
     * Move the in-memory buffer — and any batch still inside [send] — to the
     * offline queue, then force a disk write. Called when the host app
     * backgrounds so pending events survive process death. Mirrors Swift's
     * `persistBufferToDisk`.
     *
     * A batch handed to [send] is no longer in `buffer` and its retry ladder can
     * suspend for up to ~61 s, so persisting only `buffer` would lose it if
     * Android killed the process mid-retry. Those batches are parked first
     * (oldest first, ahead of the buffer) so replay order is preserved, and each
     * is marked persisted so a second persist during the same send — and
     * [retire] once the send returns — never append a duplicate copy.
     */
    suspend fun persistBufferToDisk() {
        val pending = stateMutex.withLock {
            val out = ArrayList<LogEvent>()
            for (batch in inFlightBatches.values) {
                if (batch.persisted) continue
                batch.persisted = true
                out.addAll(batch.events)
            }
            out.addAll(buffer)
            buffer.clear()
            out
        }
        if (pending.isEmpty()) return
        offlineQueue.enqueue(pending)
        offlineQueue.persistNow()
    }

    /**
     * Retroactively associate previously-sent anonymous events with a real
     * user. Flushes the buffer, then waits for every in-flight ingest POST to
     * return before POSTing the claim — so the server's `UPDATE events` can't
     * miss rows still mid-transaction. Mirrors Swift's `claimIdentity`.
     */
    suspend fun claimIdentity(anonymousId: String, userId: String) {
        flushAll()
        awaitInFlightSends()

        val body = JSONObject().apply {
            put("anonymous_id", anonymousId)
            put("user_id", userId)
        }
        val request = makeRequest(claimUrl, body.toString().toByteArray(Charsets.UTF_8))
        val ok = performWithRetry(request, "Claim")
        if (ok) {
            Log.i(TAG, "Identity claimed: $anonymousId -> $userId")
        } else {
            Log.e(TAG, "Identity claim failed after $MAX_RETRIES attempts")
        }
    }

    /**
     * Set user properties on the server. Mirrors Swift's `setUserProperties`.
     */
    suspend fun setUserProperties(userId: String, properties: Map<String, String>) {
        val body = JSONObject().apply {
            put("user_id", userId)
            put("properties", JSONObject(properties as Map<*, *>))
        }
        val request = makeRequest(propertiesUrl, body.toString().toByteArray(Charsets.UTF_8))
        val ok = performWithRetry(request, "Properties")
        if (ok) {
            Log.i(TAG, "User properties set for $userId")
        } else {
            Log.e(TAG, "User properties update failed for $userId")
        }
    }

    /**
     * One-shot synchronous feedback submission. Mirrors Swift's
     * `submitFeedback(_:)`: encodes the body, POSTs it **once** (no retry, no
     * offline queueing — the caller handles errors and decides whether to retry),
     * and on 2xx parses `{ id, created_at }` into a [PulseFeedbackReceipt]. Unlike
     * ingest, feedback is interactive: the user is staring at a spinner, so a
     * single attempt with a typed failure is the right contract rather than
     * silently retrying for 30s.
     *
     * Returns [FeedbackResult.Success] with the receipt, or [FeedbackResult.Failure]
     * carrying a typed [PulseFeedbackError] (server non-2xx with the body verbatim,
     * or a transport/encode/decode failure).
     */
    suspend fun submitFeedback(payload: FeedbackRequestBody): FeedbackResult {
        val httpBody = runCatching { payload.toJsonString().toByteArray(Charsets.UTF_8) }
            .getOrElse {
                return FeedbackResult.Failure(
                    PulseFeedbackError.TransportFailure("encoding failed: ${it.message}"),
                )
            }
        val request = makeRequest(feedbackUrl, httpBody)

        val response = withContext(ioDispatcher) {
            runCatching { httpClient.execute(request) }
        }

        response.onSuccess { http ->
            if (http.statusCode in 200..299) {
                val receipt = runCatching {
                    PulseFeedbackReceipt.fromJson(JSONObject(http.body ?: ""))
                }.getOrElse {
                    return FeedbackResult.Failure(
                        PulseFeedbackError.TransportFailure("decode failed: ${it.message}"),
                    )
                }
                return FeedbackResult.Success(receipt)
            }
            return FeedbackResult.Failure(
                PulseFeedbackError.ServerError(statusCode = http.statusCode, body = http.body),
            )
        }.onFailure { error ->
            return FeedbackResult.Failure(
                PulseFeedbackError.TransportFailure(error.message ?: error.toString()),
            )
        }

        // Unreachable — onSuccess/onFailure both return — but the compiler can't
        // see that through the Result lambdas.
        return FeedbackResult.Failure(PulseFeedbackError.TransportFailure("no response"))
    }

    // MARK: - Questionnaires

    private fun questionnaireUrl(slug: String): URL =
        endpointBase.appendPath("v1/questionnaires/${slug.urlPathEncoded()}")

    private fun questionnaireResponsesUrl(slug: String): URL =
        endpointBase.appendPath("v1/questionnaires/${slug.urlPathEncoded()}/responses")

    /**
     * Fetch a questionnaire spec + eligibility envelope. The success branch
     * carries the spec (null when the user is ineligible) plus, on eligible
     * returns, any in-progress draft so the flow container can pre-fill and
     * resume. The ineligibility reason is surfaced for diagnostics but the SDK
     * still treats already_responded / globally_dismissed / inactive as silent
     * no-ops. Returns [QuestionnaireFetchResult.Failure] only for slug-not-found
     * (404) and transport failures. Mirrors Swift's `fetchQuestionnaire`.
     */
    suspend fun fetchQuestionnaire(
        slug: String,
        userId: String?,
        force: Boolean = false,
    ): QuestionnaireFetchOutcome {
        val query = StringBuilder("?bundle_id=").append(bundleId.urlQueryEncoded())
        if (userId != null) query.append("&user_id=").append(userId.urlQueryEncoded())
        if (force) query.append("&force=true")

        val url = runCatching { URL(questionnaireUrl(slug).toString() + query.toString()) }
            .getOrElse { return QuestionnaireFetchOutcome.Failure(PulseQuestionnaireError.TransportFailure("invalid URL")) }

        val request = HttpRequest(
            url = url,
            method = "GET",
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Accept" to "application/json",
            ),
            body = null,
        )

        val response = withContext(ioDispatcher) { runCatching { httpClient.execute(request) } }
            .getOrElse { return QuestionnaireFetchOutcome.Failure(PulseQuestionnaireError.TransportFailure(it.message ?: it.toString())) }

        if (response.statusCode == 404) {
            return QuestionnaireFetchOutcome.Failure(PulseQuestionnaireError.SlugNotFound)
        }
        if (response.statusCode !in 200..299) {
            return QuestionnaireFetchOutcome.Failure(
                PulseQuestionnaireError.ServerError(response.statusCode, response.body),
            )
        }

        val result = runCatching { parseFetchEnvelope(response.body) }
            .getOrElse { return QuestionnaireFetchOutcome.Failure(PulseQuestionnaireError.TransportFailure("decode failed: ${it.message}")) }
        return QuestionnaireFetchOutcome.Success(result)
    }

    /** Decode the eligibility envelope into a [PulseQuestionnaireFetchResult]. */
    private fun parseFetchEnvelope(body: String?): PulseQuestionnaireFetchResult {
        val json = JSONObject(body ?: "")
        val eligible = json.optBoolean("eligible", false)
        val questionnaireJson = json.optJSONObject("questionnaire")
        if (eligible && questionnaireJson != null) {
            val questionnaire = PulseQuestionnaire.fromJson(questionnaireJson)
            val inProgressJson = json.optJSONObject("in_progress")
            val draft = inProgressJson?.let {
                PulseQuestionnaireDraft(
                    responseId = it.optString("response_id"),
                    answers = hydrateDraftAnswers(
                        it.optJSONObject("answers") ?: JSONObject(),
                        questionnaire.schema,
                    ),
                )
            }
            return PulseQuestionnaireFetchResult(questionnaire = questionnaire, inProgress = draft)
        }
        // Ineligible — surface the reason for diagnostics.
        val reason = PulseQuestionnaireIneligibleReason.fromWire(json.optStringOrNull("reason"))
        return PulseQuestionnaireFetchResult(questionnaire = null, ineligibleReason = reason)
    }

    /**
     * Save a draft ([isComplete] false) or finalize a submission ([isComplete]
     * true). The server upserts by `(project, slug, user_id)` — the SDK is
     * stateless across calls and doesn't track the response id. The returned
     * receipt's `wasSubmitted` is `true` exactly once per response (the call
     * that flipped `submitted_at` null → non-null) and is what the flow
     * container uses to transition into the success phase. Mirrors Swift's
     * `saveQuestionnaireResponse`.
     */
    suspend fun saveQuestionnaireResponse(
        slug: String,
        userId: String?,
        sessionId: String?,
        answers: Map<String, PulseQuestionnaireAnswerValue>,
        isComplete: Boolean,
        deviceInfo: DeviceInfo?,
        environment: String?,
        appVersion: String?,
        isDev: Boolean,
    ): QuestionnaireSaveOutcome {
        val payload = JSONObject().apply {
            put("bundle_id", bundleId)
            sessionId?.let { put("session_id", it) }
            userId?.let { put("user_id", it) }
            put("answers", encodeAnswers(answers))
            put("is_complete", isComplete)
            appVersion?.let { put("app_version", it) }
            put("sdk_name", PubkyPulseVersion.NAME)
            put("sdk_version", PubkyPulseVersion.CURRENT)
            environment?.let { put("environment", it) }
            deviceInfo?.deviceModel?.let { put("device_model", it) }
            deviceInfo?.osVersion?.let { put("os_version", it) }
            put("is_dev", isDev)
        }

        val httpBody = runCatching { payload.toString().toByteArray(Charsets.UTF_8) }
            .getOrElse { return QuestionnaireSaveOutcome.Failure(PulseQuestionnaireError.TransportFailure("encoding failed: ${it.message}")) }

        val request = makeRequest(questionnaireResponsesUrl(slug), httpBody)
        val response = withContext(ioDispatcher) { runCatching { httpClient.execute(request) } }
            .getOrElse { return QuestionnaireSaveOutcome.Failure(PulseQuestionnaireError.TransportFailure(it.message ?: it.toString())) }

        if (response.statusCode in 200..299) {
            val receipt = runCatching {
                val json = JSONObject(response.body ?: "")
                PulseQuestionnaireReceipt(
                    id = json.optString("id"),
                    createdAt = QuestionnaireDates.parseOrNow(json.optStringOrNull("created_at")),
                    wasSubmitted = json.optBoolean("was_submitted", false),
                )
            }.getOrElse { return QuestionnaireSaveOutcome.Failure(PulseQuestionnaireError.TransportFailure("decode failed: ${it.message}")) }
            return QuestionnaireSaveOutcome.Success(receipt)
        }
        if (response.statusCode == 400) {
            return QuestionnaireSaveOutcome.Failure(
                PulseQuestionnaireError.InvalidAnswers(response.body ?: "unknown"),
            )
        }
        if (response.statusCode == 404) {
            return QuestionnaireSaveOutcome.Failure(PulseQuestionnaireError.SlugNotFound)
        }
        return QuestionnaireSaveOutcome.Failure(
            PulseQuestionnaireError.ServerError(response.statusCode, response.body),
        )
    }

    /**
     * Globally opt the current user out of every questionnaire. Idempotent on
     * the server side. Mirrors Swift's `submitQuestionnaireDismiss`.
     */
    suspend fun submitQuestionnaireDismiss(userId: String): QuestionnaireDismissOutcome {
        val payload = JSONObject().apply {
            put("bundle_id", bundleId)
            put("user_id", userId)
        }
        val httpBody = runCatching { payload.toString().toByteArray(Charsets.UTF_8) }
            .getOrElse { return QuestionnaireDismissOutcome.Failure(PulseQuestionnaireError.TransportFailure("encoding failed: ${it.message}")) }

        val request = makeRequest(questionnaireDismissUrl, httpBody)
        val response = withContext(ioDispatcher) { runCatching { httpClient.execute(request) } }
            .getOrElse { return QuestionnaireDismissOutcome.Failure(PulseQuestionnaireError.TransportFailure(it.message ?: it.toString())) }

        if (response.statusCode in 200..299) {
            val date = runCatching {
                QuestionnaireDates.parseOrNow(JSONObject(response.body ?: "").optStringOrNull("dismissed_at"))
            }.getOrElse { return QuestionnaireDismissOutcome.Failure(PulseQuestionnaireError.TransportFailure("decode failed: ${it.message}")) }
            return QuestionnaireDismissOutcome.Success(date)
        }
        return QuestionnaireDismissOutcome.Failure(
            PulseQuestionnaireError.ServerError(response.statusCode, response.body),
        )
    }

    /**
     * POST one already-registered in-flight batch, counted so [claimIdentity]
     * can drain on it. Mirrors Swift's `send(_:)`. The retry ladder abandons the
     * batch once [persistBufferToDisk] has parked it, and [retire] — not this —
     * decides whether an undelivered batch goes to the offline queue, so a
     * parked batch is never appended twice. Replaying a parked batch that also
     * reached the server is fine: ingest deduplicates on `client_event_id`, so a
     * duplicate is cheap where a loss is not.
     *
     * [maxAttempts] is the batch's retry ladder length; see [flushAll].
     */
    private suspend fun send(pending: PendingBatch, maxAttempts: Int = MAX_RETRIES): Boolean {
        beforeSendHook?.invoke()
        val body = IngestRequestBody(bundleId, pending.events).toJsonString().toByteArray(Charsets.UTF_8)
        val request = makeRequest(ingestUrl, body)

        stateMutex.withLock { inFlightSendCount += 1 }
        try {
            return performWithRetry(request, "Ingest", maxAttempts) { isBatchPersisted(pending.id) }
        } finally {
            val waiters = stateMutex.withLock {
                inFlightSendCount -= 1
                if (inFlightSendCount == 0) {
                    val w = ArrayList(sendDrainContinuations)
                    sendDrainContinuations.clear()
                    w
                } else {
                    emptyList()
                }
            }
            for (waiter in waiters) waiter.resume(Unit)
        }
    }

    /** Whether [persistBufferToDisk] has already parked this in-flight batch. */
    private suspend fun isBatchPersisted(batchId: Long): Boolean =
        stateMutex.withLock { inFlightBatches[batchId]?.persisted == true }

    /**
     * Suspend until every in-flight ingest send has returned. Mirrors Swift's
     * `awaitInFlightSends()` (the continuation-drain pattern).
     */
    private suspend fun awaitInFlightSends() {
        val mustWait = stateMutex.withLock { inFlightSendCount > 0 }
        if (!mustWait) return
        suspendCoroutine { continuation: Continuation<Unit> ->
            scope.launch {
                val resumeNow = stateMutex.withLock {
                    if (inFlightSendCount == 0) {
                        true
                    } else {
                        sendDrainContinuations.add(continuation)
                        false
                    }
                }
                if (resumeNow) continuation.resume(Unit)
            }
        }
    }

    // MARK: - HTTP

    private fun makeRequest(url: URL, body: ByteArray): HttpRequest {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers["Authorization"] = "Bearer $apiKey"

        val finalBody: ByteArray
        if (compressionEnabled && body.size >= COMPRESSION_THRESHOLD) {
            finalBody = GzipCompressor.gzip(body)
            headers["Content-Encoding"] = "gzip"
        } else {
            finalBody = body
        }
        return HttpRequest(url = url, method = "POST", headers = headers, body = finalBody)
    }

    /**
     * Execute [request] with up to [maxAttempts] attempts. Retries transport
     * failures, 5xx and 429 (rate limiting is transient — dropping the batch
     * there is how a throttled app silently loses data); every other 4xx is
     * permanent and returns immediately. Backoff is exponential, capped at
     * [MAX_BACKOFF_SECONDS], raised to a server-sent `Retry-After` on the two
     * statuses that carry one. Mirrors Swift's `performWithRetry`.
     *
     * [isHandled] lets an ingest send abandon the ladder early once
     * [persistBufferToDisk] has parked its batch on the offline queue: the batch
     * is already durable, so there is nothing left to wait ~61 s for.
     */
    private suspend fun performWithRetry(
        request: HttpRequest,
        label: String,
        maxAttempts: Int = MAX_RETRIES,
        isHandled: suspend () -> Boolean = { false },
    ): Boolean {
        for (attempt in 0 until maxAttempts) {
            if (attempt > 0 && isHandled()) {
                Log.i(TAG, "$label batch already persisted offline, stopping retries")
                return false
            }

            val result = withContext(ioDispatcher) {
                runCatching { httpClient.execute(request) }
            }

            // Set by a 429/503 that carried a parsable Retry-After; drives the
            // backoff for the next attempt only.
            var retryAfterSeconds: Double? = null
            result.onSuccess { response ->
                val code = response.statusCode
                if (code in 200..299) {
                    parseIngestRejections(response.body)?.let { rejected ->
                        if (rejected > 0) Log.w(TAG, "Server rejected $rejected events")
                    }
                    return true
                }
                if (code in 400..499 && code != STATUS_TOO_MANY_REQUESTS) {
                    Log.w(TAG, "$label returned $code, not retrying")
                    return false
                }
                if (code == STATUS_TOO_MANY_REQUESTS || code == STATUS_SERVICE_UNAVAILABLE) {
                    retryAfterSeconds = parseRetryAfterSeconds(response.header("Retry-After"))
                }
                Log.w(TAG, "$label returned $code, attempt ${attempt + 1}/$maxAttempts")
            }.onFailure { error ->
                Log.w(TAG, "$label failed: ${error.message}, attempt ${attempt + 1}/$maxAttempts")
            }

            if (attempt < maxAttempts - 1) {
                delay((retryDelaySeconds(attempt, retryAfterSeconds) * 1000).toLong())
            }
        }
        return false
    }

    /**
     * Seconds to wait before attempt `attempt + 1`: the exponential ladder
     * (2^attempt capped at [MAX_BACKOFF_SECONDS]), never shortened by a
     * server-requested [retryAfterSeconds] and never stretched past
     * [MAX_RETRY_AFTER_SECONDS].
     */
    private fun retryDelaySeconds(attempt: Int, retryAfterSeconds: Double?): Double {
        val backoff = min(2.0.pow(attempt), MAX_BACKOFF_SECONDS)
        if (retryAfterSeconds == null) return backoff
        return min(max(retryAfterSeconds, backoff), MAX_RETRY_AFTER_SECONDS)
    }

    /**
     * Parse a `Retry-After` value: either delta-seconds or an HTTP-date (RFC
     * 9110 §10.2.3). Returns null when absent or unparsable so the caller falls
     * back to the plain ladder; a date already in the past yields 0.
     */
    private fun parseRetryAfterSeconds(value: String?): Double? {
        val raw = value?.trim()
        if (raw.isNullOrEmpty()) return null
        raw.toLongOrNull()?.let { return if (it < 0) null else it.toDouble() }
        val date = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("GMT") }
                .parse(raw)
        }.getOrNull() ?: return null
        return max((date.time - System.currentTimeMillis()) / 1000.0, 0.0)
    }

    /** Pull `rejected` out of a 2xx ingest body if present. Best-effort. */
    private fun parseIngestRejections(body: String?): Int? {
        if (body.isNullOrEmpty()) return null
        return runCatching { JSONObject(body).optInt("rejected", 0) }.getOrNull()
    }
}

/**
 * Outcome of a one-shot [EventTransport.submitFeedback] call. The Kotlin analog
 * of Swift's `Result<PulseFeedbackReceipt, PulseFeedbackError>` returned by
 * `submitFeedback`. Internal — [Pulse.sendFeedback] unwraps this into a returned
 * receipt or a thrown [PulseFeedbackError].
 */
internal sealed interface FeedbackResult {
    data class Success(val receipt: PulseFeedbackReceipt) : FeedbackResult
    data class Failure(val error: PulseFeedbackError) : FeedbackResult
}

/**
 * Outcome of [EventTransport.fetchQuestionnaire]. The Kotlin analog of Swift's
 * `Result<PulseQuestionnaireFetchResult, PulseQuestionnaireError>`. Internal — [Pulse]
 * unwraps it into a returned result or a thrown [PulseQuestionnaireError].
 */
internal sealed interface QuestionnaireFetchOutcome {
    data class Success(val result: PulseQuestionnaireFetchResult) : QuestionnaireFetchOutcome
    data class Failure(val error: PulseQuestionnaireError) : QuestionnaireFetchOutcome
}

/** Outcome of [EventTransport.saveQuestionnaireResponse]. */
internal sealed interface QuestionnaireSaveOutcome {
    data class Success(val receipt: PulseQuestionnaireReceipt) : QuestionnaireSaveOutcome
    data class Failure(val error: PulseQuestionnaireError) : QuestionnaireSaveOutcome
}

/** Outcome of [EventTransport.submitQuestionnaireDismiss]. */
internal sealed interface QuestionnaireDismissOutcome {
    data class Success(val dismissedAt: java.util.Date) : QuestionnaireDismissOutcome
    data class Failure(val error: PulseQuestionnaireError) : QuestionnaireDismissOutcome
}

/** A minimal HTTP request — the framework-agnostic input to [HttpClient]. */
internal data class HttpRequest(
    val url: URL,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
) {
    // data class with a ByteArray needs hand-written equals/hashCode; only
    // identity matters at the call sites, but provide them so the type behaves.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRequest) return false
        return url == other.url && method == other.method && headers == other.headers &&
            (body?.contentEquals(other.body ?: ByteArray(0)) ?: (other.body == null))
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * A minimal HTTP response. [headers] carries only what the transport reads back
 * (`Retry-After`); it defaults to empty so the many call sites that only care
 * about status + body stay two-argument.
 */
internal data class HttpResponse(
    val statusCode: Int,
    val body: String?,
    val headers: Map<String, String> = emptyMap(),
) {
    /** Look up a header, case-insensitively as HTTP field names require. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * The transport's HTTP seam — the analog of injecting a `URLSession` into the
 * Swift `EventTransport`. The default is [DefaultHttpClient] ([HttpURLConnection]);
 * tests substitute a fake to assert batching/retry/gzip without a live server.
 */
internal fun interface HttpClient {
    /** Execute synchronously; callers wrap in [Dispatchers.IO]. Throws on transport failure. */
    fun execute(request: HttpRequest): HttpResponse
}

/** Production [HttpClient] over [HttpURLConnection] — framework-only, no OkHttp. */
internal object DefaultHttpClient : HttpClient {
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = (request.url.openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = 15_000
            readTimeout = 30_000
            for ((key, value) in request.headers) setRequestProperty(key, value)
            if (request.body != null) {
                doOutput = true
                outputStream.use { it.write(request.body) }
            }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText)
            val headers = LinkedHashMap<String, String>()
            for ((name, values) in connection.headerFields) {
                // headerFields carries a null key for the status line; skip it.
                if (name != null) headers[name] = values.joinToString(", ")
            }
            return HttpResponse(statusCode = code, body = body, headers = headers)
        } finally {
            connection.disconnect()
        }
    }
}

/** Append a path segment to a base URL, normalizing a single slash boundary. */
private fun URL.appendPath(path: String): URL {
    val base = toString().trimEnd('/')
    return URL("$base/${path.trimStart('/')}")
}

/**
 * Percent-encode a path segment (e.g. a questionnaire slug). Slugs are already
 * `[a-z0-9-]`, but encode defensively so a malformed slug can't break the path.
 * `URLEncoder` targets the query grammar (`+` for space), so convert `+` back to
 * `%20` for path use.
 */
private fun String.urlPathEncoded(): String =
    java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

/** Percent-encode a query-parameter value (`+` for space is correct here). */
private fun String.urlQueryEncoded(): String =
    java.net.URLEncoder.encode(this, "UTF-8")
