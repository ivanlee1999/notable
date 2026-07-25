package com.ethran.notable.editor.utils

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PenSerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun unknown_pen_name_decodes_to_ballpen_instead_of_throwing() {
        // The field-reported crash: a settings blob carried "NEO_PENCIL", a pen we never shipped.
        val result = json.decodeFromString(Pen.serializer(), "\"NEO_PENCIL\"")
        assertEquals(Pen.BALLPEN, result)
    }

    @Test
    fun known_pen_round_trips() {
        val encoded = json.encodeToString(Pen.serializer(), Pen.FOUNTAIN)
        assertEquals("\"FOUNTAIN\"", encoded)
        assertEquals(Pen.FOUNTAIN, json.decodeFromString(Pen.serializer(), encoded))
    }

    @Test
    fun decode_is_case_insensitive_via_fromString() {
        assertEquals(Pen.CHARCOAL, json.decodeFromString(Pen.serializer(), "\"charcoal\""))
    }
}
