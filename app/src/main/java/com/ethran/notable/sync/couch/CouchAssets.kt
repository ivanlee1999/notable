package com.ethran.notable.sync.couch

import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Naming and sniffing for `asset:` documents, and where their bytes live on this device.
 *
 * The id *is* the content, so both apps must derive it the same way: lowercase hex SHA-256 of the
 * exact bytes, no framing and no normalization. bopa's `CouchAssets.swift` and
 * `NotableImageFiles.swift` are the twins of this file.
 *
 * Nothing here may touch `android.*`: the couch package is exercised by plain JVM unit tests.
 */
object CouchAssetId {
    /** The attachment name every asset document uses (protocol §7). */
    const val BLOB_NAME = "blob"

    private const val SHA256_HEX_LENGTH = 64
    private const val CHUNK_BYTES = 1 shl 20

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun forBytes(bytes: ByteArray): String = CouchDocId.asset(sha256Hex(bytes))

    /**
     * The hash a file's bytes carry, read in chunks so a large picture is never fully resident.
     * Null when the file cannot be read — a dangling reference, which callers treat as "content
     * unknown" rather than as an error.
     */
    fun sha256Hex(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHex()
    } catch (_: Exception) {
        null
    }

    /**
     * The 64-hex body of an `asset:` id, or null for anything that is not one. Also the check that
     * says whether a filename *is* a hash, which is how a device recovers an asset id for an image
     * whose bytes have not arrived yet.
     */
    fun sha256HexOfAssetId(documentId: String): String? {
        val (type, id) = CouchDocId.split(documentId) ?: return null
        return if (type == CouchDocType.ASSET && isSha256Hex(id)) id else null
    }

    fun isSha256Hex(text: String): Boolean =
        text.length == SHA256_HEX_LENGTH && text.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Content type from the leading magic bytes. Only the formats a page image can actually be;
     * anything else travels as opaque bytes, which still renders — both apps decode the file
     * rather than trusting the label.
     */
    fun contentTypeOf(bytes: ByteArray): String {
        fun startsWith(prefix: ByteArray, offset: Int = 0): Boolean =
            bytes.size >= offset + prefix.size &&
                prefix.indices.all { bytes[offset + it] == prefix[it] }

        return when {
            startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) -> "image/png"
            startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> "image/jpeg"
            startsWith("GIF8".toByteArray()) -> "image/gif"
            startsWith("RIFF".toByteArray()) && startsWith("WEBP".toByteArray(), 8) -> "image/webp"
            startsWith("ftypheic".toByteArray(), 4) -> "image/heic"
            startsWith("%PDF".toByteArray()) -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/**
 * Where this device keeps a placed image, and how that file relates to the asset document carrying
 * it between devices.
 *
 * One place, because two things have to agree about it: the canvas, which turns an `Image.uri`
 * into something to draw, and sync, which turns that same file into a content-addressed id and
 * back. A second copy of the rule would show up as an image that syncs but never appears.
 */
object CouchImageFiles {
    /** Resolves a stored image uri to a file, tolerating the `file:` scheme rows can carry. */
    fun fileFor(uri: String?): File? {
        if (uri.isNullOrEmpty()) return null
        val path = if (uri.startsWith("file:")) uri.removePrefix("file://").ifEmpty { null } else uri
        return path?.let(::File)
    }

    /**
     * The uri to store for an asset this device is about to hold: the hash itself, no extension.
     *
     * Naming the file after its content is what lets the asset id be recovered from the uri alone
     * (see [assetIdFor]) in the window between a page arriving and its pictures being downloaded —
     * the page has to be pushable in that window without dropping references to bytes that are
     * still on their way.
     */
    fun localUriFor(assetId: String, imagesFolder: File): String? =
        CouchAssetId.sha256HexOfAssetId(assetId)?.let { File(imagesFolder, it).absolutePath }

    /**
     * The asset document a placed image belongs to.
     *
     * The bytes decide when they are here; the filename decides when they are not yet. An image
     * whose file is missing and whose name says nothing about its content is genuinely unknown —
     * null, rather than a guess that would travel as a reference to bytes nobody has.
     */
    fun assetIdFor(uri: String?): String? {
        val file = fileFor(uri) ?: return null
        CouchAssetId.sha256Hex(file)?.let { return CouchDocId.asset(it) }
        return if (CouchAssetId.isSha256Hex(file.name)) CouchDocId.asset(file.name) else null
    }
}

/** Base64 of an attachment's bytes, which is how they ride inside the document. */
internal fun ByteArray.toAttachmentData(): String = Base64.getEncoder().encodeToString(this)

internal fun String.fromAttachmentData(): ByteArray? =
    try { Base64.getDecoder().decode(this) } catch (_: IllegalArgumentException) { null }
