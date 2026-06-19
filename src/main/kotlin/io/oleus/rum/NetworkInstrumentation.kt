package io.oleus.rum

import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID

class OleusOkHttpInterceptor(private val sdk: OleusRUM) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId  = String.format("%016x", (Math.random() * Long.MAX_VALUE).toLong())
        val req = chain.request().newBuilder()
            .addHeader("traceparent", "00-$traceId-$spanId-01")
            .build()
        val start = System.currentTimeMillis()
        val resp  = chain.proceed(req)
        val durationMs = System.currentTimeMillis() - start
        sdk.trackResource(
            url        = req.url.toString(),
            method     = req.method,
            statusCode = resp.code,
            durationMs = durationMs.toDouble(),
            traceId    = traceId,
            spanId     = spanId,
        )
        return resp
    }
}
