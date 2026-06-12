package io.oleus.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Disk-backed, batched OTLP/HTTP log shipper.
 *
 * Records are persisted to [queueDir] before any network attempt and deleted
 * only on a 2xx response, so events survive crashes, offline periods, and
 * process death. Batches flush every 10s or when [flush] is called.
 */
internal class OtlpShipper(
    private val endpoint: String,
    private val service: String,
    private val apiKey: String?,
    private val environment: String,
    private val release: String,
    private val queueDir: File,
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "oleus-shipper").apply { isDaemon = true }
    }
    private val maxQueuedFiles = 200

    init {
        queueDir.mkdirs()
        executor.scheduleWithFixedDelay({ flushNow() }, 10, 10, TimeUnit.SECONDS)
    }

    fun enqueue(timestampMs: Long, severity: String, body: String, attributes: Map<String, String>) {
        executor.execute {
            persist(timestampMs, severity, body, attributes)
            trim()
        }
    }

    /** Persist synchronously — for use inside the crash handler, where the
     *  process is about to die and the executor must not be trusted to run. */
    fun persistBlocking(timestampMs: Long, severity: String, body: String, attributes: Map<String, String>) {
        try {
            persist(timestampMs, severity, body, attributes)
        } catch (_: Exception) {
            // crashing — nothing more we can do
        }
    }

    fun flush() {
        executor.execute { flushNow() }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private fun persist(timestampMs: Long, severity: String, body: String, attributes: Map<String, String>) {
        val entry = JSONObject().apply {
            put("timeMs", timestampMs)
            put("severity", severity)
            put("body", body)
            put("attributes", JSONObject(attributes as Map<*, *>))
        }
        File(queueDir, "$timestampMs-${UUID.randomUUID()}.json").writeText(entry.toString())
    }

    private fun trim() {
        val files = queueDir.listFiles()?.sortedBy { it.name } ?: return
        if (files.size > maxQueuedFiles) {
            files.take(files.size - maxQueuedFiles).forEach { it.delete() }
        }
    }

    private fun flushNow() {
        val files = (queueDir.listFiles()?.sortedBy { it.name } ?: return).take(50)
        if (files.isEmpty()) return

        val records = JSONArray()
        val parsed = mutableListOf<File>()
        for (file in files) {
            try {
                val entry = JSONObject(file.readText())
                val attrs = entry.getJSONObject("attributes")
                val attrsList = JSONArray()
                attrs.keys().forEach { key ->
                    attrsList.put(JSONObject().apply {
                        put("key", key)
                        put("value", JSONObject().put("stringValue", attrs.getString(key)))
                    })
                }
                records.put(JSONObject().apply {
                    put("timeUnixNano", (entry.getLong("timeMs") * 1_000_000).toString())
                    put("severityText", entry.getString("severity"))
                    put("body", JSONObject().put("stringValue", entry.getString("body")))
                    put("attributes", attrsList)
                })
                parsed.add(file)
            } catch (_: Exception) {
                file.delete() // corrupt entry
            }
        }
        if (records.length() == 0) return

        val payload = JSONObject().apply {
            put("resourceLogs", JSONArray().put(JSONObject().apply {
                put("resource", JSONObject().put("attributes", JSONArray().apply {
                    put(resourceAttr("service.name", service))
                    put(resourceAttr("service.version", release))
                    put(resourceAttr("deployment.environment", environment))
                }))
                put("scopeLogs", JSONArray().put(JSONObject().put("logRecords", records)))
            }))
        }

        try {
            val connection = URL("$endpoint/v1/logs").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            apiKey?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (connection.responseCode in 200..299) {
                parsed.forEach { it.delete() }
            }
            connection.disconnect()
        } catch (_: Exception) {
            // offline or collector unreachable — files stay queued
        }
    }

    private fun resourceAttr(key: String, value: String) = JSONObject().apply {
        put("key", key)
        put("value", JSONObject().put("stringValue", value))
    }
}
