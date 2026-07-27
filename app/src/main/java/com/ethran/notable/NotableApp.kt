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
        logCrashLoopSignalOnStart()
        pruneCrashFiles()
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
     * Last-resort net for exceptions that escape a coroutine or the main thread. Without it they go
     * straight to the system "app has stopped" dialog with no telemetry. It records a crash-loop
     * marker, persists the stack to a local crash file (the durable record — survives when the
     * network/heap is dead), best-effort logs to ShipBook, then **chains to the previous handler**
     * so normal termination (and ShipBook's own crash reporting) still happens.
     *
     * Installed here in `Application.onCreate` to cover early crashes, and **re-installed once more
     * right after `ShipBook.start()` in MainActivity** ([reinstallCrashHandler]) so our handler is
     * outermost and is guaranteed to write the durable file even if an SDK replaces the handler
     * without chaining. The [alreadyHandled] guard makes the crash work run once even when two of
     * our handlers end up in the chain. See docs/crash-handling-plan.md, Phase 8.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        // Nothing new to wrap (e.g. called twice with no SDK installing a handler in between).
        if (previous === installedHandler) return

        val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
            // If an inner copy of our handler already did the durable work for this exact throwable,
            // just chain — don't write a second file or re-commit the marker.
            if (alreadyHandled(throwable)) {
                previous?.uncaughtException(thread, throwable)
                return@UncaughtExceptionHandler
            }
            try {
                // Durable-first: the cheapest durable signal before any heavy allocation, so an
                // OutOfMemoryError while building the crash file can't cost us the crash-loop marker.
                prefs().edit().putLong(KEY_LAST_CRASH, System.currentTimeMillis()).commit()
                writeCrashFile(thread, throwable)
                // Best-effort: ShipBook may not be started yet (it starts in MainActivity), and its
                // queue is async — the local file above is the record we rely on.
                ShipBook.getLogger("NotableApp")
                    .e("Uncaught exception on thread '${thread.name}'", throwable)
            } catch (t: Throwable) {
                // The handler must never throw — swallow anything here.
                Log.e("NotableApp", "Crash handler itself failed", t)
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
        Thread.setDefaultUncaughtExceptionHandler(handler)
        installedHandler = handler
    }

    /**
     * Re-install our handler after another SDK (ShipBook) has installed its own, so ours wraps it
     * and is guaranteed to run. Idempotent: a no-op if our handler is already the current default.
     * Call once from MainActivity immediately after `ShipBook.start()`.
     */
    fun reinstallCrashHandler() = installCrashHandler()

    /** Records the throwable we last handled so the same crash isn't persisted twice when two of
     *  our handlers sit in the chain (onCreate + post-ShipBook). Returns true if already handled. */
    private fun alreadyHandled(throwable: Throwable): Boolean {
        if (lastHandled === throwable) return true
        lastHandled = throwable
        return false
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

    /**
     * Write one crash file. Kept minimal — no listing/sorting/deleting here (that runs at startup,
     * [pruneCrashFiles]); the death path only creates the new file. The filename embeds the
     * timestamp plus the thread id so two crashes in the same millisecond don't overwrite each other.
     */
    private fun writeCrashFile(thread: Thread, throwable: Throwable) {
        val dir = File(filesDir, CRASH_DIR).apply { mkdirs() }
        File(dir, "crash_${System.currentTimeMillis()}_t${thread.id}.txt").writeText(
            "thread=${thread.name}\n${throwable.stackTraceToString()}"
        )
    }

    /** Cap the crashes/ dir at startup, off the death path. Keeps the newest [MAX_CRASH_FILES] by
     *  the timestamp embedded in the filename (more reliable than File.lastModified, which can be 0). */
    private fun pruneCrashFiles() {
        try {
            val files = File(filesDir, CRASH_DIR).listFiles() ?: return
            files.sortedByDescending { crashFileTimestamp(it.name) }
                .drop(MAX_CRASH_FILES)
                .forEach { it.delete() }
        } catch (t: Throwable) {
            Log.w("NotableApp", "crash-file prune failed", t)
        }
    }

    /** Parse the `<ts>` out of `crash_<ts>_t<id>.txt`; 0 sorts unparsable names as oldest. */
    private fun crashFileTimestamp(name: String): Long =
        name.removePrefix("crash_").substringBefore('_').toLongOrNull() ?: 0L

    private fun prefs() = getSharedPreferences("crash_guard", MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_START = "last_start"
        private const val KEY_LAST_CRASH = "last_crash"
        private const val CRASH_LOOP_MS = 5_000L
        private const val MAX_CRASH_FILES = 20
        private const val CRASH_DIR = "crashes"

        // The handler instance we last installed, so a re-install can tell "already ours" from "an
        // SDK replaced it". @Volatile because it is read/written across threads.
        @Volatile
        private var installedHandler: Thread.UncaughtExceptionHandler? = null

        // The throwable last persisted, to dedupe when two of our handlers are chained. @Volatile:
        // the crashing thread may not be the one that installed the handler.
        @Volatile
        private var lastHandled: Throwable? = null
    }
}
