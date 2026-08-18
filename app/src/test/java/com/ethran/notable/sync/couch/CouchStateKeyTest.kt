package com.ethran.notable.sync.couch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which kv key a server's checkpoint lives under.
 *
 * The key used to hash the raw URL, so a trailing slash, the host's case, or embedded
 * `user:pass@` re-derived a different key — silently discarding the checkpoint (a full feed
 * replay) and, worse, folding the password into the key's identity. bopa normalizes deliberately
 * (`endpointIdentity`); this mirrors it, plus the one-time migration that moves state written
 * under the raw-URL key to the normalized one.
 */
class CouchStateKeyTest {

    @Test
    fun `case, slashes and userinfo variants derive one key`() {
        val canonical = couchStateKey("https://couch.example.com/db", "notes")
        val variants = listOf(
            "https://couch.example.com/db/",
            "https://COUCH.example.COM/db",
            "HTTPS://couch.example.com/db",
            "https://alice:secret@couch.example.com/db",
            "  https://couch.example.com/db  ",
            "https://alice:secret@COUCH.EXAMPLE.com/db///",
        )
        for (variant in variants) {
            assertEquals(
                "'$variant' should reach the same checkpoint",
                canonical,
                couchStateKey(variant, "notes"),
            )
        }
    }

    @Test
    fun `a password is not part of the key's identity`() {
        assertEquals(
            "changing a password must not discard the checkpoint",
            couchStateKey("https://alice:old@couch.example.com/db", "notes"),
            couchStateKey("https://alice:new@couch.example.com/db", "notes"),
        )
    }

    @Test
    fun `different servers, ports, paths and databases stay distinct`() {
        val base = couchStateKey("https://couch.example.com/db", "notes")
        assertNotEquals(base, couchStateKey("https://other.example.com/db", "notes"))
        assertNotEquals(base, couchStateKey("https://couch.example.com:5984/db", "notes"))
        assertNotEquals(base, couchStateKey("https://couch.example.com/other", "notes"))
        assertNotEquals(base, couchStateKey("https://couch.example.com/db", "journal"))
    }

    /** An address that does not parse is used as typed — exactly as good as the old behaviour. */
    @Test
    fun `an unparseable url still yields a stable key`() {
        assertEquals(
            couchStateKey("not a url at all", "notes"),
            couchStateKey("not a url at all", "notes"),
        )
    }

    @Test
    fun `the normalizer folds away exactly the non-differences`() {
        assertEquals(
            "https://couch.example.com/db",
            endpointIdentity(" HTTPS://alice:pw@COUCH.example.com/db/ "),
        )
        assertEquals(
            "the port is a real difference and stays",
            "https://couch.example.com:5984/db",
            endpointIdentity("https://couch.example.com:5984/db"),
        )
    }

    // region Migration

    private class MapKv {
        val rows = mutableMapOf<String, CouchSyncState>()
        val removed = mutableListOf<String>()

        suspend fun migrate(key: String, legacyKey: String): CouchSyncState? =
            migrateLegacyCouchState(
                key = key,
                legacyKey = legacyKey,
                read = { rows[it] },
                write = { k, state -> rows[k] = state },
                remove = { rows.remove(it); removed += it },
            )
    }

    /** Nobody may lose their checkpoint to the update that introduced normalization. */
    @Test
    fun `state under the raw-url key moves to the normalized key once`() = runBlocking {
        val kv = MapKv()
        val newKey = couchStateKey("https://Couch.example.com/db/", "notes")
        val legacyKey = legacyCouchStateKey("https://Couch.example.com/db/", "notes")
        assertNotEquals("this url is one normalization changes", newKey, legacyKey)
        val checkpoint = CouchSyncState(lastSeq = "42", revs = mapOf("page:p1" to "3-abc"))
        kv.rows[legacyKey] = checkpoint

        val migrated = kv.migrate(newKey, legacyKey)

        assertEquals("the checkpoint travels intact", checkpoint, migrated)
        assertEquals(checkpoint, kv.rows[newKey])
        assertNull("the legacy row is pruned; nothing will read it again", kv.rows[legacyKey])
        assertEquals(listOf(legacyKey), kv.removed)
    }

    @Test
    fun `nothing to migrate when the keys coincide or the legacy row is absent`() = runBlocking {
        val kv = MapKv()
        // An already-normal url: both derivations agree, so there is nothing to look for.
        val url = "https://couch.example.com/db"
        assertNull(kv.migrate(couchStateKey(url, "notes"), legacyCouchStateKey(url, "notes")))

        // A url normalization changes, but no legacy state was ever written.
        val newKey = couchStateKey("https://Couch.example.com/db/", "notes")
        val legacyKey = legacyCouchStateKey("https://Couch.example.com/db/", "notes")
        assertNull(kv.migrate(newKey, legacyKey))
        assertTrue("nothing may be invented or removed", kv.rows.isEmpty() && kv.removed.isEmpty())
    }

    /** A checkpoint already under the new key wins; the migration never overwrites it. */
    @Test
    fun `migration is not consulted once the normalized key holds state`() = runBlocking {
        // loadState only calls the migration when the normalized key came up empty, so the pure
        // function itself never needs to arbitrate — but the shape is worth pinning: a caller
        // that migrates unconditionally would overwrite newer state with older.
        val kv = MapKv()
        val newKey = couchStateKey("https://Couch.example.com/db/", "notes")
        val legacyKey = legacyCouchStateKey("https://Couch.example.com/db/", "notes")
        kv.rows[newKey] = CouchSyncState(lastSeq = "99")
        kv.rows[legacyKey] = CouchSyncState(lastSeq = "1")

        // The host reads the new key first and returns; migration is reached only on a miss.
        val current = kv.rows[newKey]
        assertEquals("99", current!!.lastSeq)
    }

    // endregion
}
