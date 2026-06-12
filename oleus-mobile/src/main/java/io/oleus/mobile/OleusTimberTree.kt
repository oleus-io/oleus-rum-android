package io.oleus.mobile

import android.util.Log
import timber.log.Timber

/**
 * Timber tree forwarding WARN+ logs (and any logged Throwable) to Oleus.
 * Requires the host app to depend on Timber (this SDK only compiles against it).
 *
 *     Timber.plant(OleusTimberTree())
 */
public class OleusTimberTree(
    private val minPriority: Int = Log.WARN,
) : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < minPriority && t == null) return

        if (t != null) {
            OleusMobile.capture(t, mapOf("log_tag" to (tag ?: ""), "log_message" to message))
            return
        }
        val severity = when (priority) {
            Log.WARN -> "WARN"
            Log.ERROR -> "ERROR"
            Log.ASSERT -> "FATAL"
            else -> "INFO"
        }
        val attrs = OleusMobile.baseAttributes().toMutableMap()
        attrs["log_tag"] = tag ?: ""
        OleusMobile.ship(System.currentTimeMillis(), severity, message, attrs)
    }
}
