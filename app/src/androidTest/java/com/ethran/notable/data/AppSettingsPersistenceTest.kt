package com.ethran.notable.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.CryptoHelper
import com.ethran.notable.data.db.Kv
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.KvRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class AppSettingsPersistenceTest {
    private val previous = GlobalAppSettings.current
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = mockk<KvRepository>()
    private val proxy = KvProxy(repository, mockk<CryptoHelper>(), applicationScope)
    private val writes = CopyOnWriteArrayList<AppSettings>()
    private val first = AppSettings(version = 1, toolbarFavoriteIds = listOf("ball", "red"))
    private val latest = first.copy(toolbarFavoriteIds = listOf("red", "ball"), canvasLocked = true)

    @After fun cleanup() = runBlocking {
        applicationScope.coroutineContext.job.cancelAndJoin()
        GlobalAppSettings.update(previous)
    }

    @Test fun blockedOlderWriteCannotRevertANewerFavoriteOrderInMemoryOrOnDisk() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        coEvery { repository.set(any()) } coAnswers {
            val value = Json.decodeFromString<AppSettings>(firstArg<Kv>().value)
            if (value == first) { started.complete(Unit); finish.await() }
            writes += value
        }
        val oldWrite = proxy.enqueueAppSettings(first)
        withTimeout(5_000) { started.await() }
        val newWrite = proxy.enqueueAppSettings(latest)
        assertEquals(latest, GlobalAppSettings.current)
        assertTrue(writes.isEmpty())
        finish.complete(Unit)
        withTimeout(5_000) { oldWrite.await(); newWrite.await() }
        assertEquals(listOf(first, latest), writes.toList())
        assertEquals(latest, GlobalAppSettings.current)
    }

    @Test fun aFailedWriteDoesNotPoisonTheNextAwaitableSave() = runBlocking {
        coEvery { repository.set(any()) } coAnswers {
            val value = Json.decodeFromString<AppSettings>(firstArg<Kv>().value)
            if (value == first) throw IOException("disk temporarily unavailable")
            writes += value
        }
        assertTrue(runCatching { proxy.setAppSettings(first) }.isFailure)
        withTimeout(5_000) { proxy.setAppSettings(latest) }
        assertEquals(listOf(latest), writes.toList())
        assertEquals(latest, GlobalAppSettings.current)
    }

    @Test fun cancellingTheUiAwaiterDoesNotCancelTheQueuedPersistence() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val saved = CompletableDeferred<Unit>()
        coEvery { repository.set(any()) } coAnswers {
            started.complete(Unit); finish.await()
            writes += Json.decodeFromString<AppSettings>(firstArg<Kv>().value)
            saved.complete(Unit)
        }
        val caller = launch { proxy.setAppSettings(latest) }
        withTimeout(5_000) { started.await() }
        caller.cancelAndJoin()
        finish.complete(Unit)
        withTimeout(5_000) { saved.await() }
        assertEquals(listOf(latest), writes.toList())
        assertEquals(latest, GlobalAppSettings.current)
    }

    @Test fun backgroundPageCleanupTransformsTheLatestFavoriteSnapshotWithoutDroppingOtherFields() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var count = 0
        coEvery { repository.set(any()) } coAnswers {
            if (++count == 1) { started.complete(Unit); finish.await() }
            writes += Json.decodeFromString<AppSettings>(firstArg<Kv>().value)
        }
        val firstWrite = proxy.enqueueAppSettings(first.copy(quickNavPages = listOf("removed", "kept")))
        withTimeout(5_000) { started.await() }
        val newWrite = proxy.enqueueAppSettings(latest.copy(quickNavPages = listOf("removed", "kept")))
        val cleanup = proxy.enqueueAppSettings { it.copy(quickNavPages = it.quickNavPages - "removed") }
        val expected = latest.copy(quickNavPages = listOf("kept"))
        assertEquals(expected, GlobalAppSettings.current)
        finish.complete(Unit)
        withTimeout(5_000) { firstWrite.await(); newWrite.await(); cleanup.await() }
        assertEquals(expected, writes.last())
        assertEquals(expected, GlobalAppSettings.current)
    }
}
