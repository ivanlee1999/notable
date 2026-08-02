package com.ethran.notable.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class WebDavClientTest {

    @Test
    fun parseHttpDate_returns_epoch_millis_for_valid_header() {
        val header = "Tue, 15 Nov 1994 08:12:31 GMT"
        val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("GMT")
        val expected = formatter.parse(header)?.time

        val result = WebDAVClient.parseHttpDate(header)

        assertEquals(expected, result)
    }

    @Test
    fun parseHttpDate_returns_null_for_invalid_header() {
        assertNull(WebDAVClient.parseHttpDate("not-a-date"))
    }

    // The bug these pin: the same validator arrives spelled differently from a PUT response header
    // and from a PROPFIND `<getetag>`, and comparing the two spellings verbatim made an unchanged
    // page look changed on every sync.

    @Test
    fun normalizeEtag_strips_quotes_and_weak_prefix() {
        assertEquals("abc", WebDAVClient.normalizeEtag("\"abc\""))
        assertEquals("abc", WebDAVClient.normalizeEtag("W/\"abc\""))
        assertEquals("abc", WebDAVClient.normalizeEtag("w/\"abc\""))
        assertEquals("abc", WebDAVClient.normalizeEtag("  W/\"abc\"  "))
        assertEquals("abc", WebDAVClient.normalizeEtag("abc"))
    }

    @Test
    fun normalizeEtag_makes_weak_and_strong_spellings_compare_equal() {
        assertEquals(
            WebDAVClient.normalizeEtag("W/\"66d0-5f3a\""),
            WebDAVClient.normalizeEtag("\"66d0-5f3a\"")
        )
    }

    @Test
    fun normalizeEtag_is_idempotent() {
        val once = WebDAVClient.normalizeEtag("W/\"abc\"")
        assertEquals(once, WebDAVClient.normalizeEtag(once))
    }

    @Test
    fun normalizeEtag_returns_null_for_missing_or_empty() {
        assertNull(WebDAVClient.normalizeEtag(null))
        assertNull(WebDAVClient.normalizeEtag(""))
        assertNull(WebDAVClient.normalizeEtag("   "))
        assertNull(WebDAVClient.normalizeEtag("\"\""))
    }
}
