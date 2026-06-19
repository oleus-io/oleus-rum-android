package io.oleus.rum

internal class CrashReporter(private val sdk: OleusRUM) {
    fun install() {
        val default_ = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            sdk.trackCrash(
                exceptionClass = throwable.javaClass.name,
                message = throwable.message ?: "",
                stackTrace = throwable.stackTraceToString(),
                isFatal = true,
            )
            sdk.flushSync()
            default_?.uncaughtException(thread, throwable)
        }
    }
}
