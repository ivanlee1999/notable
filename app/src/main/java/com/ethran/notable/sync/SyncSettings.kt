package com.ethran.notable.sync

import kotlinx.serialization.Serializable

const val SYNC_SETTINGS_KEY = "SYNC_SETTINGS"

/** Where the CouchDB engine's checkpoint/outbox is persisted. See [com.ethran.notable.sync.couch.CouchSyncState]. */
const val COUCH_SYNC_STATE_KEY = "COUCH_SYNC_STATE"

/**
 * Which sync transport notable uses. Exactly one is live at a time, and [OFF] means none is — this
 * is the single switch every sync path consults, so selecting one backend genuinely silences the
 * other rather than merely hiding its settings.
 *
 * WebDAV stays the default while CouchDB is proven out; the intent is for CouchDB to become the
 * only option. bopa's `SyncBackend` is the twin of this enum.
 */
@Serializable
enum class SyncBackend { OFF, WEBDAV, COUCHDB }

/**
 * "boox" by default, to pair with bopa's "ipad". Distinctness is what matters, not the spelling —
 * two devices sharing an id would break the merge's tiebreak when two edits share a millisecond.
 */
const val DEFAULT_DEVICE_ID = "boox"

@Serializable
data class SyncSettings(
    val syncEnabled: Boolean = false,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "", // KvProxy handles the encryption
    val autoSync: Boolean = true,
    val syncInterval: Int = 15, // minutes
    val lastSyncTime: Long? = null,
    val syncOnNoteClose: Boolean = true,
    /** Run a full sync when the app starts. */
    val syncOnAppStart: Boolean = true,
    /** When opening a notebook, check whether the server has a newer version and hint the user. */
    val checkOnOpen: Boolean = true,
    val wifiOnly: Boolean = false,
    val uploadOnly: Boolean = false,
    /** Only pull from the server; never push local changes/deletions (mirror of [uploadOnly]). */
    val downloadOnly: Boolean = false,
    /** How to resolve a genuine same-page conflict; independent edits always merge. */
    val conflictStrategy: SyncConflictStrategy = SyncConflictStrategy.ASK,
    /**
     * User off-switch for bulk change detection. Default on; the optimization runs only when this is
     * true *and* the server was measured to support it ([ServerCapabilities.collectionEtagPropagates]).
     */
    val fastSyncEnabled: Boolean = true,

    // ---- CouchDB backend ----
    // Kept alongside the WebDAV fields rather than in their own record so one KV row still holds
    // the whole sync configuration, and so switching backends cannot half-apply.

    /** Selected transport. WEBDAV until the user switches, so an upgrade changes nothing. */
    val backend: SyncBackend = SyncBackend.WEBDAV,
    val couchUrl: String = "",
    val couchDatabase: String = "notes",
    val couchUsername: String = "",
    /** Encrypted at rest by KvProxy, exactly like [password]. */
    val couchPassword: String = "",
    /**
     * Identifies this device in every document it writes. It is the merge tiebreak, so it must
     * differ from bopa's "ipad" — see [DEFAULT_DEVICE_ID].
     */
    val deviceId: String = DEFAULT_DEVICE_ID,
) {
    /** Enough to build a client: an http(s) URL and a database name. */
    val couchConfigured: Boolean
        get() = (couchUrl.startsWith("http://") || couchUrl.startsWith("https://")) &&
                couchDatabase.isNotBlank()

    /** True when CouchDB is both selected and usable. */
    val couchActive: Boolean get() = backend == SyncBackend.COUCHDB && couchConfigured

    /**
     * True when WebDAV is both selected and switched on. Every WebDAV entry point gates on this
     * rather than on [syncEnabled] alone: a saved server and `syncEnabled = true` survive a switch
     * to CouchDB, and without the backend check the two engines would write the same notebooks
     * through different transports — sync-on-note-close and check-on-open call the orchestrator
     * directly, so they never see the periodic worker's routing.
     */
    val webdavActive: Boolean get() = backend == SyncBackend.WEBDAV && syncEnabled

    /** The one misconfiguration that silently breaks merging. */
    val deviceIdWarning: String?
        get() = if (deviceId.isBlank()) {
            "Give this device a name. It identifies your edits when two devices change the " +
                    "same page at once."
        } else {
            null
        }
}
