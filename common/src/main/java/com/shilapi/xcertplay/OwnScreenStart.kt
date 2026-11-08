package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.util.Log

/**
 * Opens one of DiPlay's own screens from [Activity.startActivity] without letting a refusal close DiPlay.
 *
 * Inside BYD's DiYou launcher card (DiLink 5, #463) Android refused such a start with IllegalArgumentException
 * "There is no running activity in rootTask" for the CarPlay screen's own task (taskAffinity `.carplay`, since
 * 0.2.12), and the uncaught exception closed DiPlay every time it opened in the card. The start is retried once
 * from the application context in a task of its own; a second refusal is logged and DiPlay stays open.
 */
internal object OwnScreenStart {
    private const val TAG = "DiPlay"

    fun homeIntent(activity: Activity, page: String = "home") =
        Intent(activity, DiPlayActivity::class.java).putExtra("page", page)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Also removes empty recent cards and hosts which never reached session ownership. */
    fun removeCarPlayTasks(activity: Activity) {
        val tasks = activity.getSystemService(ActivityManager::class.java)?.appTasks ?: return
        for (task in tasks) {
            runCatching {
                val info = task.taskInfo ?: return@runCatching
                val root = info.baseIntent.component ?: return@runCatching
                if (root.packageName != activity.packageName ||
                    root.className != CarPlayHostActivity::class.java.name) return@runCatching
                // Older builds could put the management page inside the projection task.
                // Move it to its own task before removing that entire old stack.
                @Suppress("DEPRECATION")
                if (info.id == activity.taskId && !start(activity, homeIntent(activity))) return@runCatching
                task.finishAndRemoveTask()
            }.onFailure { Log.w(TAG, "Could not remove stopped CarPlay task", it) }
        }
    }

    fun start(activity: Activity, intent: Intent, log: (String) -> Unit = { Log.w(TAG, it) }): Boolean {
        val screen = intent.component?.className?.substringAfterLast('.') ?: "screen"
        try {
            activity.startActivity(intent)
            return true
        } catch (refused: IllegalArgumentException) {
            log("Opening $screen refused: ${refused.message?.lineSequence()?.firstOrNull()?.take(MAX_MESSAGE)}; " +
                "retrying in a new task")
        }
        return try {
            activity.applicationContext.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (again: RuntimeException) {
            log("Opening $screen in a new task failed: ${again.javaClass.simpleName}")
            false
        }
    }

    private const val MAX_MESSAGE = 120
}
