package io.oleus.rum

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView

internal class SessionReplay(private val sdk: OleusRUM) {
    private val captureRunnable = object : Runnable {
        override fun run() {
            currentActivity?.window?.decorView?.let { root ->
                val wireframe = captureView(root)
                sdk.trackReplayFrame(wireframe)
            }
            captureHandler?.postDelayed(this, 2000L)
        }
    }
    private var captureHandler: android.os.Handler? = null
    var currentActivity: Activity? = null

    fun start() {
        captureHandler = android.os.Handler(android.os.Looper.getMainLooper())
        captureHandler?.post(captureRunnable)
    }

    fun stop() { captureHandler?.removeCallbacks(captureRunnable); captureHandler = null }

    private fun captureView(view: View): Map<String, Any> {
        val rect = Rect(); view.getGlobalVisibleRect(rect)
        val node = mutableMapOf<String, Any>(
            "type"    to view.javaClass.simpleName,
            "frame"   to mapOf("x" to rect.left, "y" to rect.top, "w" to rect.width(), "h" to rect.height()),
            "visible" to (view.visibility == View.VISIBLE),
            "alpha"   to view.alpha,
        )
        when {
            view is EditText  -> node["text"] = "[masked]"
            view is TextView  -> {
                val hint = view.contentDescription?.toString()?.lowercase() ?: ""
                node["text"] = if (hint.contains("password") || hint.contains("card")) "[masked]"
                               else view.text?.toString()?.take(200) ?: ""
            }
        }
        if (view is ViewGroup) {
            node["children"] = (0 until view.childCount).map { captureView(view.getChildAt(it)) }
        }
        return node
    }
}
