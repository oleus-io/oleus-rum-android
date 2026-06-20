package io.oleus.mobile

import android.os.Handler
import android.os.Looper
import android.view.Choreographer

/**
 * Choreographer-based frame-drop detector (Android equivalent of CADisplayLink).
 *
 * Registers a FrameCallback after each vsync. If the time between consecutive
 * callbacks exceeds [slowThresholdMs] (~3 frames at 60 Hz) the frame is "slow";
 * above [frozenThresholdMs] it is a "frozen frame". Both call [onJank] with the
 * frame duration and a type string so the SDK can ship them as log events.
 *
 * Complements ANR detection (which catches thread stalls) with frame-level
 * render budget violations that are too short to trigger the ANR threshold.
 */
internal class JankMonitor(
    private val slowThresholdMs:   Long = 50L,
    private val frozenThresholdMs: Long = 700L,
    private val onJank: (frameMs: Long, type: String) -> Unit,
) {
    @Volatile private var running    = false
    @Volatile private var lastFrameNs: Long = 0

    // Must be obtained + posted from the main thread.
    private val mainHandler  = Handler(Looper.getMainLooper())
    private var choreographer: Choreographer? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val prev = lastFrameNs
            lastFrameNs = frameTimeNanos
            if (prev > 0) {
                val frameMs = (frameTimeNanos - prev) / 1_000_000L
                when {
                    frameMs >= frozenThresholdMs -> onJank(frameMs, "frozen_frame")
                    frameMs >= slowThresholdMs   -> onJank(frameMs, "slow_frame")
                }
            }
            choreographer?.postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastFrameNs = 0
        mainHandler.post {
            val ch = Choreographer.getInstance()
            choreographer = ch
            ch.postFrameCallback(frameCallback)
        }
    }

    fun stop() {
        running = false
        mainHandler.post { choreographer?.removeFrameCallback(frameCallback) }
    }
}
