package io.oleus.rum

data class RUMEvent(
    val type: String,
    val session_id: String,
    val view_id: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val platform: String = "android",
    val app_version: String = "",
    val os_version: String = android.os.Build.VERSION.RELEASE,
    val device_model: String = android.os.Build.MODEL,
    val attributes: Map<String, Any> = emptyMap(),
)
