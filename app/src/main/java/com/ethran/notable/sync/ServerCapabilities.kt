package com.ethran.notable.sync

import kotlinx.serialization.Serializable
import java.security.MessageDigest

const val SYNC_SERVER_CAPABILITIES_KEY = "SYNC_SERVER_CAPABILITIES"

/**
 * The non-standard ETag behaviours the sync fast paths depend on, as *measured* on one server by
 * [CapabilityProbeService]. Never inferred from a banner, URL, or version string — a stored record
 * only ever reflects mutations this installation actually observed on this server.
 *
 * Two independent flags are consumed, per `docs/plans/bulk-change-detection-plan.md`:
 *  - [collectionEtagPropagates] gates bulk notebook change detection (the one silent-staleness risk).
 *  - [movePreservesEtag] gates skipping the manifest-ETag backfill after a MOVE publish.
 *
 * The record is keyed by [serverKey] so a record left over from a different server is ignored rather
 * than trusted; a reader must confirm the key matches the server it is about to sync.
 */
@Serializable
data class ServerCapabilities(
    /** Hash of serverUrl + username. A record whose key doesn't match the current server is stale. */
    val serverKey: String,
    /** Epoch millis of the probe. Diagnostic only ("last checked"); never triggers a re-probe. */
    val probedAt: Long,
    /**
     * All of the probe's overwrite/MOVE/deep-create/deep-overwrite mutations moved the observed
     * ancestor collection's ETag. Anything less is false: a server that propagates some changes but
     * not others is more dangerous than one that propagates none.
     */
    val collectionEtagPropagates: Boolean,
    /** The MOVE publish returned 2xx and the destination kept the tmp file's ETag. */
    val movePreservesEtag: Boolean,
    /** The server returned an ETag on PUT. Diagnostics for the connection report; gates nothing. */
    val issuesEtagOnPut: Boolean,
    /** That PUT ETag was strong (not `W/`). Diagnostics for the connection report; gates nothing. */
    val issuesStrongEtags: Boolean,
) {
    companion object {
        /** Stable per-server identity: a change to server URL or username yields a different key. */
        fun serverKey(serverUrl: String, username: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$serverUrl\n$username".toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
