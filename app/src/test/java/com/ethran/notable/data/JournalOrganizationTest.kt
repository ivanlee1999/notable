package com.ethran.notable.data

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalOrganizationTest {
    private val vectors = Json.parseToJsonElement(
        javaClass.getResource("/journal-sync-vectors.json")!!.readText()
    ).jsonObject

    @Test
    fun sharedNormalizationVectors() {
        vectors.getValue("normalization").jsonArray.forEach { value ->
            val vector = value.jsonObject
            val input = vector.getValue("input").jsonPrimitive.content
            assertEquals(vector.getValue("name").jsonPrimitive.content, topicName(input))
            assertEquals(vector.getValue("key").jsonPrimitive.content, topicKey(input))
        }
        assertEquals("\u00a0#École\u00a0", topicKey("\u00a0#ÉCOLE\u00a0"))
        assertEquals(listOf("#Idea"), normalizeTopics(listOf(" #Idea "), listOf("#Idea")))
    }

    @Test
    fun sharedMergesAreCommutativeAndIdempotent() {
        vectors.getValue("merges").jsonArray.forEach { value ->
            val vector = value.jsonObject
            val left = decodeOrganization(vector.getValue("left").jsonObject)
            val right = decodeOrganization(vector.getValue("right").jsonObject)
            val expected = decodeOrganization(vector.getValue("expected").jsonObject)
            assertEquals(vector.getValue("name").jsonPrimitive.content, expected, mergeOrganization(left, right))
            assertEquals(expected, mergeOrganization(right, left))
            assertEquals(expected, mergeOrganization(expected, expected))
            assertEquals(expected, decodeOrganization(encodeOrganization(expected)))
        }
    }

    @Test
    fun mergesAreAssociativeAcrossIndependentTagsRemovalsAndCaptureTuples() {
        val entries = vectors.getValue("merges").jsonArray.flatMap { value ->
            val vector = value.jsonObject
            listOf("left", "right").map { decodeOrganization(vector.getValue(it).jsonObject) }
        }
        for (a in entries) for (b in entries) for (c in entries) {
            assertEquals(mergeOrganization(mergeOrganization(a, b), c), mergeOrganization(a, mergeOrganization(b, c)))
        }
    }

    @Test
    fun revisionTiesUseUtf8WriterThenRemovalThenName() {
        val a = OrganizationEntry("note", null, 1, mapOf("product" to TopicRevision("Product", true, 1, "\uE000")))
        val b = a.copy(topics = mapOf("product" to TopicRevision("PRODUCT", false, 1, "\uD800\uDC00")))
        assertFalse(mergeOrganization(a, b).topics.getValue("product").present)
        val sameStamp = a.copy(topics = mapOf("product" to TopicRevision("PRODUCT", false, 1, "\uE000")))
        assertFalse(mergeOrganization(a, sameStamp).topics.getValue("product").present)
        val nameTie = a.copy(topics = mapOf("product" to TopicRevision("product", true, 1, "\uE000")))
        assertEquals("product", mergeOrganization(a, nameTie).topics.getValue("product").name)
    }

    @Test
    fun rejectsWrongIdentityDeletedDocumentsAndLossyNumbers() {
        val valid = encodeOrganization(OrganizationEntry("note", "2026-11-11", 1,
            mapOf("product" to TopicRevision("Product", true, 1, "a"))))
        val invalid = listOf(
            valid + ("_id" to JsonPrimitive("journal:other")),
            valid - "_id",
            valid + ("_deleted" to JsonPrimitive(true)),
            valid + ("schema" to JsonPrimitive(2)),
            valid + ("createdAt" to JsonPrimitive(MAX_ORGANIZATION_NUMBER + 1)),
            valid + ("createdAt" to JsonPrimitive(-1)),
            valid + ("createdAt" to JsonPrimitive("1")),
            valid + ("date" to JsonPrimitive("2026-02-30")),
            valid + ("topics" to buildJsonObject { put("wrong", valid.getValue("topics").jsonObject.getValue("product")) }),
        )
        invalid.forEach { raw -> assertTrue(runCatching { decodeOrganization(JsonObject(raw)) }.isFailure) }
    }
}
