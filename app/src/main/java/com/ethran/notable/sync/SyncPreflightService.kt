package com.ethran.notable.sync

import android.content.Context
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class SyncPreflightService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val kvProxy: KvProxy
) {
    suspend fun checkWifiConstraint(): AppResult<Unit, DomainError> {
        val settings = kvProxy.getSyncSettings()
        if (!settings.wifiOnly) return AppResult.Success(Unit)

        return if (ConnectivityChecker(context).isUnmeteredConnected()) {
            AppResult.Success(Unit)
        } else {
            AppResult.Error(DomainError.SyncWifiRequired)
        }
    }

    fun checkClockSkew(client: WebDAVClient): AppResult<Unit, DomainError> {
        val serverTime = client.getServerTime().onFailure { return AppResult.Error(it) }

        val skewMs = System.currentTimeMillis() - serverTime
        return if (abs(skewMs) > CLOCK_SKEW_THRESHOLD_MS) {
            AppResult.Error(DomainError.SyncClockSkew(skewMs / 1000))
        } else {
            AppResult.Success(Unit)
        }
    }

    fun ensureServerDirectories(client: WebDAVClient): AppResult<Unit, DomainError> {
        val dirs = listOf(
            SyncPaths.rootDir(),
            SyncPaths.notebooksDir(),
            SyncPaths.tombstonesDir()
        )
        for (dir in dirs) {
            val present = client.exists(dir).onFailure { return AppResult.Error(it) }
            if (!present) {
                client.createCollection(dir).onError { return AppResult.Error(it) }
            }
        }
        return AppResult.Success(Unit)
    }

    /**
     * Collapsed full-sync preflight: one root `PROPFIND` replaces the three sequential existence HEADs
     * of [ensureServerDirectories], and its `Date` header replaces the [checkClockSkew] HEAD whenever
     * the server sends a usable one.
     *
     * Ordering matches the old two-call sequence: clock skew is verified *before* any directory is
     * created, so a skewed clock still fails fast. A root 404 is the only "missing" signal that comes
     * from the probe; every other probe failure (auth, permission, transient) propagates unchanged and
     * is never mistaken for an absent directory. When the `Date` header is missing or unparseable the
     * dedicated [checkClockSkew] HEAD is issued as a fallback, so skew is never silently skipped.
     */
    fun ensureServerReady(client: WebDAVClient): AppResult<Unit, DomainError> {
        val probe = client.probeRoot(SyncPaths.rootDir()).onFailure { return AppResult.Error(it) }

        checkClockSkew(probe, client).onFailure { return AppResult.Error(it) }

        if (!probe.rootExists) {
            // The root is absent, so both children are too — create the whole tree unconditionally.
            val directories =
                listOf(SyncPaths.rootDir(), SyncPaths.notebooksDir(), SyncPaths.tombstonesDir())
            for (dir in directories) {
                client.createCollection(dir).onError { return AppResult.Error(it) }
            }
            return AppResult.Success(Unit)
        }

        for (dir in listOf(SyncPaths.notebooksDir(), SyncPaths.tombstonesDir())) {
            // SyncPaths currently has no trailing slash, but normalize here so changing that path
            // spelling cannot silently turn every child name into an empty string.
            val childName = dir.trimEnd('/').substringAfterLast('/')
            if (childName !in probe.childNames) {
                client.createCollection(dir).onError { return AppResult.Error(it) }
            }
        }
        return AppResult.Success(Unit)
    }

    /**
     * Clock-skew check from a [RootProbe]: uses the probe's `Date` header against the local midpoint
     * when present, and otherwise falls back to the dedicated [checkClockSkew] HEAD. HTTP servers may
     * omit or corrupt `Date`, so the fallback keeps skew detection correct rather than assuming zero.
     */
    private fun checkClockSkew(
        probe: RootProbe,
        client: WebDAVClient
    ): AppResult<Unit, DomainError> {
        val serverTime = probe.serverTimeMs ?: return checkClockSkew(client)
        val skewMs = probe.localMidpointMs - serverTime
        return if (abs(skewMs) > CLOCK_SKEW_THRESHOLD_MS) {
            AppResult.Error(DomainError.SyncClockSkew(skewMs / 1000))
        } else {
            AppResult.Success(Unit)
        }
    }

    companion object {
        private const val CLOCK_SKEW_THRESHOLD_MS = 30_000L
    }
}
