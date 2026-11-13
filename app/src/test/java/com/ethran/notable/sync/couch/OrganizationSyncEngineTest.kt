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

    @Test fun sameEndpointServerResetDoesNotKeepFalseAcknowledgements() = runBlocking {
        var activeServer = server
        val transport = object : CouchTransport {
            override fun send(request: CouchRequest) = activeServer.send(request)
        }
        val local = Local(entry(mapOf("product" to topic("Product", 1))))
        val engine = local.engine(CouchDbClient(transport, "notes_organization"))
        var acknowledged = organizationAcknowledgements(JsonObject(emptyMap()), engine.sync(true, true), true)
        assertEquals(encodeOrganization(local.current), acknowledged["note"])
        activeServer = FakeCouchTransport()
        acknowledged = organizationAcknowledgements(acknowledged, engine.sync(false, true), true)
        assertNotEquals(encodeOrganization(local.current), acknowledged["note"])
        assertEquals(setOf("product"), local.current.topics.keys)
        engine.sync(true, true)
        assertNotNull(CouchDbClient(activeServer, "notes_organization").getRaw("journal:note"))
    }

    @Test fun organizationFailureKeepsInkSuccessAndRequestsRetry() {
        val ink = CouchSyncEngine.FlushReport(pushed = listOf("page:ink"))
        val error = java.io.IOException("Organization unavailable", CouchError.Transport("offline"))
        val report = organizationFlushReport(ink, listOf("journal:note"), error)
        assertEquals(ink.pushed, report.pushed)
        assertTrue(report.stillDirty.contains("journal:note"))
        assertTrue(report.failures.isNotEmpty())
        assertTrue(report.hasRetriableFailure)
        val rateLimited = organizationFlushReport(ink, listOf("journal:note"),
            java.io.IOException("Try later", CouchError.Server(429, "/notes_organization", retryAfterMs = 30_000)))
        assertEquals(30_000L, rateLimited.retryAfterMs)
    }

    @Test fun editDuringUploadRemainsVisibleInPendingReport() {
        val report = organizationFlushReport(CouchSyncEngine.FlushReport(pushed = listOf("page:ink")),
            listOf("journal:note"), null)
        assertEquals(listOf("journal:note"), report.stillDirty)
        assertTrue(report.failures.isEmpty())
    }

    @Test fun retiredSyncCannotApplyOrUploadAResponse() = runBlocking {
        val remoteClient = CouchDbClient(server, "notes_organization")
        remoteClient.put("journal:note", null, encodeOrganization(entry(mapOf("alex" to topic("Alex", 1)))), JsonObject.serializer())
        var active = true
        val transport = object : CouchTransport {
            override fun send(request: CouchRequest): CouchResponse {
                val response = server.send(request)
                active = false
                return response
            }
        }
        var applied = false
        val engine = OrganizationSyncEngine(CouchDbClient(transport, "notes_organization"),
            { emptyList() }, { applied = true; it }, { if (!active) throw kotlinx.coroutines.CancellationException("Retired") })
        try {
            engine.sync(true, true)
            fail("Expected retirement cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertFalse(applied)
    }

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
