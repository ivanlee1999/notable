package com.ethran.notable.sync.couch

/**
 * Producing and pruning the tombstones the merge relies on. Protocol §6.6; bopa's
 * `CouchTombstones.swift` is the twin of this file.
 *
 * Neither app records erasures today: notable deletes the stroke row and bopa's exporter writes
 * out whatever ink survived. Absence is enough when one device owns the file, but a merge cannot
 * tell "s2 was erased here" from "s2 has not arrived here yet" — without tombstones the other
 * device's copy simply comes back. So each save has to say what *stopped* existing, which is a set
 * difference against what the page held at the previous save.
 */
object CouchTombstones {

    /** Thirty days: far beyond any plausible gap between two devices that sync in seconds. */
    const val DEFAULT_MAX_AGE_MS: Long = 30L * 24 * 60 * 60 * 1000

    /**
     * Tombstones to record for a save, given the ids present before and after.
     *
     * Only genuinely departed ids are recorded, and an id already tombstoned keeps its original
     * `deletedAt` — the earliest observation of a deletion is the true one, and re-stamping it on
     * every save would let a much later timestamp win a delete-vs-edit it should lose.
     */
    fun derive(
        previousIds: Set<String>,
        currentIds: Set<String>,
        existing: List<CouchTombstone>,
        deletedAt: String,
    ): List<CouchTombstone> {
        val alreadyRecorded = existing.map { it.id }.toSet()
        val departed = previousIds - currentIds - alreadyRecorded
        val added = departed.map { CouchTombstone(id = it, deletedAt = deletedAt) }
        return (existing + added).sortedBy { it.id }
    }

    /**
     * Drops tombstones older than [maxAgeMs].
     *
     * Safe only once every device has synced past them — a tombstone dropped while a peer still
     * holds the stroke lets that stroke come back. A tombstone whose timestamp will not parse is
     * kept: it cannot be *shown* to be old enough to drop.
     */
    fun prune(
        tombstones: List<CouchTombstone>,
        nowMs: Long,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): List<CouchTombstone> {
        val cutoff = nowMs - maxAgeMs
        return tombstones.filter { tombstone ->
            val deletedAt = CouchMerge.millis(tombstone.deletedAt)
            deletedAt == Long.MIN_VALUE || deletedAt >= cutoff
        }
    }

    /**
     * Convenience for a page save: applies [derive] against the page's own previous strokes.
     * Returns a copy — Swift takes the page `inout`, which Kotlin data classes express as a copy.
     */
    fun recordErasures(
        page: CouchPage,
        previousStrokeIds: Set<String>,
        deletedAt: String,
    ): CouchPage = page.copy(
        deletedStrokes = derive(
            previousIds = previousStrokeIds,
            currentIds = page.strokes.map { it.id }.toSet(),
            existing = page.deletedStrokes,
            deletedAt = deletedAt,
        )
    )
}
