package com.ethran.notable.recognition

import com.ethran.notable.recognition.OnyxHwrEngine.Companion.writeFixed32
import com.ethran.notable.recognition.OnyxHwrEngine.Companion.writeString
import com.ethran.notable.recognition.OnyxHwrEngine.Companion.writeTag
import com.ethran.notable.recognition.OnyxHwrEngine.Companion.writeVarint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The protobuf primitives the recognizer's input is built from.
 *
 * These are hand-rolled rather than generated, because the service's .proto is not published and
 * a dependency would not have saved guessing the field numbers. That makes them worth pinning to
 * bytes: a wrong varint or a byte-order slip does not fail loudly on the device — MyScript
 * returns empty text, which is indistinguishable from a page it could not read.
 */
class HwrProtobufTest {

    private fun bytes(write: (ByteArrayOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also(write).toByteArray()

    @Test
    fun `a small varint is a single byte`() {
        assertArrayEquals(byteArrayOf(0), bytes { writeVarint(it, 0) })
        assertArrayEquals(byteArrayOf(1), bytes { writeVarint(it, 1) })
        assertArrayEquals(byteArrayOf(127), bytes { writeVarint(it, 127) })
    }

    @Test
    fun `a varint continues while the high bit is set`() {
        // 128 -> 0x80 0x01: seven bits per byte, low group first.
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), bytes { writeVarint(it, 128) })
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), bytes { writeVarint(it, 300) })
    }

    @Test
    fun `a large timestamp survives the varint`() {
        // Point timestamps are epoch milliseconds zigzagged; they need all ten bytes' worth of
        // room, and an Int somewhere in this path would silently truncate them.
        val millis = 1_755_500_000_000L
        val zigzag = (millis shl 1) xor (millis shr 63)

        val encoded = bytes { writeVarint(it, zigzag) }

        var decoded = 0L
        var shift = 0
        for (byte in encoded) {
            decoded = decoded or ((byte.toLong() and 0x7F) shl shift)
            shift += 7
        }
        assertEquals(millis, (decoded ushr 1) xor -(decoded and 1))
    }

    @Test
    fun `a tag packs the field number above the wire type`() {
        // Field 15, length-delimited: (15 << 3) | 2 = 122.
        assertArrayEquals(byteArrayOf(122), bytes { writeTag(it, 15, 2) })
        // Field 1, fixed32: (1 << 3) | 5 = 13.
        assertArrayEquals(byteArrayOf(13), bytes { writeTag(it, 1, 5) })
        // Field 16 is where the tag itself needs two bytes.
        assertArrayEquals(byteArrayOf(0x82.toByte(), 0x01), bytes { writeTag(it, 16, 2) })
    }

    @Test
    fun `a float is written little-endian`() {
        // 1.0f is 0x3F800000; protobuf fixed32 puts the low byte first.
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F),
            bytes { writeFixed32(it, 1.0f) }
        )
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), bytes { writeFixed32(it, 0f) })
    }

    @Test
    fun `a float round-trips through the wire bytes`() {
        for (value in listOf(0.5f, 1404f, 1872f, -12.25f, 0.0039f)) {
            val encoded = bytes { writeFixed32(it, value) }

            var bits = 0
            for (i in 3 downTo 0) bits = (bits shl 8) or (encoded[i].toInt() and 0xFF)
            assertEquals(value, java.lang.Float.intBitsToFloat(bits), 0f)
        }
    }

    @Test
    fun `a string is its length then its utf-8 bytes`() {
        assertArrayEquals(
            byteArrayOf(5, 'e'.code.toByte(), 'n'.code.toByte(), '_'.code.toByte(),
                'U'.code.toByte(), 'S'.code.toByte()),
            bytes { writeString(it, "en_US") }
        )
    }

    @Test
    fun `a string is measured in bytes, not characters`() {
        // A language tag is ASCII, but nothing stops the recognizer being asked for another
        // script, and a length in characters would truncate the field.
        val encoded = bytes { writeString(it, "日本語") }

        assertEquals(9, encoded[0].toInt())
        assertEquals(10, encoded.size)
    }
}
