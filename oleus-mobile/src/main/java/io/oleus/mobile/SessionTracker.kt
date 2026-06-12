package io.oleus.mobile

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.io.File
import java.util.UUID

/**
 * Sessions via Application.ActivityLifecycleCallbacks (no androidx dependency).
 *
 * A session starts on init and on foregrounding after >30 min in background;
 * it ends on backgrounding. The live session id is persisted to disk so a
 * hard crash on the next launch still attributes to it — that attribution is
 * what makes crash-free-sessions per release computable.
 */
internal class SessionTracker(
    application: Application,
    private val markerFile: File,
    private val emit: (event: String, attrs: Map<String, String>) -> Unit,
) : Application.ActivityLifecycleCallbacks {

    @Volatile
    var sessionId: String = UUID.randomUUID().toString()
        private set

    private var startedActivities = 0
    private var backgroundedAtMs = 0L
    private val sessionTimeoutMs = 30L * 60 * 1000
    private val lock = Any()

    init {
        persistMarker()
        emit("session_start", emptyMap())
        application.registerActivityLifecycleCallbacks(this)
    }

    companion object {
        /** Session id active when the previous process died. */
        fun previousSessionId(markerFile: File): String? =
            try { markerFile.readText().trim().ifEmpty { null } } catch (_: Exception) { null }
    }

    override fun onActivityStarted(activity: Activity) {
        synchronized(lock) {
            if (startedActivities == 0) {
                val backgroundedFor = System.currentTimeMillis() - backgroundedAtMs
                if (backgroundedAtMs > 0 && backgroundedFor > sessionTimeoutMs) {
                    sessionId = UUID.randomUUID().toString()
                    persistMarker()
                    emit("session_start", emptyMap())
                } else if (backgroundedAtMs > 0) {
                    emit("session_start", mapOf("resumed" to "true"))
                }
            }
            startedActivities++
        }
    }

    override fun onActivityStopped(activity: Activity) {
        synchronized(lock) {
            startedActivities--
            if (startedActivities <= 0) {
                startedActivities = 0
                backgroundedAtMs = System.currentTimeMillis()
                emit("session_end", emptyMap())
            }
        }
    }

    private fun persistMarker() {
        try { markerFile.writeText(sessionId) } catch (_: Exception) { /* best effort */ }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityResumed(activity: Activity) {
        // automatic screen_view per Activity; Compose screens use
        // OleusMobile.trackScreen() from the NavController listener
        OleusMobile.trackScreen(activity.javaClass.simpleName)
    }
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
