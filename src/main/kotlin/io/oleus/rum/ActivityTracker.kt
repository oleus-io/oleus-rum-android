package io.oleus.rum

import android.app.Activity
import android.app.Application
import android.os.Bundle

internal class ActivityTracker(private val sdk: OleusRUM) : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity)  { sdk.trackViewStart(activity.javaClass.simpleName) }
    override fun onActivityPaused(activity: Activity)   { sdk.trackViewEnd(activity.javaClass.simpleName) }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
