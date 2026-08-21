package com.ethran.notable.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ethran.notable.di.ApplicationScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope

/**
 * Reaches the application-lifetime [CoroutineScope] from a composable, for writes that must land
 * whether or not the composable that started them is still on screen.
 *
 * `rememberCoroutineScope()` is the wrong home for those, and the way it goes wrong is quiet.
 * Its job is cancelled the moment the composable leaves the composition, so the very common
 *
 * ```
 * onConfirm = {
 *     scope.launch { repository.doTheThing() }   // dispatched, has not run yet
 *     onClose()                                  // unmounts this composable -> scope cancelled
 * }
 * ```
 *
 * is a race between a database write and the next frame. When the write wins the action happens;
 * when the frame wins the coroutine is cancelled at its first suspension point and the action
 * silently does not happen, with no error and nothing in the log. That is what made deleting a
 * notebook work only some of the time: the same tap, the same code, decided by a few milliseconds.
 *
 * Two rules follow, and they are the reason this exists rather than the "call `onClose` from
 * inside the coroutine" trick used elsewhere in the app:
 *
 *  - The work outlives the dialog, so the dialog is free to close immediately — which is what the
 *    user expects from a confirmation — instead of lingering for the length of a disk write.
 *  - It outlives *any* dismissal, including a back press or a tap outside the dialog that no
 *    `onConfirm` gets to sequence.
 *
 * This is for writes that are already durable and transactional on their own (the repositories
 * queue their outbox entries in the same transaction as the row). It is not a general escape from
 * structured concurrency: anything that needs to update UI state on completion still belongs in a
 * scope that can be cancelled with that UI.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppScopeEntryPoint {
    @ApplicationScope
    fun applicationScope(): CoroutineScope
}

@Composable
fun rememberAppScope(): CoroutineScope {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        EntryPointAccessors
            .fromApplication(context, AppScopeEntryPoint::class.java)
            .applicationScope()
    }
}
