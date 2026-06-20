package io.oleus.mobile

import android.os.Handler
import android.os.Looper

/**
 * Main-thread ANR watchdog.
 *
 * Posts a ticker to the main looper every [intervalMs]; if it has not run
 * within [thresholdMs] the main thread is blocked and we report an ANR with
 * the main thread's stack trace. One report per blockage (re-arms after the
 * main thread recovers). Complements ApplicationExitInfo (which only
 * surfaces ANRs the system killed, on next launch).
 */
internal class AnrWatchdog(
    private val thresholdMs: Long = 5_000,
    private val intervalMs: Long = 1_000,
    private val onAnr: (stack: String, blockedForMs: Long) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastTickMs = System.currentTimeMillis()
    @Volatile private var reportedCurrentBlockage = false
    @Volatile private var running = false

    private val ticker = Runnable { lastTickMs = System.currentTimeMillis() }

    fun start() {
        if (running) return
        running = true
        lastTickMs = System.currentTimeMillis()
        Thread({
            while (running) {
                mainHandler.post(ticker)
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { return@Thread }

                val blockedFor = System.currentTimeMillis() - lastTickMs
                if (blockedFor > thresholdMs && !reportedCurrentBlockage) {
                    reportedCurrentBlockage = true
                    // Capture all threads — main thread marked [BLOCKED]
                    val mainThread = Looper.getMainLooper().thread
                    val stack = Thread.getAllStackTraces().entries.joinToString("\n\n") { (t, frames) ->
                        val marker = if (t == mainThread) " [BLOCKED]" else ""
                        "Thread: ${t.name} [${t.state}]$marker\n" +
                            frames.joinToString("\n") { "  at $it" }
                    }
                    onAnr(stack, blockedFor)
                } else if (blockedFor < thresholdMs) {
                    reportedCurrentBlockage = false
                }
            }
        }, "oleus-anr-watchdog").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
    }
}
