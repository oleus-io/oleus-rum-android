package io.oleus.mobile

import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Oleus Android SDK — crash reporting, ANR detection, sessions, breadcrumbs.
 *
 * ```kotlin
 * // In Application.onCreate():
 * OleusMobile.initialize(
 *     context = this,
 *     endpoint = "https://oleus.example.com/otlp",   // HTTPS — Android blocks cleartext by default
 *     service = "rondo-android",
 *     apiKey = BuildConfig.OLEUS_INGEST_KEY,
 * )
 * ```
 *
 * Release builds with R8 enabled: upload `mapping.txt` per release so the
 * platform can retrace stacks (see r8-upload.gradle in the repo root).
 */
public object OleusMobile {
    private var shipper: OtlpShipper? = null
    private var sessions: SessionTracker? = null
    private var anrWatchdog: AnrWatchdog? = null
    private var activityTracker: ActivityTracker? = null
    private var sessionReplay: SessionReplay? = null
    private var appVersion: String = "unknown"
    private var deviceId: String = "unknown"
    private var identity: OleusIdentity? = null
    private val breadcrumbs = mutableListOf<JSONObject>()
    private const val MAX_BREADCRUMBS = 50
    private const val PREFS = "io.oleus.mobile"

    @JvmStatic
    @JvmOverloads
    public fun initialize(
        context: Context,
        endpoint: String,
        service: String,
        apiKey: String? = null,
        environment: String = "production",
        anrDetection: Boolean = true,
        sessionReplayEnabled: Boolean = true,
        sessionReplaySampleRate: Double = 0.1,
    ) {
        if (shipper != null) return
        val app = context.applicationContext

        appVersion = readAppVersion(app)
        deviceId = readDeviceId(app)

        val sharedPrefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        identity = OleusIdentity(SharedPrefsIdentityStore(sharedPrefs))

        val baseDir = File(app.filesDir, "oleus").apply { mkdirs() }
        val sessionMarker = File(baseDir, "session.current")
        val previousSession = SessionTracker.previousSessionId(sessionMarker)

        val ship = OtlpShipper(
            endpoint = endpoint.trimEnd('/'),
            service = service,
            apiKey = apiKey,
            environment = environment,
            release = appVersion,
            queueDir = File(baseDir, "queue"),
        )
        shipper = ship

        // 1. surface system-recorded deaths from the previous run (ANR / native crash)
        reapApplicationExitInfo(app, ship, previousSession)

        // 2. JVM crash handler — chains the default handler so the system
        //    crash dialog / Play reporting still run
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleCrash(ship, thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // 3. sessions
        if (app is Application) {
            sessions = SessionTracker(app, sessionMarker) { event, extra ->
                val attrs = baseAttributes().toMutableMap()
                attrs["event.name"] = event
                attrs["event.domain"] = "oleus"
                attrs.putAll(extra)
                ship.enqueue(System.currentTimeMillis(), "INFO", event, attrs)
            }
        }

        // 4. ANR watchdog
        if (anrDetection) {
            anrWatchdog = AnrWatchdog(onAnr = { stack, blockedForMs ->
                val attrs = baseAttributes().toMutableMap()
                attrs["error_type"] = "ANR"
                attrs["error_stack"] = stack
                attrs["blocked_ms"] = blockedForMs.toString()
                attrs["breadcrumbs"] = breadcrumbsJson()
                ship.enqueue(System.currentTimeMillis(), "ERROR", "ANR: main thread blocked ${blockedForMs}ms", attrs)
            }).also { it.start() }
        }

        // 5. auto activity tracking + session replay
        if (app is Application) {
            val tracker = ActivityTracker()
            if (sessionReplayEnabled && Math.random() < sessionReplaySampleRate) {
                val replay = SessionReplay()
                replay.start()
                tracker.sessionReplay = replay
                sessionReplay = replay
            }
            app.registerActivityLifecycleCallbacks(tracker)
            activityTracker = tracker
        }
    }

    /** Record a non-fatal exception. Batched through the disk-backed queue. */
    @JvmStatic
    @JvmOverloads
    public fun capture(throwable: Throwable, context: Map<String, Any>? = null) {
        val ship = shipper ?: return
        val attrs = baseAttributes().toMutableMap()
        attrs["error_type"] = throwable.javaClass.simpleName
        attrs["error_stack"] = throwable.stackTraceToString()
        attrs["breadcrumbs"] = breadcrumbsJson()
        context?.forEach { (k, v) -> attrs[k] = v.toString() }
        ship.enqueue(System.currentTimeMillis(), "ERROR", throwable.message ?: throwable.javaClass.simpleName, attrs)
    }

    /** Add a breadcrumb (screen navigation, key taps, network milestones). */
    @JvmStatic
    @JvmOverloads
    public fun addBreadcrumb(message: String, category: String = "default", attributes: Map<String, Any>? = null) {
        val crumb = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("message", message)
            put("category", category)
            attributes?.forEach { (k, v) -> put(k, v.toString()) }
        }
        synchronized(breadcrumbs) {
            breadcrumbs.add(crumb)
            if (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.removeAt(0)
        }
    }

    /**
     * Record a screen view. Activities are tracked automatically; for Compose
     * wire this to the NavController:
     *
     * ```kotlin
     * navController.addOnDestinationChangedListener { _, dest, _ ->
     *     OleusMobile.trackScreen(dest.route ?: "unknown")
     * }
     * ```
     */
    @JvmStatic
    @JvmOverloads
    public fun trackScreen(name: String, renderMs: Long? = null) {
        val ship = shipper ?: return
        addBreadcrumb(name, category = "navigation")
        val attrs = baseAttributes().toMutableMap()
        attrs["event.name"] = "screen_view"
        attrs["event.domain"] = "oleus"
        attrs["screen"] = name
        renderMs?.let { attrs["render_ms"] = it.toString() }
        ship.enqueue(System.currentTimeMillis(), "INFO", "screen_view", attrs)
    }

    /** Force-flush queued events. */
    @JvmStatic
    public fun flush() {
        shipper?.flush()
    }

    // ── identity ────────────────────────────────────────────────────────────────

    /**
     * Tie the current anonymous install to a known user id. Emits a `${'$'}identify`
     * carrying the anonymous id so pre-login activity stitches to one person.
     * [properties] become person properties (`${'$'}set`).
     */
    @JvmStatic
    @JvmOverloads
    public fun identify(distinctId: String, properties: Map<String, Any>? = null) {
        if (distinctId.isEmpty()) return
        val ship = shipper ?: return
        val id = identity ?: return
        val anon = id.anonId
        id.identify(distinctId)
        val attrs = baseAttributes().toMutableMap()
        attrs["event.name"] = "\$identify"
        attrs["event.domain"] = "oleus"
        attrs["\$anon_id"] = anon
        properties?.forEach { (k, v) -> attrs["\$set.$k"] = v.toString() }
        ship.enqueue(System.currentTimeMillis(), "INFO", "\$identify", attrs)
    }

    /** Merge another distinct id into the current person (e.g. web ↔ Android). */
    @JvmStatic
    public fun alias(otherDistinctId: String) {
        if (otherDistinctId.isEmpty()) return
        val ship = shipper ?: return
        val attrs = baseAttributes().toMutableMap()
        attrs["event.name"] = "\$merge"
        attrs["event.domain"] = "oleus"
        attrs["\$alias"] = otherDistinctId
        ship.enqueue(System.currentTimeMillis(), "INFO", "\$merge", attrs)
    }

    /** Clear identity on logout: forget the user id and rotate the anonymous id. */
    @JvmStatic
    public fun reset() {
        identity?.reset()
    }

    /** The id currently sent on every event (user id once identified, else anon).
     *  Named to match the browser/iOS SDKs (`getDistinctId`). */
    @JvmStatic
    public fun getDistinctId(): String = identity?.distinctId ?: "unknown"

    // ── internals ─────────────────────────────────────────────────────────────

    private fun handleCrash(ship: OtlpShipper, thread: Thread, throwable: Throwable) {
        val attrs = baseAttributes().toMutableMap()
        attrs["error_type"] = throwable.javaClass.simpleName
        attrs["error_stack"] = throwable.stackTraceToString()
        attrs["thread"] = thread.name
        attrs["breadcrumbs"] = breadcrumbsJson()
        attrs["crash_source"] = "uncaught_exception"
        // synchronous persist — the process dies right after this returns;
        // the queued file ships on next launch
        ship.persistBlocking(
            System.currentTimeMillis(), "FATAL",
            "${throwable.javaClass.simpleName}: ${throwable.message ?: ""}", attrs,
        )
    }

    private fun reapApplicationExitInfo(context: Context, ship: OtlpShipper, previousSession: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastSeen = prefs.getLong("last_exit_timestamp", 0)
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            var newest = lastSeen

            for (exit in exits) {
                if (exit.timestamp <= lastSeen) continue
                if (exit.timestamp > newest) newest = exit.timestamp

                val (type, severity) = when (exit.reason) {
                    ApplicationExitInfo.REASON_ANR -> "ANR_SystemKill" to "FATAL"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "NativeCrash" to "FATAL"
                    else -> continue
                }
                val attrs = baseAttributes().toMutableMap()
                attrs["error_type"] = type
                attrs["error_stack"] = exit.traceInputStream
                    ?.bufferedReader()?.use { it.readText().take(64 * 1024) } ?: ""
                attrs["crash_source"] = "application_exit_info"
                previousSession?.let { attrs["session.id"] = it }
                ship.enqueue(exit.timestamp, severity, "$type: ${exit.description ?: ""}", attrs)
            }
            prefs.edit().putLong("last_exit_timestamp", newest).apply()
        } catch (_: Exception) {
            // exit-info reaping is best-effort
        }
    }

    internal fun baseAttributes(): Map<String, String> {
        val attrs = mutableMapOf(
            "platform" to "android",
            "mobile" to "true",
            "device_model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "os_version" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "app_version" to appVersion,
            "release" to appVersion,
            "device.id" to deviceId,
            "distinct_id" to (identity?.distinctId ?: "unknown"),
        )
        sessions?.sessionId?.let { attrs["session.id"] = it }
        return attrs
    }

    internal fun ship(timestampMs: Long, severity: String, body: String, attributes: Map<String, String>) {
        shipper?.enqueue(timestampMs, severity, body, attributes)
    }

    private fun breadcrumbsJson(): String {
        val arr = JSONArray()
        synchronized(breadcrumbs) { breadcrumbs.forEach { arr.put(it) } }
        return arr.toString()
    }

    private fun readAppVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
                   else @Suppress("DEPRECATION") info.versionCode.toLong()
        "${info.versionName ?: "0.0"}+$code"
    } catch (_: Exception) {
        "unknown"
    }

    private fun readDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        val generated = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("device_id", generated).apply()
        return generated
    }
}
