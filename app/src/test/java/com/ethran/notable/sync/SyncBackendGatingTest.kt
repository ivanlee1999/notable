package com.ethran.notable.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backend switch is the single gate every sync path consults. These pin the property the
 * gates read, because the bug they exist to prevent is invisible in the UI: a WebDAV server left
 * configured and enabled would keep syncing under CouchDB, and two engines writing the same
 * notebooks through different transports is how an edit goes missing.
 */
class SyncBackendGatingTest {

    private val configuredWebdav = SyncSettings(
        syncEnabled = true,
        serverUrl = "https://example.com/dav",
        username = "u",
        password = "p",
    )

    private val configuredCouch = SyncSettings(
        couchUrl = "http://192.168.1.10:5984",
        couchDatabase = "notes",
    )

    @Test
    fun webdav_is_active_only_when_selected_and_enabled() {
        assertTrue(configuredWebdav.copy(backend = SyncBackend.WEBDAV).webdavActive)
        assertFalse(configuredWebdav.copy(backend = SyncBackend.WEBDAV, syncEnabled = false).webdavActive)
    }

    /**
     * The regression this whole change is about: switching to CouchDB must silence WebDAV even
     * though its credentials and its own on-switch are untouched, so switching back needs no
     * re-typing.
     */
    @Test
    fun a_fully_configured_webdav_goes_quiet_under_couchdb() {
        val switched = configuredWebdav.copy(backend = SyncBackend.COUCHDB)

        assertFalse(switched.webdavActive)
        assertTrue("credentials must survive the switch", switched.syncEnabled)
        assertEquals("https://example.com/dav", switched.serverUrl)
    }

    @Test
    fun off_silences_both_backends() {
        val off = configuredWebdav.copy(
            backend = SyncBackend.OFF,
            couchUrl = configuredCouch.couchUrl,
            couchDatabase = configuredCouch.couchDatabase,
        )

        assertFalse(off.webdavActive)
        assertFalse(off.couchActive)
        // Off is a pause, not a reset: everything needed by either backend is still there.
        assertTrue(off.syncEnabled)
        assertEquals("https://example.com/dav", off.serverUrl)
        assertEquals("http://192.168.1.10:5984", off.couchUrl)
    }

    @Test
    fun couchdb_is_active_only_when_selected_and_configured() {
        assertTrue(configuredCouch.copy(backend = SyncBackend.COUCHDB).couchActive)
        assertFalse(configuredCouch.copy(backend = SyncBackend.COUCHDB, couchUrl = "").couchActive)
        assertFalse(configuredCouch.copy(backend = SyncBackend.WEBDAV).couchActive)
    }

    /**
     * Settings persisted before the backend field existed must still mean WebDAV. Adding OFF as
     * the enum's first constant is exactly the change that would silently make it the default if
     * the field's own default were ever dropped.
     */
    @Test
    fun settings_saved_before_the_backend_field_still_mean_webdav() {
        val legacy = """{"syncEnabled":true,"serverUrl":"https://example.com/dav"}"""

        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString(SyncSettings.serializer(), legacy)

        assertEquals(SyncBackend.WEBDAV, decoded.backend)
        assertTrue(decoded.webdavActive)
    }
}
