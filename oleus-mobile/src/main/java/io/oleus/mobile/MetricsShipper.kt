package io.oleus.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * In-memory custom metrics accumulator for the Oleus Android SDK.
 *
 * Collects gauge samples (last-write-wins), delta counters, and histogram
 * samples between flushes, then ships a single OTLP/HTTP metrics payload to
 * /v1/metrics every 30 seconds.
 *
 * Pipeline: SDK → /otlp/v1/metrics → OTEL collector → VictoriaMetrics
 * (Prometheus remote-write) — metrics become queryable time-series.
 */
internal class MetricsShipper(
    private val endpoint:    String,
    private val service:     String,
    private val apiKey:      String?,
    private val environment: String,
    private val release:     String,
) {
    companion object {
        val BUCKET_BOUNDS = doubleArrayOf(5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1_000.0, 2_500.0, 5_000.0, 10_000.0)
    }

    private data class MetricKey(val name: String, val tags: Map<String, String>)

    private class CounterState(var sum: Double, val startMs: Long)

    private class HistState(val startMs: Long) {
        var count: Long   = 0
        var sum: Double   = 0.0
        var min: Double   = Double.MAX_VALUE
        var max: Double   = -Double.MAX_VALUE
        val buckets       = IntArray(BUCKET_BOUNDS.size + 1)
        fun record(v: Double) {
            count++; sum += v
            if (v < min) min = v
            if (v > max) max = v
            for ((i, bound) in BUCKET_BOUNDS.withIndex()) {
                if (v <= bound) { buckets[i]++; return }
            }
            buckets[buckets.size - 1]++
        }
    }

    private val lock       = Any()
    private val gauges     = HashMap<MetricKey, Double>()
    private val counters   = HashMap<MetricKey, CounterState>()
    private val histograms = HashMap<MetricKey, HistState>()

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "oleus-metrics").apply { isDaemon = true }
    }

    init { executor.scheduleWithFixedDelay({ flushNow() }, 30, 30, TimeUnit.SECONDS) }

    // ── public API (thread-safe) ──────────────────────────────────────────────

    fun recordGauge(name: String, value: Double, tags: Map<String, String>) {
        synchronized(lock) { gauges[MetricKey(name, tags)] = value }
    }

    fun recordIncrement(name: String, by: Double, tags: Map<String, String>) {
        val nowMs = System.currentTimeMillis()
        synchronized(lock) {
            val key = MetricKey(name, tags)
            counters[key]?.also { it.sum += by } ?: run { counters[key] = CounterState(by, nowMs) }
        }
    }

    fun recordHistogram(name: String, value: Double, tags: Map<String, String>) {
        val nowMs = System.currentTimeMillis()
        synchronized(lock) {
            val key = MetricKey(name, tags)
            histograms.getOrPut(key) { HistState(nowMs) }.record(value)
        }
    }

    fun flush() { executor.execute { flushNow() } }

    // ── private ───────────────────────────────────────────────────────────────

    private fun flushNow() {
        val nowNs: Long
        val gaugesCopy:     Map<MetricKey, Double>
        val countersCopy:   Map<MetricKey, CounterState>
        val histogramsCopy: Map<MetricKey, HistState>

        synchronized(lock) {
            if (gauges.isEmpty() && counters.isEmpty() && histograms.isEmpty()) return
            nowNs          = System.currentTimeMillis() * 1_000_000L
            gaugesCopy     = HashMap(gauges)
            countersCopy   = HashMap(counters)
            histogramsCopy = HashMap(histograms)
            val nowMs = System.currentTimeMillis()
            for (key in counters.keys) counters[key] = CounterState(0.0, nowMs)
            histograms.clear()
        }

        val metrics = JSONArray()
        for ((key, value) in gaugesCopy)
            metrics.put(buildGauge(key.name, key.tags, value, nowNs))
        for ((key, c) in countersCopy) if (c.sum > 0)
            metrics.put(buildSum(key.name, key.tags, c.sum, c.startMs * 1_000_000L, nowNs))
        for ((key, h) in histogramsCopy) if (h.count > 0)
            metrics.put(buildHistogram(key.name, key.tags, h, nowNs))
        if (metrics.length() == 0) return

        val payload = JSONObject().put("resourceMetrics", JSONArray().put(JSONObject().apply {
            put("resource", JSONObject().put("attributes", JSONArray().apply {
                put(attr("service.name",           service))
                put(attr("service.version",        release))
                put(attr("platform",               "android"))
                put(attr("deployment.environment", environment))
            }))
            put("scopeMetrics", JSONArray().put(JSONObject().apply {
                put("scope",   JSONObject().put("name", "io.oleus.mobile").put("version", "1.0"))
                put("metrics", metrics)
            }))
        }))

        try {
            val conn = URL("$endpoint/v1/metrics").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            apiKey?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            conn.connectTimeout = 10_000
            conn.readTimeout    = 10_000
            conn.doOutput = true
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }

    private fun attr(k: String, v: String) =
        JSONObject().put("key", k).put("value", JSONObject().put("stringValue", v))

    private fun tags(tags: Map<String, String>) = JSONArray().also { arr ->
        tags.forEach { (k, v) -> arr.put(attr(k, v)) }
    }

    private fun buildGauge(name: String, tags: Map<String, String>, value: Double, timeNs: Long) =
        JSONObject().put("name", name).put("gauge", JSONObject().put("dataPoints", JSONArray().put(
            JSONObject().put("timeUnixNano", timeNs.toString())
                        .put("asDouble", value)
                        .put("attributes", tags(tags))
        )))

    private fun buildSum(name: String, tags: Map<String, String>, sum: Double, startNs: Long, timeNs: Long) =
        JSONObject().put("name", name).put("sum", JSONObject().apply {
            put("dataPoints", JSONArray().put(JSONObject()
                .put("startTimeUnixNano", startNs.toString())
                .put("timeUnixNano",      timeNs.toString())
                .put("asDouble", sum)
                .put("attributes", tags(tags))))
            put("aggregationTemporality", 2) // DELTA
            put("isMonotonic", true)
        })

    private fun buildHistogram(name: String, tags: Map<String, String>, h: HistState, timeNs: Long): JSONObject {
        val cumulative = JSONArray()
        var running = 0
        for (c in h.buckets) { running += c; cumulative.put(running.toString()) }
        val bounds = JSONArray().also { a -> BUCKET_BOUNDS.forEach { a.put(it) } }
        return JSONObject().put("name", name).put("histogram", JSONObject().apply {
            put("dataPoints", JSONArray().put(JSONObject().apply {
                put("startTimeUnixNano", (h.startMs * 1_000_000L).toString())
                put("timeUnixNano",      timeNs.toString())
                put("count",             h.count.toString())
                put("sum",               h.sum)
                put("min",               if (h.min == Double.MAX_VALUE)  0.0 else h.min)
                put("max",               if (h.max == -Double.MAX_VALUE) 0.0 else h.max)
                put("explicitBounds",    bounds)
                put("bucketCounts",      cumulative)
                put("attributes",        tags(tags))
            }))
            put("aggregationTemporality", 2) // DELTA
        })
    }
}
