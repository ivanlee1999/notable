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

    @Test
    fun rootChildNames_requires_and_removes_root_self_entry() {
        val result = rootChildNames(
            path = "/notable/",
            decodedNames = listOf("notable", "notebooks", "deletions")
        )

        assertEquals(setOf("notebooks", "deletions"), result)
    }

    @Test
    fun rootChildNames_rejects_response_without_root_self_entry() {
        assertNull(rootChildNames("/notable", listOf("notebooks", "deletions")))
    }
}
