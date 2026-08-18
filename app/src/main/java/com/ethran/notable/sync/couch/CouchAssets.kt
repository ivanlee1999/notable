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
     *
     * [hashOf] is where a caller puts the memory of having read the file — `RoomCouchStore`
     * passes its mtime+size-keyed cache, because this is asked for every image on every load of
     * every page, per push *and* per apply, and re-reading unchanged pictures each time is the
     * difference between a sync and an ordeal on e-ink storage.
     */
    fun assetIdFor(
        uri: String?,
        hashOf: (File) -> String? = { CouchAssetId.sha256Hex(it) },
    ): String? {
        val file = fileFor(uri) ?: return null
        hashOf(file)?.let { return CouchDocId.asset(it) }
        return if (CouchAssetId.isSha256Hex(file.name)) CouchDocId.asset(file.name) else null
    }
}

/**
 * Which backgrounds are files, and how one relates to the asset document carrying it between
 * devices.
 *
 * A page's `background` is two different things depending on its `backgroundType`: for a native
 * template it is the template's name (`"blank"`, `"lined"`), and for a PDF or a picture it is a
 * path on whichever device wrote it. A path is the one thing that cannot travel — `/storage/.../
 * notabledb/backgrounds/pdfs/lecture.pdf` names nothing on the iPad and nothing on a second BOOX —
 * so a file-backed background travels as the `asset:<sha256>` id of its bytes, exactly as a placed
 * image does, and each device resolves that id to a path of its own.
 *
 * The id goes in the same `background` field rather than beside it, deliberately. Both apps already
 * carry that field through verbatim, so a page that passes through a build which has never heard of
 * background assets comes back with the reference intact; a new field would be dropped on the way
 * and the peer's PDF would come loose from its pages.
 *
 * The type strings are spelled out here rather than read off `BackgroundType`, which lives in the
 * app's data layer: this package is exercised by plain JVM tests and may not touch `android.*`.
 * bopa's `BackgroundKind.isFileBacked` is the twin of the rule, and must agree with it.
 */
object CouchBackgroundFiles {
    /** PDFs are told from pictures by their name — see [localPathFor]. */
    private const val PDF_EXTENSION = ".pdf"

    fun isFileBacked(backgroundType: String): Boolean =
        isPdf(backgroundType) || backgroundType.startsWith("image") || backgroundType == "coverImage"

    fun isPdf(backgroundType: String): Boolean =
        backgroundType.startsWith("pdf") || backgroundType == "autoPdf"

    /**
     * The asset document a page's background belongs to, or null when this device cannot say what
     * bytes it is showing — an external PDF it can no longer read, or a native template.
     *
     * The filename is consulted before the bytes, which is the opposite of [CouchImageFiles]. A
     * background named after a hash was written by sync and by nothing else — it is [localPathFor]'s
     * own output — so its name is as good an answer as its content, and a great deal cheaper:
     * backgrounds are the one asset that is routinely tens of megabytes and shared by every page of
     * a notebook. Only a file this device imported under the user's own name has to be read, and
     * [hashOf] is where the caller puts the memory of having done so.
     */
    fun assetIdFor(
        background: String,
        backgroundType: String,
        hashOf: (File) -> String? = { CouchAssetId.sha256Hex(it) },
    ): String? {
        if (!isFileBacked(backgroundType) || background.isEmpty()) return null
        // Already an id: a page received while storage was unreachable keeps the reference it
        // arrived with, and must go on naming the same bytes when it is pushed back.
        if (CouchAssetId.sha256HexOfAssetId(background) != null) return background
        val file = File(background)
        sha256HexOfFileName(file.name)?.let { return CouchDocId.asset(it) }
        return hashOf(file)?.let(CouchDocId::asset)
    }

    /**
     * Where this device will keep the bytes of [assetId] — the path a page points at while the
     * download is still outstanding, and the one it is drawn from once the bytes land.
     *
     * PDFs keep the extension because that is how the renderer tells a document to page through
     * from a picture to decode ([com.ethran.notable.io.loadBackgroundBitmap]). Pictures are decoded
     * by content, so a bare hash is enough for them.
     */
    fun localPathFor(assetId: String, backgroundType: String, backgroundsFolder: File): String? {
        val sha = CouchAssetId.sha256HexOfAssetId(assetId) ?: return null
        val name = if (isPdf(backgroundType)) "$sha$PDF_EXTENSION" else sha
        return File(backgroundsFolder, name).absolutePath
    }

    /**
     * Every name a downloaded background can have been given for [sha256Hex] — the reverse of
     * [localPathFor], for a lookup that has the hash but not the type that produced the name.
     */
    fun fileNamesFor(sha256Hex: String): List<String> =
        listOf(sha256Hex, "$sha256Hex$PDF_EXTENSION")

    /** The hash a downloaded background's filename encodes, or null for a name that is not one. */
    fun sha256HexOfFileName(fileName: String): String? =
        fileName.removeSuffix(PDF_EXTENSION).takeIf { CouchAssetId.isSha256Hex(it) }
}

/** Base64 of an attachment's bytes, which is how they ride inside the document. */
internal fun ByteArray.toAttachmentData(): String = Base64.getEncoder().encodeToString(this)

internal fun String.fromAttachmentData(): ByteArray? =
    try { Base64.getDecoder().decode(this) } catch (_: IllegalArgumentException) { null }
