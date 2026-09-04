package org.pubky.pulse.android

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Observes app-process foreground/background transitions and flushes the
 * [EventTransport] on background. The Android analog of Swift's
 * `LifecycleObserver`.
 *
 * Swift wires `UIApplication.didEnterBackgroundNotification` /
 * `willEnterForegroundNotification` / `willTerminateNotification` and, on
 * background, acquires a `beginBackgroundTask` window to `flushAll()` before the
 * OS suspends. Android has no per-app background-task grant for a library, so
 * the analog is [ProcessLifecycleOwner] (which reports whole-process
 * foreground/background — not per-Activity), driving:
 *  - **ON_START** (foreground): bump the questionnaire foreground counter and
 *    emit `sdk:app_foregrounded`. Mirrors Swift's `willEnterForeground` handler.
 *    The process-cold-launch path doesn't double-fire because
 *    `ProcessLifecycleOwner` reports the first `ON_START` as a foreground only
 *    after the host's first real background→foreground transition is observed.
 *  - **ON_STOP** (background): emit `sdk:app_backgrounded` and run [flushAll]
 *    (when `flushOnBackground` is on) so the in-memory buffer reaches the
 *    server — one attempt per batch, since the process may not live long enough
 *    for a retry ladder; on the way out also [persistBufferToDisk] so anything
 *    appended during the flush survives process death. Best-effort: Android may
 *    kill the process at any time after `ON_STOP`, so the disk persist is the
 *    durable backstop — exactly Swift's watchOS strategy (no background-task
 *    grant).
 *
 * The transport calls run on the SDK [scope]; the lifecycle callbacks
 * themselves arrive on the main thread (where [ProcessLifecycleOwner] posts).
 * Constructed + [start]ed only when `flushOnBackground` is enabled (matching
 * Swift's `if config.flushOnBackground` gate), and [stop]ped + replaced on
 * re-configure.
 */
internal class LifecycleObserver(
    private val transport: EventTransport,
    private val scope: CoroutineScope,
    // Injectable so tests can drive a fake LifecycleOwner without the real
    // process-global owner. Production passes `ProcessLifecycleOwner.get()`.
    private val lifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get(),
) : DefaultLifecycleObserver {

    // ProcessLifecycleOwner emits ON_START on the very first foreground (cold
    // launch). Swift's `willEnterForeground` fires only on *return* to
    // foreground, not the initial launch. Skip the first ON_START so the
    // foreground counter + `sdk:app_foregrounded` semantics match Swift: a cold
    // launch is `sdk:session_started` (emitted by configure), not a foreground.
    private var sawFirstStart = false

    /** Register with the process lifecycle. Must be called on the main thread. */
    fun start() {
        lifecycleOwner.lifecycle.addObserver(this)
    }

    /** Deregister. Idempotent. Mirrors Swift's `stop()`. */
    fun stop() {
        lifecycleOwner.lifecycle.removeObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!sawFirstStart) {
            // Cold-launch ON_START — already represented by sdk:session_started.
            sawFirstStart = true
            return
        }
        PulseQuestionnaireState.shared?.incrementForeground()
        Pulse.info("sdk:app_foregrounded")
    }

    override fun onStop(owner: LifecycleOwner) {
        Pulse.info("sdk:app_backgrounded")
        scope.launch {
            // One attempt per batch: the full retry ladder can sit in a 429/503
            // `Retry-After` for up to a minute per attempt, and Android may kill
            // the backgrounded process long before it returns — which would also
            // mean never reaching the persist below. Anything undelivered goes
            // straight to the offline queue instead.
            transport.flushAll(maxAttemptsPerBatch = 1)
            // Durable backstop: anything appended during the flush (including the
            // sdk:app_backgrounded event above, still hopping the dedup filter →
            // transport) plus any batch still working through the retry ladder is
            // persisted to disk so it survives process death after ON_STOP.
            // Mirrors Swift's watchOS handler.
            transport.persistBufferToDisk()
        }
    }
}
