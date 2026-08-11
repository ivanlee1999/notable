package com.ethran.notable.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The sync engine's activity log: an in-memory ring buffer the settings screen renders live, backed
 * by a file so the record survives the process.
 *
 * The file half is what makes background sync diagnosable at all. Periodic sync runs in a WorkManager
 * worker that can start and finish while notable is closed; by the time the user opens Settings to
 * ask "why didn't it sync?", the process that made the decision is long gone and an in-memory-only
 * buffer is empty. Persisting means the answer is still there.
 *
 * [install] wires the file up; until it is called (unit tests, early startup) every entry still lands
 * in memory and logcat, and the file writes are simply skipped. Logging must never be the thing that
 * breaks a sync, so every file operation is best-effort and swallows its own failures.
 */
object SyncLogger {
    /**
     * A full sync of a large library emits several lines per notebook, so the old 50-entry buffer
     * dropped the beginning of its own run — including the preflight lines that say why the run went
     * the way it did. Sized to hold a whole ordinary run plus the previous one for comparison.
     */
    private const val MAX_LOGS = 400

    /** Sub-directory of `filesDir` holding the persisted log, alongside `crashes/`. */
    private const val LOG_DIR = "sync"
    private const val LOG_FILE = "sync-log.txt"

    /**
     * Rotate at this size, keeping one previous generation. Sized so a couple of full verbose runs
     * fit in the current file, while the two generations together stay small enough to read back and
     * put on the clipboard in one go (that is what "Copy Log" does) — roughly a few thousand lines.
     */
    private const val MAX_FILE_BYTES = 256 * 1024L

    /** How many persisted lines are restored into the live buffer on startup. */
    private const val RESTORED_LINES = 200

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // Per-thread, because SimpleDateFormat is not thread-safe and these are touched from everywhere
    // sync runs: IO coroutines logging entries, the writer thread parsing the file back, and the UI
    // thread rendering timestamps. A shared instance corrupts its output under exactly that traffic.
    private val clockFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }
    private val fileFormat =
        ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

    /**
     * Whether [d] entries are recorded. Off by default: the per-page and per-request detail it emits
     * is what you want while diagnosing a specific problem, and noise that would push the important
     * lines out of the buffer the rest of the time. Mirrors [SyncSettings.verboseSyncLog].
     */
    @Volatile
    var verbose: Boolean = false

    /**
     * Serializes file writes off the caller's thread. Single-threaded, so entries reach the file in
     * the order they were logged; null until [install]. Sync logging happens on IO coroutines and
     * (via the settings screen) occasionally the main thread — neither should wait on a disk write.
     */
    @Volatile
    private var writer: java.util.concurrent.ExecutorService? = null

    @Volatile
    private var logFile: File? = null

    /** Guards the buffer against the restore (writer thread) racing live entries (IO threads). */
    private val bufferLock = Any()

    /**
     * Point the logger at [context]'s files directory and restore the tail of the previous session's
     * log into the live buffer, so the settings screen opens showing what background sync did rather
     * than an empty panel. Safe to call more than once; the second call is a no-op.
     *
     * Called from `Application.onCreate`, so it touches no disk on the calling thread: the directory
     * creation and the read-back both run on the writer thread. Only the [logFile]/[writer] handles
     * are set here, which is what makes persistence live for any entry logged from this point on.
     */
    fun install(context: Context) {
        if (logFile != null) return
        try {
            val file = File(File(context.filesDir, LOG_DIR), LOG_FILE)
            val executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "sync-log-writer").apply { isDaemon = true }
            }
            logFile = file
            writer = executor
            executor.execute {
                runCatching {
                    file.parentFile?.mkdirs()
                    restoreFrom(file)
                }.onFailure { Log.w(TAG, "Sync log restore failed", it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Sync log persistence unavailable", t)
            logFile = null
            writer = null
        }
    }

    /**
     * Mark the start of a sync run in the log. Runs are the unit a user reasons about ("it skipped
     * again at 14:30"), and without a separator the lines of three runs read as one confusing
     * sequence — especially across a restart, where restored history sits directly above live lines.
     */
    fun beginRun(label: String) {
        addLog(LogLevel.INFO, TAG, "──────── $label ────────")
    }

    /** Detail-level entry: recorded only while [verbose] is on. */
    fun d(tag: String, message: String) {
        if (!verbose) return
        addLog(LogLevel.DEBUG, tag, message)
        io.shipbook.shipbooksdk.Log.d(tag, message)
    }

    /**
     * Add an info log entry.
     */
    fun i(tag: String, message: String) {
        addLog(LogLevel.INFO, tag, message)
        io.shipbook.shipbooksdk.Log.i(tag, message)
    }

    /**
     * Add a warning log entry.
     */
    fun w(tag: String, message: String) {
        addLog(LogLevel.WARNING, tag, message)
        io.shipbook.shipbooksdk.Log.w(tag, message)
    }

    /**
     * Add an error log entry.
     */
    fun e(tag: String, message: String) {
        addLog(LogLevel.ERROR, tag, message)
        io.shipbook.shipbooksdk.Log.e(tag, message)
    }

    /**
     * Clear all log entries, on screen and on disk. The persisted copy has to go too — otherwise
     * "Clear Log" empties the panel and the next launch restores everything it just cleared.
     */
    fun clear() {
        synchronized(bufferLock) { _logs.value = emptyList() }
        val file = logFile ?: return
        writer?.execute {
            runCatching {
                file.delete()
                previousGeneration(file).delete()
            }
        }
    }

    /**
     * The whole retained log as text — the persisted history plus this session's entries — for the
     * "Copy Log" button. A bug report wants everything on disk, not just the entries this process
     * happens to be holding.
     *
     * Reads both generations synchronously, so call it off the main thread: at the rotation ceiling
     * that is half a megabyte of file, enough to stall a screen on slow e-ink storage.
     */
    fun dump(): String {
        val persisted = logFile?.let { file ->
            runCatching {
                val previous = previousGeneration(file)
                buildString {
                    if (previous.exists()) append(previous.readText())
                    if (file.exists()) append(file.readText())
                }
            }.getOrNull()
        }
        // The persisted text already contains this session's lines (they are written as they are
        // logged), so fall back to the buffer only when there is no file to read.
        return persisted?.takeIf { it.isNotBlank() }
            ?: _logs.value.joinToString("\n") { it.toFileLine() }
    }

    private fun addLog(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            atMillis = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )

        synchronized(bufferLock) {
            val currentLogs = _logs.value.toMutableList()
            currentLogs.add(entry)

            // Keep only last MAX_LOGS entries
            while (currentLogs.size > MAX_LOGS) {
                currentLogs.removeAt(0)
            }

            _logs.value = currentLogs
        }
        persist(entry)
    }

    private fun persist(entry: LogEntry) {
        val file = logFile ?: return
        val line = entry.toFileLine()
        writer?.execute {
            runCatching {
                if (file.length() > MAX_FILE_BYTES) rotate(file)
                file.appendText(line + "\n")
            }
        }
    }

    /**
     * Keep one previous generation and start a fresh file. Two generations rather than truncation so
     * a rotation in the middle of a run can't throw away the run's own opening lines.
     */
    private fun rotate(file: File) {
        val previous = previousGeneration(file)
        previous.delete()
        if (!file.renameTo(previous)) file.delete()
    }

    private fun previousGeneration(file: File) = File(file.parentFile, "$LOG_FILE.1")

    /**
     * Seed the buffer with the tail of the persisted log. Parse failures are not worth failing over:
     * an unparsable line is surfaced as-is at INFO so nothing is silently dropped from the record.
     *
     * Reads across the rotation boundary. A run that rotated mid-way leaves the current file holding
     * only its tail, and reading that file alone would restore a handful of lines while the ones
     * that explain the run sat unread in the previous generation.
     */
    private fun restoreFrom(file: File) {
        val recent = tailOf(file, RESTORED_LINES)
        // Only reach back into the previous generation when the current file cannot fill the quota,
        // which is exactly the just-rotated case.
        val older = if (recent.size < RESTORED_LINES) {
            tailOf(previousGeneration(file), RESTORED_LINES - recent.size)
        } else {
            emptyList()
        }
        val restored = older + recent
        if (restored.isEmpty()) return
        // Restored history goes *above* whatever this session already logged while the read was in
        // flight, and the cap still applies — history must never crowd out the live run.
        synchronized(bufferLock) {
            _logs.value = (restored + _logs.value).takeLast(MAX_LOGS)
        }
    }

    /** The last [count] lines of [file] as entries, or empty if it is absent or unreadable. */
    private fun tailOf(file: File, count: Int): List<LogEntry> {
        if (count <= 0 || !file.exists()) return emptyList()
        return runCatching {
            file.readLines().takeLast(count).map { parseFileLine(it) }
        }.getOrNull().orEmpty()
    }

    /** `2026-08-11 14:30:02 I/SyncWorker: message` — one line, greppable, stable to parse back. */
    private fun LogEntry.toFileLine(): String =
        "${fileFormat.get().format(Date(atMillis))} ${level.marker}/$tag: $message"

    /**
     * Every entry produced here is [LogEntry.isRestored], including one whose own text could not be
     * parsed — where it came from is known for certain by the caller, so it must not be inferred
     * from a timestamp the line may not have carried.
     */
    private fun parseFileLine(line: String): LogEntry {
        val match = FILE_LINE.matchEntire(line) ?: return LogEntry(
            atMillis = 0L, level = LogLevel.INFO, tag = TAG, message = line, isRestored = true
        )
        val (stamp, marker, tag, message) = match.destructured
        return LogEntry(
            atMillis = runCatching { fileFormat.get().parse(stamp)?.time }.getOrNull() ?: 0L,
            level = LogLevel.entries.firstOrNull { it.marker == marker } ?: LogLevel.INFO,
            tag = tag,
            message = message,
            isRestored = true,
        )
    }

    private val FILE_LINE = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) ([DIWE])/([^:]*): (.*)$""")

    /** Exposes the file-line parser to tests; restoring from disk is otherwise unreachable there. */
    @androidx.annotation.VisibleForTesting
    internal fun parseFileLineForTest(line: String): LogEntry = parseFileLine(line)

    private const val TAG = "SyncLogger"

    data class LogEntry(
        /** When the entry was logged. 0 for a restored line whose timestamp could not be parsed. */
        val atMillis: Long,
        val level: LogLevel,
        val tag: String,
        val message: String,
        /**
         * Whether this entry was read back from disk rather than logged by this process — set by
         * whoever creates it, not inferred, so a restored line with an unreadable timestamp is still
         * correctly dimmed as history.
         */
        val isRestored: Boolean = false,
    ) {
        /** Clock time for the on-screen log. */
        val timestamp: String
            get() = if (atMillis == 0L) "--:--:--" else clockFormat.get().format(Date(atMillis))
    }

    enum class LogLevel(val marker: String) {
        DEBUG("D"), INFO("I"), WARNING("W"), ERROR("E")
    }
}
