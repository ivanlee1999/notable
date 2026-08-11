package com.ethran.notable.sync.couch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sync log is kept on disk, forwarded to ShipBook and copied into bug reports, so a CouchDB URL
 * written the ordinary way — `https://user:password@host` — must not carry the password into it.
 */
class RedactUserInfoTest {

    @Test
    fun `credentials in the authority are replaced`() {
        assertEquals(
            "https://***@couch.example.com:5984/notes",
            redactUserInfo("https://admin:hunter2@couch.example.com:5984/notes")
        )
    }

    @Test
    fun `a username with no password is still redacted`() {
        assertEquals("http://***@localhost:5984", redactUserInfo("http://admin@localhost:5984"))
    }

    @Test
    fun `a url without credentials is left completely readable`() {
        val url = "https://couch.example.com:5984/notes"
        assertEquals(url, redactUserInfo(url))
    }

    /**
     * The redaction runs on strings that failed to parse — that is the only time it is called — so
     * it must not depend on them being well-formed, and must never fall back to the raw value.
     */
    @Test
    fun `a malformed url keeps its credentials hidden`() {
        assertEquals("https://***@ho st:5984/x", redactUserInfo("https://admin:pw@ho st:5984/x"))
    }

    @Test
    fun `nothing that resembles a scheme means nothing is touched`() {
        assertEquals("not a url at all", redactUserInfo("not a url at all"))
        assertEquals("", redactUserInfo(""))
    }

    /**
     * An `@` after the authority belongs to the path, not to credentials — redacting from there
     * would silently eat the host as well and make the typo unreadable.
     */
    @Test
    fun `an at sign in the path is not mistaken for credentials`() {
        val url = "https://couch.example.com/notes/a@b"
        assertEquals(url, redactUserInfo(url))
    }

    @Test
    fun `an at sign in the query is not mistaken for credentials`() {
        val url = "https://couch.example.com/notes?to=a@b"
        assertEquals(url, redactUserInfo(url))
    }
}
