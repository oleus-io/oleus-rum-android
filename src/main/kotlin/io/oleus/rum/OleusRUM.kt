package io.oleus.rum

import android.app.Application
import android.os.Build
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class OleusConfiguration(
    val apiKey: String,
    val endpoint: String = "https://api.internal.oleus.io",
    val sessionSampleRate: Double = 1.0,
    val sessionReplayEnabled: Boolean = true,
    val sessionReplaySampleRate: Double = 0.1,
    val networkInstrumentationEnabled: Boolean = true,
    val crashReportingEnabled: Boolean = true,
    val anrDetectionEnabled: Boolean = true,
    val batchSize: Int = 50,
    val flushIntervalSeconds: Long = 30L,
)

class OleusRUM private constructor(
    private val app: Application,
    val config: OleusConfiguration,
) {
    val sessionId: String = UUID.randomUUID().toString()
    private var currentViewId: String? = null
    private var currentViewName: String? = null
    private val batcher = EventBatcher(config)
    private var crashReporter: CrashReporter? = null
    private var anrDetector: ANRDetector? = null
    private var sessionReplay: SessionReplay? = null
    private val appVersion: String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) { "unknown" }

    companion object {
        @Volatile var shared: OleusRUM? = null

        @JvmStatic fun start(app: Application, config: OleusConfiguration): OleusRUM {
            val sdk = OleusRUM(app, config)
            shared = sdk
            sdk.boot()
            return sdk
        }
    }

    private fun boot() {
        batcher.start()
        if (Math.random() > config.sessionSampleRate) return
        enqueue(makeEvent("session_start", emptyMap()))
        if (config.crashReportingEnabled) { crashReporter = CrashReporter(this); crashReporter!!.install() }
        if (config.anrDetectionEnabled) { anrDetector = ANRDetector(this); anrDetector!!.start() }
        app.registerActivityLifecycleCallbacks(ActivityTracker(this))
        if (config.sessionReplayEnabled && Math.random() < config.sessionReplaySampleRate) {
            sessionReplay = SessionReplay(this)
            sessionReplay!!.start()
        }
    }

    fun trackViewStart(name: String) {
        currentViewId = UUID.randomUUID().toString()
        currentViewName = name
        enqueue(makeEvent("view_start", mapOf("view_name" to name), viewId = currentViewId))
        sessionReplay?.currentActivity  // update ref
    }

    fun trackViewEnd(name: String) {
        enqueue(makeEvent("view_end", mapOf("view_name" to name), viewId = currentViewId))
        currentViewId = null
    }

    fun trackAction(name: String, attributes: Map<String, Any> = emptyMap()) {
        enqueue(makeEvent("action", attributes + mapOf("action_name" to name)))
    }

    fun trackError(
        message: String,
        throwable: Throwable? = null,
        attributes: Map<String, Any> = emptyMap(),
    ) {
        val attrs = attributes.toMutableMap()
        attrs["message"] = message
        if (throwable != null) {
            attrs["error_type"] = throwable.javaClass.simpleName
            attrs["error_description"] = throwable.localizedMessage ?: ""
            attrs["stack_trace"] = throwable.stackTraceToString().take(4000)
        }
        enqueue(makeEvent("error", attrs))
    }

    fun addBreadcrumb(
        message: String,
        category: String = "default",
        attributes: Map<String, Any> = emptyMap(),
    ) {
        val attrs = attributes.toMutableMap()
        attrs["message"] = message
        attrs["category"] = category
        enqueue(makeEvent("breadcrumb", attrs))
    }

    fun trackResource(url: String, method: String, statusCode: Int, durationMs: Double, traceId: String, spanId: String) {
        enqueue(makeEvent("resource", mapOf("url" to url, "method" to method,
            "status_code" to statusCode, "duration_ms" to durationMs,
            "trace_id" to traceId, "span_id" to spanId)))
    }

    fun trackCrash(exceptionClass: String, message: String, stackTrace: String, isFatal: Boolean) {
        enqueue(makeEvent("crash", mapOf("exception_class" to exceptionClass,
            "message" to message, "stack_trace" to stackTrace.take(8000), "is_fatal" to isFatal)))
    }

    fun trackANR(durationMs: Long, stackTrace: String) {
        enqueue(makeEvent("anr", mapOf("duration_ms" to durationMs, "stack_trace" to stackTrace.take(4000))))
    }

    fun trackReplayFrame(wireframe: Map<String, Any>) {
        enqueue(makeEvent("replay_segment", mapOf("frame" to wireframe)))
    }

    fun flushSync() = batcher.flush()

    private fun enqueue(event: RUMEvent) = batcher.enqueue(event)

    private fun makeEvent(type: String, attrs: Map<String, Any>, viewId: String? = null) = RUMEvent(
        type = type,
        session_id = sessionId,
        view_id = viewId ?: currentViewId,
        app_version = appVersion,
        os_version = Build.VERSION.RELEASE,
        device_model = Build.MODEL,
        attributes = attrs,
    )
}
