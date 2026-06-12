package io.oleus.mobile

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor: request breadcrumbs + slow/failed-request events,
 * tagged with the Apollo GraphQL operation name when present (Apollo Kotlin
 * sends X-APOLLO-OPERATION-NAME). Requires the host app to ship OkHttp.
 *
 *     OkHttpClient.Builder().addInterceptor(OleusOkHttpInterceptor()).build()
 */
public class OleusOkHttpInterceptor(
    private val slowThresholdMs: Long = 3_000,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val operation = request.header("X-APOLLO-OPERATION-NAME")
        val label = operation ?: "${request.method} ${request.url.encodedPath}"
        val startMs = System.currentTimeMillis()

        try {
            val response = chain.proceed(request)
            val durationMs = System.currentTimeMillis() - startMs

            OleusMobile.addBreadcrumb(
                message = label,
                category = if (operation != null) "graphql" else "http",
                attributes = mapOf("status" to response.code, "duration_ms" to durationMs),
            )

            if (response.code >= 500 || durationMs > slowThresholdMs) {
                val attrs = OleusMobile.baseAttributes().toMutableMap()
                attrs["error_type"] = if (response.code >= 500) "HttpError" else "SlowRequest"
                attrs["http_status"] = response.code.toString()
                attrs["duration_ms"] = durationMs.toString()
                operation?.let { attrs["graphql_operation"] = it }
                attrs["url_path"] = request.url.encodedPath
                OleusMobile.ship(
                    startMs,
                    if (response.code >= 500) "ERROR" else "WARN",
                    "$label -> ${response.code} in ${durationMs}ms",
                    attrs,
                )
            }
            return response
        } catch (e: Exception) {
            OleusMobile.addBreadcrumb(
                message = "$label failed: ${e.javaClass.simpleName}",
                category = if (operation != null) "graphql" else "http",
            )
            throw e
        }
    }
}
