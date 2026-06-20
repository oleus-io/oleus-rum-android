package io.oleus.mobile

import android.app.Activity
import android.app.Application
import android.os.Bundle

internal class ActivityTracker : Application.ActivityLifecycleCallbacks {
    internal var sessionReplay: SessionReplay? = null

    override fun onActivityResumed(activity: Activity) {
        OleusMobile.trackScreen(activity.javaClass.simpleName)
        sessionReplay?.currentActivity = activity
    }
    override fun onActivityPaused(activity: Activity) {
        sessionReplay?.currentActivity = null
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
