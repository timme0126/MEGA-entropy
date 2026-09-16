package org.mega.entropy

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NEEDS-VALIDATION lead m4-device. Two phases driven across process death
 * (harness passes -e phase write|read -e runid X):
 *  WRITE: run while FOCUSED (Android 10+ drops background clip writes) ->
 *    write sensitive marker clip exactly like MegaCopyIconButton.
 *  (harness: force-stop both processes, wait >60s)
 *  READ: fresh process, focused -> is the marker still there?
 */
@RunWith(AndroidJUnit4::class)
class ClipboardRetentionInstrumentedTest {

    private val TAG = "ClipAudit"

    @Test
    fun retentionAcrossProcessDeath() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("phase", "write")
        val runId = args.getString("runid", "?")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // Launch the APP's own main activity (same process as the running
        // instrumentation - targetPackage is the app). Faithful scenario:
        // write the clip while MEGA itself is focused, as a user tapping copy.
        val intent = android.content.Intent().apply {
            setClassName("org.mega.entropy", "org.mega.entropy.MainActivity")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        Log.i(TAG, "RUN=$runId phase=$phase pid=${android.os.Process.myPid()}")

        // Focus via instrumentation startActivitySync (avoids androidx.test.core
        // ActivityScenario, which is not on this classpath).
        InstrumentationRegistry.getInstrumentation().startActivitySync(intent)
        Thread.sleep(1500) // let it resume and hold focus

        val read: String? = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(ctx)?.toString()

        if (phase == "write") {
            val marker = "MEGA-AUDIT-$runId"
            val clip = ClipData.newPlainText("mega-audit", marker)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            cm.setPrimaryClip(clip)
            Thread.sleep(500)
            val back = cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(ctx)?.toString()
            Log.i(TAG, "RUN=$runId WROTE marker=$marker focused_readback=${back ?: "EMPTY"}")
        } else {
            val has = cm.hasPrimaryClip()
            Log.i(
                TAG,
                "RUN=$runId READBACK hasClip=$has value=${read ?: "EMPTY"} " +
                    "retained_after_process_death=${read != null && read.startsWith("MEGA-AUDIT")} " +
                    "device=Pixel9a-A37 os=GrapheneOS",
            )
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
