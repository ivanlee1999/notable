package com.ethran.notable.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ethran.notable.sync.couch.CouchSyncController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Reaches the app-scoped [CouchSyncController] from a composable.
 *
 * The dialogs that delete a notebook or a folder are plain composables several layers below any
 * view model, and the controller is a singleton with no screen state of its own. Threading it
 * through every intermediate signature would say nothing true about those screens, so it is
 * resolved where it is used instead.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CouchSyncEntryPoint {
    fun couchSyncController(): CouchSyncController
}

@Composable
fun rememberCouchSyncController(): CouchSyncController {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        EntryPointAccessors
            .fromApplication(context, CouchSyncEntryPoint::class.java)
            .couchSyncController()
    }
}
