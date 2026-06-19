package io.oleus.rum

import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class EventBatcher(private val config: OleusConfiguration) {
    private val batch = CopyOnWriteArrayList<RUMEvent>()
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val gson = Gson()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    fun start() {
        scheduler.scheduleAtFixedRate(::flush, config.flushIntervalSeconds, config.flushIntervalSeconds, TimeUnit.SECONDS)
    }

    fun enqueue(event: RUMEvent) {
        batch.add(event)
        if (batch.size >= config.batchSize) flush()
    }

    @Synchronized fun flush() {
        if (batch.isEmpty()) return
        val payload = batch.toList()
        batch.clear()
        send(payload)
    }

    private fun send(events: List<RUMEvent>, attempt: Int = 1) {
        val body = gson.toJson(mapOf("events" to events))
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("${config.endpoint}/v1/mobile-rum/ingest")
            .header("X-Oleus-API-Key", config.apiKey)
            .post(body)
            .build()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful && attempt < 3) {
                    Thread.sleep(1000L * attempt)
                    send(events, attempt + 1)
                }
            }
        } catch (e: Exception) {
            if (attempt < 3) { Thread.sleep(1000L * attempt); send(events, attempt + 1) }
        }
    }
}
