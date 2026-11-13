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
) {
    suspend fun sync(upload: Boolean, download: Boolean): Map<String, JsonObject> {
        val observed = linkedMapOf<String, JsonObject>()
        if (download) {
            // Decode the entire response before applying anything. An invalid snapshot is never empty.
            val remote = client.allRaw("journal:").map { decodeOrganization(it.json) }
            for (entry in remote) {
                mergeLocal(entry)
                observed[entry.notebookId] = encodeOrganization(entry)
            }
        }
        if (upload) for (entry in allLocal()) {
            val id = "journal:${entry.notebookId}"
            var accepted = false
            repeat(4) {
                if (accepted) return@repeat
                val stored = client.getRaw(id)
                val remote = stored?.let { decodeOrganization(it.json) }
                // This transaction reads CURRENT local state, including edits made during HTTP.
                val merged = mergeLocal(remote ?: entry)
                if (remote == merged) {
                    observed[entry.notebookId] = encodeOrganization(merged)
                    accepted = true
                } else {
                    try {
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
