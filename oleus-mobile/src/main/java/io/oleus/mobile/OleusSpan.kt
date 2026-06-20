package io.oleus.mobile

import java.security.SecureRandom

/**
 * A lightweight distributed tracing span.
 *
 * Spans are sent as OTLP log events with trace/span IDs so they can be
 * correlated across services. The [traceId] is propagated to child spans.
 *
 * ```kotlin
 * val span = OleusMobile.startSpan("api.call", attributes = mapOf("endpoint" to "/events"))
 * try {
 *     // ... do work ...
 *     span.setTag("status_code", "200")
 * } catch (e: Exception) {
 *     span.setError(e.message ?: "unknown")
 *     throw e
 * } finally {
 *     span.finish()
 * }
 * ```
 */
public class OleusSpan internal constructor(
    public val name:    String,
    public val traceId: String,
    private val onFinish: (OleusSpan) -> Unit,
) {
    public val spanId:  String = randomHex(16)
    private val startMs: Long  = System.currentTimeMillis()
    private val tags   = mutableMapOf<String, String>()
    private var status = "ok"
    @Volatile private var finished = false

    /** Add or update a tag on this span. */
    public fun setTag(key: String, value: String) { tags[key] = value }

    /** Mark the span as errored. */
    public fun setError(message: String) {
        status = "error"
        tags["error.message"] = message
    }

    /** Start a child span that inherits this span's [traceId]. */
    public fun childSpan(name: String, attributes: Map<String, String> = emptyMap()): OleusSpan {
        val childAttrs = attributes.toMutableMap().also { it["parent_span_id"] = spanId }
        return OleusMobile.startSpan(name, traceId = traceId, attributes = childAttrs)
    }

    /** Ship the span. Subsequent calls are no-ops. */
    public fun finish() {
        if (finished) return
        finished = true
        tags["span.duration_ms"] = (System.currentTimeMillis() - startMs).toString()
        tags["span.status"] = status
        onFinish(this)
    }

    internal val allTags:      Map<String, String> get() = tags
    internal val startTimestamp: Long              get() = startMs
    internal val spanStatus:   String              get() = status

    companion object {
        private val rng = SecureRandom()
        internal fun randomHex(len: Int): String {
            val bytes = ByteArray(len / 2)
            rng.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
