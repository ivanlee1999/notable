package com.ethran.notable.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BlockPayloadTest {

    @Test
    fun `an empty payload is the empty column value, and reads back empty`() {
        assertEquals("{}", BlockPayload().encode())
        assertEquals(BlockPayload(), BlockPayload.decode("{}"))
        assertEquals(BlockPayload(), BlockPayload.decode(""))
    }

    @Test
    fun `a payload round-trips through its column`() {
        val payload = BlockPayload(imageAssetId = "asset:abc", strokeIds = listOf("s1", "s2"))
        assertEquals(payload, BlockPayload.decode(payload.encode()))
    }

    @Test
    fun `an unreadable column reads as nothing rather than failing the page`() {
        assertEquals(BlockPayload(), BlockPayload.decode("not json"))
    }

    @Test
    fun `renaming stroke ids follows the map and drops what it does not know`() {
        val block = Block(
            pageId = "p",
            kind = "ink",
            payload = BlockPayload(strokeIds = listOf("s1", "s2", "gone")).encode(),
        )
        val renamed = block.withStrokeIdsRenamed(mapOf("s1" to "n1", "s2" to "n2"))
        assertEquals(listOf("n1", "n2"), renamed.decodedPayload.strokeIds)
    }

    @Test
    fun `a block that names no strokes is untouched by a rename`() {
        val block = Block(pageId = "p", text = "hello")
        assertSame(block, block.withStrokeIdsRenamed(mapOf("s1" to "n1")))
    }
}
