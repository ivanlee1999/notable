package com.ethran.notable.sync.couch

import com.ethran.notable.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class OrganizationSyncLiveTest {
    @Test fun independentWritersRoundTripCaptureTagsAndRemovalThroughCouchDB() = runBlocking {
        val url = System.getenv("NOTABLE_COUCH_URL")
        val database = System.getenv("NOTABLE_COUCH_DATABASE")
        assumeTrue("Requires an isolated test_ CouchDB database", !url.isNullOrBlank() && database?.startsWith("test_") == true)
        val client = CouchDbClient(OkHttpCouchTransport(url!!,
            System.getenv("NOTABLE_COUCH_USER"), System.getenv("NOTABLE_COUCH_PASSWORD"), OkHttpClient()),
            "${database}_organization")
        val id = UUID.randomUUID().toString()
        var a = OrganizationEntry(id, "2026-11-11", 1794427200000,
            mapOf("product" to TopicRevision("Product", true, 1, "a")))
        var b = OrganizationEntry(id, null, 1,
            mapOf("alex" to TopicRevision("Alex", true, 1, "b")))
        // Ignore other test records in this deliberately shared isolated database.
        fun engine(read: () -> OrganizationEntry, write: (OrganizationEntry) -> Unit) =
            OrganizationSyncEngine(client, { listOf(read()) }, { incoming ->
                if (incoming.notebookId == id) mergeOrganization(read(), incoming).also(write) else incoming
            })
        engine({ a }, { a = it }).sync(true, false)
        engine({ b }, { b = it }).sync(true, true)
        engine({ a }, { a = it }).sync(false, true)
        assertEquals(setOf("product", "alex"), a.topics.keys)
        assertEquals("2026-11-11", b.date)
        a = a.copy(topics = a.topics + ("product" to TopicRevision("Product", false, 2, "a")))
        engine({ a }, { a = it }).sync(true, true)
        engine({ b }, { b = it }).sync(true, true)
        assertFalse(b.topics.getValue("product").present)
        assertTrue(b.topics.getValue("alex").present)
        val remote = decodeOrganization(client.getRaw("journal:$id")!!.json)
        assertEquals(a, remote)
    }
}
