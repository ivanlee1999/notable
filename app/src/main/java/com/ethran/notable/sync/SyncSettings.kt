package com.ethran.notable.sync

import kotlinx.serialization.Serializable

const val SYNC_SETTINGS_KEY = "SYNC_SETTINGS"

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
)
