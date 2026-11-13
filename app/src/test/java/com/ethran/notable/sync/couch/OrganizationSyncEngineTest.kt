package com.ethran.notable.sync.couch

import com.ethran.notable.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class OrganizationSyncEngineTest {
    private val server = FakeCouchTransport()
    private fun entry(topics: Map<String, TopicRevision> = emptyMap()) =
        OrganizationEntry("note", "2026-11-11", 1794427200000, topics)
    private fun topic(name: String, counter: Long, present: Boolean = true) =
        TopicRevision(name, present, counter, "writer")

    private class Local(initial: OrganizationEntry) {
        val values = ConcurrentHashMap<String, OrganizationEntry>().apply { put(initial.notebookId, initial) }
        fun engine(client: CouchDbClient) = OrganizationSyncEngine(client,
            { values.values.toList() }, { incoming ->
                values.compute(incoming.notebookId) { _, old -> old?.let { mergeOrganization(it, incoming) } ?: incoming }!!
            })
        val current get() = values.getValue("note")
    }

    @Test fun offlineAdditionsAndRemovalSurviveStalePeerAndReadd() = runBlocking {
        val client = CouchDbClient(server, "notes_organization")
        val a = Local(entry(mapOf("product" to topic("Product", 1))))
        val b = Local(entry(mapOf("alex" to topic("Alex", 1))))
        a.engine(client).sync(true, true)
        b.engine(client).sync(true, true)
        a.engine(client).sync(false, true)
        assertEquals(setOf("product", "alex"), a.current.topics.keys)
        a.values["note"] = a.current.copy(topics = a.current.topics + ("product" to topic("Product", 2, false)))
        a.engine(client).sync(true, true)
        b.engine(client).sync(true, true)
        assertFalse(b.current.topics.getValue("product").present)
        b.values["note"] = b.current.copy(topics = b.current.topics + ("product" to topic("Product", 3)))
        b.engine(client).sync(true, true)
        a.engine(client).sync(false, true)
        assertTrue(a.current.topics.getValue("product").present)
        assertEquals("2026-11-11", a.current.date)
    }

    @Test fun conflictRefetchesAndMergesCurrentLocalEdits() = runBlocking {
        val local = Local(entry(mapOf("product" to topic("Product", 1))))
        var conflict = true
        val transport = object : CouchTransport {
            override fun send(request: CouchRequest): CouchResponse {
                if (request.method == "PUT" && conflict) {
                    conflict = false
                    val remote = encodeOrganization(entry(mapOf("alex" to topic("Alex", 2))))
                    server.send(CouchRequest("PUT", request.path, body = remote.toString().toByteArray()))
                    local.values["note"] = local.current.copy(topics = local.current.topics + ("idea" to topic("Idea", 3)))
                }
                return server.send(request)
            }
        }
        local.engine(CouchDbClient(transport, "notes_organization")).sync(true, true)
        val stored = CouchDbClient(server, "notes_organization").getRaw("journal:note")!!
        assertEquals(setOf("product", "alex", "idea"), decodeOrganization(stored.json).topics.keys)
    }

    @Test fun editDuringSuccessfulPutIsNotAcknowledgedAsUploaded() = runBlocking {
        val local = Local(entry(mapOf("product" to topic("Product", 1))))
        var edit = true
        val transport = object : CouchTransport {
            override fun send(request: CouchRequest): CouchResponse {
                val response = server.send(request)
                if (request.method == "PUT" && edit) {
                    edit = false
                    local.values["note"] = local.current.copy(topics = local.current.topics + ("alex" to topic("Alex", 2)))
                }
                return response
            }
        }
        val engine = local.engine(CouchDbClient(transport, "notes_organization"))
        val observed = engine.sync(true, true)
        assertNotEquals(encodeOrganization(local.current), observed["note"])
        engine.sync(true, true)
        assertEquals(local.current, decodeOrganization(CouchDbClient(server, "notes_organization").getRaw("journal:note")!!.json))
    }

    @Test fun downloadOnlyDoesNotPublishLocalRecords() = runBlocking {
        val local = Local(entry(mapOf("product" to topic("Product", 1))))
        local.engine(CouchDbClient(server, "notes_organization")).sync(false, true)
        assertTrue(server.requestLog.none { it.first == "PUT" })
    }

    @Test fun malformedSnapshotDoesNotOverwriteLocalRecords() = runBlocking {
        val local = Local(entry(mapOf("product" to topic("Product", 1))))
        val transport = object : CouchTransport {
            override fun send(request: CouchRequest) = CouchResponse(status = 200,
                body = """{"rows":[{"doc":{"_id":"journal:bad","_rev":"1-a","type":"journal","schema":99}}]}""".toByteArray())
        }
        val before = local.current
        try {
            local.engine(CouchDbClient(transport, "notes_organization")).sync(true, true)
            fail("Expected invalid metadata failure")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, local.current)
    }

    @Test fun offlineFailureRetainsDesiredStateForRetry() = runBlocking {
        val local = Local(entry(mapOf("product" to topic("Product", 1))))
        server.isOffline = true
        try {
            local.engine(CouchDbClient(server, "notes_organization")).sync(true, true)
            fail("Expected offline failure")
        } catch (_: Exception) { }
        assertEquals(setOf("product"), local.current.topics.keys)
        server.isOffline = false
        local.engine(CouchDbClient(server, "notes_organization")).sync(true, true)
        assertNotNull(CouchDbClient(server, "notes_organization").getRaw("journal:note"))
    }
}
