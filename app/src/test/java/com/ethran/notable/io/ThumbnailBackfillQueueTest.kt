package com.ethran.notable.io

import com.ethran.notable.data.events.AppEventBus
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailBackfillQueueTest {
    @Test fun changesDuringRenderingCoalesceIntoOneFreshnessCheckAfterwards() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val generator = mockk<ThumbnailGenerator>()
        var renders = 0
        coEvery { generator.ensureThumbnail("page", any()) } coAnswers {
            if (++renders == 1) { started.complete(Unit); finish.await() }
            ThumbnailEnsureResult.GENERATED
        }
        try {
            val queue = ThumbnailBackfillQueue(scope, dispatcher, generator, mockk<AppEventBus>(relaxed = true))
            queue.enqueue(listOf("page"))
            runCurrent()
            started.await()
            repeat(5) { queue.enqueue(listOf("page")) }
            runCurrent()
            finish.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, renders)
        } finally { scope.cancel() }
    }
}
