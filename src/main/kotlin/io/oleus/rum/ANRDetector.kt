package io.oleus.rum

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ANRDetector(private val sdk: OleusRUM, private val thresholdMs: Long = 5000L) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var responded = true

    fun start() {
        watchdog.scheduleAtFixedRate({
            responded = false
            mainHandler.post { responded = true }
            Thread.sleep(thresholdMs)
            if (!responded) {
                // Main thread blocked — capture ANR
                val mainThreadStack = Looper.getMainLooper().thread.stackTrace
                    .joinToString("\n") { "\tat ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                sdk.trackANR(durationMs = thresholdMs, stackTrace = mainThreadStack)
            }
        }, thresholdMs, thresholdMs, TimeUnit.MILLISECONDS)
    }

    fun stop() { watchdog.shutdown() }
}
