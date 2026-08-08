package com.ethran.notable.sync

import io.shipbook.shipbooksdk.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.Date

/**
 * One `<response>` block from a PROPFIND: the resource's href plus the properties Notable reads —
 * its last-modified date and its ETag (either may be null if the server omitted it).
 */
data class DavEntry(val href: String, val lastModified: Date?, val etag: String?)

/**
 * Parsing helpers for WebDAV PROPFIND XML responses.
 *
 * Split out of [WebDAVClient], which is otherwise concerned only with issuing HTTP requests and
 * mapping status codes. Date-header parsing stays in [WebDAVClient.parseHttpDate] (it is HTTP,
 * not XML, and has a unit test pinned to that location).
 */
internal object WebDavXml {
    private const val TAG = "WebDavXml"

    /**
     * Parse href values from a WebDAV XML response.
     * Properly handles namespaces, CDATA, and whitespace.
     */
    fun parseHrefs(xml: String): List<String> {
        return try {
            val parser = newParser(xml)
            val hrefs = mutableListOf<String>()
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.lowercase() == "href") {
                    if (parser.next() == XmlPullParser.TEXT) hrefs.add(parser.text.trim())
                }
                eventType = parser.next()
            }
            hrefs
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse XML for hrefs: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse `<response>` blocks from a PROPFIND XML response, returning each resource's href with
     * its last-modified date and ETag (either null if the server omitted it). An `allprop` request
     * yields both; a narrower request yields only what it asked for.
     */
    fun parseEntries(xml: String): List<DavEntry> {
        return try {
            val parser = newParser(xml)
            val entries = mutableListOf<DavEntry>()
            var currentHref: String? = null
            var currentLastModified: Date? = null
            var currentEtag: String? = null
            var inResponse = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                        "response" -> {
                            inResponse = true
                            currentHref = null; currentLastModified = null; currentEtag = null
                        }

                        "href" -> if (inResponse && parser.next() == XmlPullParser.TEXT) currentHref =
                            parser.text.trim()

                        "getlastmodified" -> if (inResponse && parser.next() == XmlPullParser.TEXT) {
                            currentLastModified =
                                WebDAVClient.parseHttpDate(parser.text.trim())?.let { Date(it) }
                        }

                        "getetag" -> if (inResponse && parser.next() == XmlPullParser.TEXT) {
                            currentEtag = parser.text.trim().takeIf { it.isNotEmpty() }
                        }
                    }

                    XmlPullParser.END_TAG -> if (parser.name.lowercase() == "response" && inResponse) {
                        currentHref?.let {
                            entries.add(DavEntry(it, currentLastModified, currentEtag))
                        }
                        inResponse = false
                    }
                }
                eventType = parser.next()
            }
            entries
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse XML for entries", e)
            emptyList()
        }
    }

    fun isValidUuid(name: String): Boolean =
        name.length == 36 && name[8] == '-' && name[13] == '-' && name[18] == '-' && name[23] == '-'

    private fun newParser(xml: String): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newPullParser().apply { setInput(StringReader(xml)) }
    }
}
