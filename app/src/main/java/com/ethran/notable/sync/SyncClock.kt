package com.ethran.notable.sync

import java.time.Instant
import java.util.Date

/**
 * The clock every sync-relevant timestamp is stamped from: this device's wall clock, corrected by
 * the last disagreement measured against the sync server (protocol §7.1).
 *
 * The protocol orders everything by client wall-clock instants — §4's `pick` decides each scalar
 * field by `updatedAt`, §6.4 decides delete-versus-edit by comparing a deletion's instant with an
 * edit's. A device running fast therefore does not merely mis-report a time, it *wins* comparisons
 * it should lose, on both devices: a future-dated tombstone destroys edits made until that date
 * passes, and a future-dated envelope pins a rename or a trash state until the stamp comes true.
 * Measuring the skew and only printing it leaves that damage happening.
 *
 * So the measurement is applied where the timestamp is *made*, and nowhere else:
 *
 * - **Stamp time, not apply time.** The merge functions stay pure — a value that arrived from a
 *   peer is never adjusted, clamped or second-guessed. Rewriting instants on the way in would make
 *   two devices compute different results from the same pair of documents, which is exactly the
 *   convergence the shared vector suite exists to protect.
 * - **All of them or none of them.** Correcting deletions but not page clocks would be worse than
 *   correcting nothing: on a device an hour fast, deletions would move back an hour while edits
 *   stayed an hour ahead, and §6.4 would resurrect far more aggressively than the uncorrected bug
 *   destroys. Every stamp that lands in a synced document reads this clock.
 *
 * This is not a hybrid logical clock and does not pretend to be one. Two devices that have both
 * synced against the same server agree to within the measurement's precision; a device that has
 * never reached the server is exactly as wrong as it was before. That is a floor being raised, not
 * the ordering assumption being removed — see the protocol's §7.1, which still calls HLCs the fix.
 *
 * Deliberately a process-wide object rather than an injected dependency: the stamps it has to reach
 * include Room entity defaults (`val updatedAt: Date = Date()`), which take no constructor
 * arguments and cannot be given one. `GlobalAppSettings` is the same shape for the same reason.
 */
object SyncClock {

    /**
     * Signed seconds this device is *ahead* of the server, as last measured — the value subtracted
     * from the system clock to get a stamp. Zero means "no significant disagreement", which is what
     * a healthy clock reports: `CouchDbClient.clockSkewSeconds` clears itself below its threshold
     * rather than reporting noise.
     *
     * `@Volatile` because it is written from whichever thread finished a sync and read from every
     * thread that stamps anything.
     */
    @Volatile
    private var offsetSeconds: Long = 0

    /**
     * The last measured skew, or null when the clocks agree — what the warning is built from.
     * Distinct from [offsetSeconds] only in that it keeps "unmeasured" and "measured as fine"
     * apart for the UI; both correct by zero.
     */
    @Volatile
    var skewSeconds: Long? = null
        private set

    /** This device's corrected clock, in epoch milliseconds. */
    fun nowMs(): Long = System.currentTimeMillis() - offsetSeconds * 1_000

    /** This device's corrected clock as an [Instant]. */
    fun now(): Instant = Instant.ofEpochMilli(nowMs())

    /** This device's corrected clock as a [Date] — the form the Room entities store. */
    fun nowDate(): Date = Date(nowMs())

    /** This device's corrected clock as the ISO-8601 instant the wire format uses. */
    fun nowIso(): String = now().toString()

    /**
     * Records the skew measured against the server, in signed seconds ahead, or null when the last
     * response showed no significant disagreement.
     *
     * Applied to stamps from here on; timestamps already written are left alone. Correcting them
     * retroactively would rewrite history the peer has already merged, and the peer would not make
     * the same correction — the disagreement is a fact about this device's clock, not about the
     * documents.
     */
    fun note(skewSeconds: Long?) {
        this.skewSeconds = skewSeconds
        offsetSeconds = skewSeconds ?: 0
    }

    /** Drops back to an uncorrected clock. For tests, and for a backend being torn down. */
    fun reset() = note(null)

    /**
     * Whether the disagreement is large enough to say so persistently rather than in passing.
     *
     * Well above the 120s at which a skew is *recorded*: that threshold is loose enough to catch a
     * slow link, and a banner that appears for a round-trip hiccup is one that gets dismissed
     * unread. Five minutes is past any latency explanation and into "this device's date is wrong",
     * which is a thing the user can actually go and fix.
     */
    val isWorthWarningAbout: Boolean
        get() = skewSeconds?.let { kotlin.math.abs(it) >= WARNING_THRESHOLD_SECONDS } == true

    const val WARNING_THRESHOLD_SECONDS = 300L
}
