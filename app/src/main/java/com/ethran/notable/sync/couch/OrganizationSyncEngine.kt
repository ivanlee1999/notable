package com.ethran.notable.sync.couch

import com.ethran.notable.data.OrganizationEntry
import com.ethran.notable.data.decodeOrganization
import com.ethran.notable.data.encodeOrganization
import kotlinx.serialization.json.JsonObject

/** Local records are the durable desired state. Only server-observed versions are acknowledged. */
class OrganizationSyncEngine(
    private val client: CouchDbClient,
    private val allLocal: suspend () -> List<OrganizationEntry>,
    private val mergeLocal: suspend (OrganizationEntry) -> OrganizationEntry,
    private val checkActive: suspend () -> Unit = {},
) {
    suspend fun sync(upload: Boolean, download: Boolean): Map<String, JsonObject> {
        val observed = linkedMapOf<String, JsonObject>()
        checkActive()
        if (download) {
            // Decode the entire response before applying anything. An invalid snapshot is never empty.
            val remote = client.allRaw("journal:").map { decodeOrganization(it.json) }
            for (entry in remote) {
                checkActive()
                mergeLocal(entry)
                observed[entry.notebookId] = encodeOrganization(entry)
            }
        }
        if (upload) for (entry in allLocal()) {
            val id = "journal:${entry.notebookId}"
            var accepted = false
            repeat(4) {
                if (accepted) return@repeat
                checkActive()
                val stored = client.getRaw(id)
                val remote = stored?.let { decodeOrganization(it.json) }
                // This transaction reads CURRENT local state, including edits made during HTTP.
                checkActive()
                val merged = mergeLocal(remote ?: entry)
                if (remote == merged) {
                    observed[entry.notebookId] = encodeOrganization(merged)
                    accepted = true
                } else {
                    try {
                        checkActive()
                        client.put(id, stored?.rev, encodeOrganization(merged), JsonObject.serializer())
                        observed[entry.notebookId] = encodeOrganization(merged)
                        accepted = true
                    } catch (_: CouchError.Conflict) {
                        // Refetch and merge; never retry a stale body with a fresh revision.
                    }
                }
            }
            if (!accepted) throw CouchError.Conflict(id)
        }
        return observed
    }
}

internal fun organizationFlushReport(
    ink: CouchSyncEngine.FlushReport, pending: List<String>, error: Exception?,
): CouchSyncEngine.FlushReport {
    val key = "journal:organization"
    val reason = error?.cause ?: error
    val retriable = when (reason) {
        is CouchError.Conflict -> true
        is CouchError -> reason.isRetriable
        is java.io.IOException -> true
        else -> false
    }
    return ink.copy(
        stillDirty = (ink.stillDirty + pending + if (error != null) listOf(key) else emptyList()).distinct(),
        failures = ink.failures + if (error != null) mapOf(key to (error.message ?: "Organization sync failed")) else emptyMap(),
        failureCauses = ink.failureCauses + if (error != null) mapOf(key to error) else emptyMap(),
        hasRetriableFailure = ink.hasRetriableFailure || retriable,
        retryAfterMs = listOfNotNull(ink.retryAfterMs, (reason as? CouchError.Server)?.retryAfterMs).maxOrNull(),
    )
}
