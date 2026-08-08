package com.ethran.notable.sync

import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.getOrElse
import com.ethran.notable.utils.getOrNull
import com.ethran.notable.utils.onFailure
import io.shipbook.shipbooksdk.ShipBook
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Measures, on one server, the non-standard ETag behaviours the sync fast paths rely on
 * (`docs/plans/bulk-change-detection-plan.md`, Part 1).
 *
 * Runs only from Test Connection — ~10 requests against a scratch tree it creates and deletes, never
 * on an ordinary sync. The tree mirrors the real layout depth for depth
 * (`notebooks/<id>/{manifest.json, pages/<p>.json}`) so propagation is measured over the same
 * distance the sync will lean on, watching the ancestor `notebooks/` collection's ETag after each
 * mutation.
 *
 * Any failure, interruption, or ambiguity yields a record with both flags false: unknown is treated
 * as absent, never as present. The failure the flags guard against is silent, so the default is off.
 */
@Singleton
class CapabilityProbeService @Inject constructor() {

    private val log = ShipBook.getLogger(TAG)

    /**
     * Run the probe and return what it observed. [nowMs] is passed in (not read from the clock) so
     * the caller controls the timestamp and tests are deterministic. Always cleans up the scratch
     * tree, even on failure.
     */
    fun probe(
        client: WebDAVClient,
        serverUrl: String,
        username: String,
        nowMs: Long
    ): ServerCapabilities {
        val serverKey = ServerCapabilities.serverKey(serverUrl, username)
        val failed = ServerCapabilities(
            serverKey = serverKey,
            probedAt = nowMs,
            collectionEtagPropagates = false,
            movePreservesEtag = false,
            issuesEtagOnPut = false,
            issuesStrongEtags = false,
        )

        // Clear any tree a previous run left behind before it could clean up.
        client.delete(PROBE_ROOT)

        return try {
            runProbe(client, serverKey, nowMs) ?: failed
        } catch (e: Exception) {
            log.w("Capability probe failed", e)
            failed
        } finally {
            client.delete(PROBE_ROOT) // best-effort; a leftover tree is harmless and self-heals next run
        }
    }

    /**
     * Perform the mutations and gather the readings, then hand them to [evaluate]. Returns null on
     * the first request that fails — the caller turns that into the failed record. Keeps all I/O
     * here so the verdict ([evaluate]) stays a pure, unit-testable function.
     */
    private fun runProbe(client: WebDAVClient, serverKey: String, nowMs: Long): ServerCapabilities? {
        for (dir in listOf(PROBE_ROOT, OBSERVE_DIR, PROBE_NB, PAGES_DIR)) {
            client.createCollection(dir).onFailure { return null }
        }

        // Step 1 — create the manifest; learn what PUT reports about ETags, and take the baseline.
        val putEtag = client.putFileReturningEtag(MANIFEST, bytes(1), JSON).getOrElse { return null }
        val baseline = client.resourceEtag(OBSERVE_DIR).getOrElse { return null }

        // Step 2 — in-place overwrite.
        client.putFile(MANIFEST, bytes(2), JSON).onFailure { return null }
        val afterOverwrite = client.resourceEtag(OBSERVE_DIR).getOrElse { return null }

        // Step 3 — publish via tmp PUT + MOVE, and check whether the ETag survived the MOVE.
        val tmpEtag = client.putFileReturningEtag(TMP, bytes(3), JSON).getOrElse { return null }
        val moveOk = client.move(TMP, MANIFEST) is AppResult.Success
        if (!moveOk) client.delete(TMP)
        val afterMove = client.resourceEtag(OBSERVE_DIR).getOrElse { return null }
        val destEtagAfterMove = client.resourceEtag(MANIFEST).getOrNull()

        // Step 4 — create a file two levels below the observed collection.
        client.putFile(PAGE, bytes(1), JSON).onFailure { return null }
        val afterDeepCreate = client.resourceEtag(OBSERVE_DIR).getOrElse { return null }

        // Step 5 — overwrite that deep file.
        client.putFile(PAGE, bytes(2), JSON).onFailure { return null }
        val afterDeepOverwrite = client.resourceEtag(OBSERVE_DIR).getOrElse { return null }

        return evaluate(
            serverKey, nowMs,
            ProbeObservations(
                putEtag = putEtag,
                baseline = baseline,
                afterOverwrite = afterOverwrite,
                afterMove = afterMove,
                afterDeepCreate = afterDeepCreate,
                afterDeepOverwrite = afterDeepOverwrite,
                moveOk = moveOk,
                tmpEtag = tmpEtag,
                destEtagAfterMove = destEtagAfterMove,
            )
        )
    }

    private fun bytes(n: Int): ByteArray = """{"probe":$n}""".toByteArray()

    companion object {
        private const val TAG = "CapabilityProbeService"
        private const val JSON = "application/json"

        private const val PROBE_ROOT = "/notable/.capability-probe"
        private const val OBSERVE_DIR = "$PROBE_ROOT/notebooks"
        private const val PROBE_NB = "$OBSERVE_DIR/probe"
        private const val PAGES_DIR = "$PROBE_NB/pages"
        private const val MANIFEST = "$PROBE_NB/manifest.json"
        private const val TMP = "$MANIFEST.tmp"
        private const val PAGE = "$PAGES_DIR/page.json"
    }
}

/**
 * The raw readings one probe run collected: the observed collection's ETag after each mutation, and
 * what the MOVE step reported. Separated from the I/O so [evaluate] can be exercised without a server.
 */
internal data class ProbeObservations(
    val putEtag: ETag?,
    val baseline: ETag?,
    val afterOverwrite: ETag?,
    val afterMove: ETag?,
    val afterDeepCreate: ETag?,
    val afterDeepOverwrite: ETag?,
    val moveOk: Boolean,
    val tmpEtag: ETag?,
    val destEtagAfterMove: ETag?,
)

/**
 * The verdict, as a pure function of [obs]. `collectionEtagPropagates` requires the observed ETag to
 * have moved across **all four** of overwrite → MOVE → deep-create → deep-overwrite; any step that
 * didn't move (or that the server withheld an ETag for) fails it, because a server that propagates
 * some changes but not others is the dangerous case. `movePreservesEtag` requires the MOVE to have
 * succeeded and the destination to carry the tmp file's validator.
 */
internal fun evaluate(serverKey: String, nowMs: Long, obs: ProbeObservations): ServerCapabilities {
    fun moved(a: ETag?, b: ETag?): Boolean = a != null && b != null && !a.matches(b)

    val propagates = moved(obs.baseline, obs.afterOverwrite) &&
        moved(obs.afterOverwrite, obs.afterMove) &&
        moved(obs.afterMove, obs.afterDeepCreate) &&
        moved(obs.afterDeepCreate, obs.afterDeepOverwrite)

    return ServerCapabilities(
        serverKey = serverKey,
        probedAt = nowMs,
        collectionEtagPropagates = propagates,
        movePreservesEtag = obs.moveOk && obs.destEtagAfterMove.matches(obs.tmpEtag),
        issuesEtagOnPut = obs.putEtag != null,
        issuesStrongEtags = obs.putEtag != null && !obs.putEtag.isWeak,
    )
}
