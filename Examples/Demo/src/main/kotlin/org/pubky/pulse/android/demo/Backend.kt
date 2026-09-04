package org.pubky.pulse.android.demo

import org.pubky.pulse.android.Pulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Android analog of the Swift demo's `callBackend`. POSTs JSON to the Node
 * demo server (`http://10.0.2.2:4007` — the emulator alias for the host's
 * localhost:4007) and stamps the current Pubky Pulse session id on the
 * `X-Pulse-Session-Id` header so the backend's server-side events correlate into
 * the same session as the client events.
 *
 * Runs the blocking [HttpURLConnection] call on [Dispatchers.IO]; returns a
 * short "status: body" string for the event log, matching the Swift version.
 */
suspend fun callBackend(path: String, body: Map<String, String?>): String =
    withContext(Dispatchers.IO) {
        try {
            val url = URL("http://10.0.2.2:4007$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 5_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                Pulse.sessionId?.let { setRequestProperty("X-Pulse-Session-Id", it) }
            }

            val payload = JSONObject()
            for ((key, value) in body) {
                if (value != null) payload.put(key, value)
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "No body"
            conn.disconnect()
            "$status: $text"
        } catch (e: Throwable) {
            "Error: ${e.message}"
        }
    }
