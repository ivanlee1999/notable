package com.ethran.notable.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.testing.ComposeUiSupportRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where a dialog's actions have to run, so that closing the dialog does not throw the action away.
 *
 * This is the shape of the bug that made deleting a notebook work only some of the time. The
 * notebook dialog confirmed a deletion like this:
 *
 * ```
 * scope.launch { trashRepository.trashNotebook(bookId) }   // rememberCoroutineScope()
 * onClose()                                                // unmounts the dialog
 * ```
 *
 * and `onClose()` cancels `scope`. Whether the notebook was actually trashed came down to whether
 * Room finished the write before the next frame disposed the composable — nothing in the code said
 * which, so the same tap deleted a notebook one time and did nothing at all the next, silently
 * either way.
 *
 * Both halves are asserted here, because the fix is only meaningful against the trap: the
 * composition's own scope really is cancelled at that moment, and [rememberAppScope] really does
 * survive it.
 */
@RunWith(AndroidJUnit4::class)
class DialogActionScopeTests {

    private val composeRule = createComposeRule()

    @get:Rule
    val composeGate = ComposeUiSupportRule(composeRule)

    /**
     * The regression itself: work handed to [rememberAppScope] finishes even though the composable
     * that started it is gone before the work gets anywhere.
     *
     * The gate is what makes this a test rather than a race. The action parks on it, so the
     * disposal is *guaranteed* to happen while the work is still outstanding — exactly the case
     * that used to lose the write, rather than the case that happened to win.
     */
    @Test
    fun anActionOnTheAppScopeOutlivesTheDialogThatStartedIt() {
        val gate = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()

        val job = launchThenClose(finished, gate) { rememberAppScope() }

        assertFalse("the action must still be waiting, not cancelled", job.isCancelled)
        gate.complete(Unit)
        runBlocking { withTimeout(TIMEOUT_MS) { finished.await() } }
    }

    /**
     * The trap, kept under test so the comment above is not the only thing saying it is real: the
     * identical action on `rememberCoroutineScope()` is cancelled by the dialog closing and never
     * finishes, no matter how long it is given.
     */
    @Test
    fun theSameActionOnTheDialogsOwnScopeIsThrownAway() {
        val gate = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()

        val job = launchThenClose(finished, gate) { rememberCoroutineScope() }

        assertTrue("closing the dialog cancels its own scope", job.isCancelled)
        gate.complete(Unit)
        composeRule.waitForIdle()
        assertFalse("a cancelled action never lands, and says nothing", finished.isCompleted)
    }

    /**
     * Mounts a stand-in for a confirmation dialog, taps its confirm, and lets the composition
     * settle — which is where the dialog is unmounted, since confirming closes it, just as
     * `onClose()` does in the real one.
     *
     * @return the job the confirm started, to be asked whether it survived.
     */
    private fun launchThenClose(
        finished: CompletableDeferred<Unit>,
        gate: CompletableDeferred<Unit>,
        scopeUnderTest: @Composable () -> CoroutineScope,
    ): Job {
        val isOpen = mutableStateOf(true)
        lateinit var job: Job
        lateinit var confirm: () -> Unit

        composeRule.setContent {
            if (isOpen.value) {
                val scope = scopeUnderTest()
                confirm = {
                    job = scope.launch {
                        gate.await()
                        finished.complete(Unit)
                    }
                    // The line that does the damage: the dialog goes away while the action above
                    // has not run yet.
                    isOpen.value = false
                }
            }
        }

        composeRule.runOnIdle { confirm() }
        composeRule.waitForIdle()
        return job
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
