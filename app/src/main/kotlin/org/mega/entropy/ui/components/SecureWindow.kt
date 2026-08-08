package org.mega.entropy.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import java.util.WeakHashMap

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

internal object SecureWindowFlag {
    private val countsByActivity = WeakHashMap<Activity, Int>()

    fun acquire(activity: Activity?) {
        if (activity == null) return

        val currentCount = countsByActivity[activity] ?: 0
        if (currentCount == 0) {
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        countsByActivity[activity] = currentCount + 1
    }

    fun release(activity: Activity?) {
        if (activity == null) return

        val currentCount = countsByActivity[activity] ?: return
        if (currentCount <= 1) {
            countsByActivity.remove(activity)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            countsByActivity[activity] = currentCount - 1
        }
    }
}
