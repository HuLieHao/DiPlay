package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import com.shilapi.xcertplay.host.R

/** Task labels describe the page, while the launcher's formal/debug application label stays intact. */
internal object AppTaskIdentity {
    fun apply(activity: Activity, carPlay: Boolean) {
        val app = activity.applicationInfo.loadLabel(activity.packageManager).toString()
        @Suppress("DEPRECATION")
        activity.setTaskDescription(ActivityManager.TaskDescription(
            "$app · ${activity.getString(if (carPlay) R.string.task_carplay else R.string.task_connection_management)}"))
    }
}
