package com.ethran.notable.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Parser coverage for the ETag parsed by [WebDavXml.parseEntries]. Instrumented because
 * `XmlPullParser` needs the Android runtime (no Robolectric in the JVM test source set).
 */
@RunWith(AndroidJUnit4::class)
class WebDavXmlAndroidTest {

    // A representative Depth-1 `allprop` PROPFIND response for a pages directory: the collection
    // itself, then two page files each with a getetag and getlastmodified. The third page omits the
    // getetag to exercise the null path.
    private val pagesPropfind = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/notable/notebooks/nb1/pages/</D:href>
            <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
              <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
          <D:response>
            <D:href>/notable/notebooks/nb1/pages/page-a.json</D:href>
            <D:propstat><D:prop>
              <D:getlastmodified>Tue, 15 Nov 1994 08:12:31 GMT</D:getlastmodified>
              <D:getetag>"aaa-111"</D:getetag>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
          <D:response>
            <D:href>/notable/notebooks/nb1/pages/page-b.json</D:href>
            <D:propstat><D:prop>
              <D:getlastmodified>Tue, 15 Nov 1994 09:00:00 GMT</D:getlastmodified>
              <D:getetag>W/"bbb-222"</D:getetag>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
          <D:response>
            <D:href>/notable/notebooks/nb1/pages/page-c.json</D:href>
            <D:propstat><D:prop>
              <D:getlastmodified>Tue, 15 Nov 1994 10:00:00 GMT</D:getlastmodified>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    @Test
    fun parseEntries_capturesEtagPerResponse() {
        val byHref = WebDavXml.parseEntries(pagesPropfind).associateBy { it.href }

        assertEquals(4, byHref.size)
        assertEquals("\"aaa-111\"", byHref["/notable/notebooks/nb1/pages/page-a.json"]?.etag)
        // Weak validators are preserved verbatim (equality comparison only).
        assertEquals("W/\"bbb-222\"", byHref["/notable/notebooks/nb1/pages/page-b.json"]?.etag)
        // A response with no getetag yields a null etag, not an empty string.
        assertNull(byHref["/notable/notebooks/nb1/pages/page-c.json"]?.etag)
        // The collection itself carries neither.
        assertNull(byHref["/notable/notebooks/nb1/pages/"]?.etag)
    }

    @Test
    fun parseEntries_stillCapturesLastModified() {
        val pageA = WebDavXml.parseEntries(pagesPropfind)
            .first { it.href.endsWith("page-a.json") }
        assertEquals(WebDAVClient.parseHttpDate("Tue, 15 Nov 1994 08:12:31 GMT"), pageA.lastModified?.time)
    }
}
