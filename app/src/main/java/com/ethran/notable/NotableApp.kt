package com.ethran.notable

import android.app.Application
import android.util.Log
import com.onyx.android.sdk.rx.RxManager
import dagger.hilt.android.HiltAndroidApp
import io.shipbook.shipbooksdk.ShipBook
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File

@HiltAndroidApp
class NotableApp : Application() {

    override fun onCreate() {
        Log.i("NotableApp", "onCreate START")
        super.onCreate()
        installCrashHandler()
        RxManager.Builder.initAppContext(this)
        checkHiddenApiBypass()
        Log.i("NotableApp", "onCreate FINISH")
    }

    private fun checkHiddenApiBypass() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }

    /**
     * Last-resort net for exceptions that escape a coroutine or the main thread. Without it they
     * go straight to the system "app has stopped" dialog with no telemetry. We log to ShipBook,
     * persist the stack to a local crash file (survives when the network/heap is dead), record a
     * crash-loop signal, then **chain to the previous handler** (ShipBook installs its own) so
     * normal termination still happens. See docs/crash-handling-plan.md, Layer 0.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        logCrashLoopSignalOnStart()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                ShipBook.getLogger("NotableApp")
                    .e("Uncaught exception on thread '${thread.name}'", throwable)
                writeCrashFile(thread, throwable)
                prefs().edit().putLong(KEY_LAST_CRASH, System.currentTimeMillis()).commit()
            } catch (t: Throwable) {
                // The handler must never throw — swallow anything here.
                Log.e("NotableApp", "Crash handler itself failed", t)
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    /** On startup, note if the last run crashed very soon after launching — a likely crash loop. */
    private fun logCrashLoopSignalOnStart() {
        try {
            val p = prefs()
            val lastStart = p.getLong(KEY_LAST_START, 0L)
            val lastCrash = p.getLong(KEY_LAST_CRASH, 0L)
            if (lastCrash > lastStart && lastStart > 0L && (lastCrash - lastStart) < CRASH_LOOP_MS) {
                ShipBook.getLogger("NotableApp").w(
                    "Possible crash loop: previous run crashed ${lastCrash - lastStart} ms after launch"
                )
            }
            p.edit().putLong(KEY_LAST_START, System.currentTimeMillis()).apply()
        } catch (t: Throwable) {
            Log.w("NotableApp", "crash-loop check failed", t)
        }
    }

    private fun writeCrashFile(thread: Thread, throwable: Throwable) {
        val dir = File(filesDir, "crashes").apply { mkdirs() }
        // Cap the directory so crash files can't accumulate unbounded.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_CRASH_FILES - 1)
            ?.forEach { it.delete() }
        File(dir, "crash_${System.currentTimeMillis()}.txt").writeText(
            "thread=${thread.name}\n${throwable.stackTraceToString()}"
        )
    }

    private fun prefs() = getSharedPreferences("crash_guard", MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_START = "last_start"
        private const val KEY_LAST_CRASH = "last_crash"
        private const val CRASH_LOOP_MS = 5_000L
        private const val MAX_CRASH_FILES = 20
    }
}
