package com.ethran.notable.sync.couch

/** Exact source snapshots retained when text revisions meet during a merge. */
object TextVersions {
    const val KIND = "text-version"
    private const val PREFIX = "$KIND:"

    fun fingerprint(source: String): String = CouchAssetId.sha256Hex(source.toByteArray(Charsets.UTF_8))

    fun versionId(sourceId: String, text: String): String = "$PREFIX$sourceId:${fingerprint(text)}"

    fun sourceId(versionId: String): String? {
        if (!versionId.startsWith(PREFIX)) return null
        val end = versionId.lastIndexOf(':')
        if (end <= PREFIX.length) return null
        val digest = versionId.substring(end + 1)
        if (digest.length != 64 || digest.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return versionId.substring(PREFIX.length, end)
    }

    fun sameSource(a: String?, b: String?): Boolean = a == b

    fun conflicts(baseText: String?, currentText: String?, draftText: String): Boolean =
        !sameSource(currentText, baseText) && !sameSource(currentText, draftText)

    fun recovery(block: CouchBlock): CouchBlock {
        require(block.kind == "md")
        val text = requireNotNull(block.text)
        return block.copy(id = versionId(block.id, text), kind = KIND)
    }

    /**
     * Materialise both heads once different sources (or a removal) have been observed. Keeping
     * only the loser is not associative: a tombstone could hide that head before its peer arrives.
     * History is itself the marker that this source needs closure, so later equal-text revisions
     * also update the snapshot's metadata deterministically. Ordinary identical merges create none.
     *
     * Positioned and flowing markdown share this rule: surface metadata can change while a page
     * is offline, so conditioning history on the current surface would lose closure. Snapshot IDs
     * depend only on the original ID and exact source. Their metadata keeps the original LWW
     * ordering, and the caller applies the ordinary remove-wins filter to snapshot IDs too.
     */
    internal fun recoveries(a: List<CouchBlock>, b: List<CouchBlock>, removed: Set<String>): List<CouchBlock> {
        val observed = a + b
        val history = observed.filter { it.kind == KIND }.mapNotNull { sourceId(it.id) }.toSet() +
            removed.mapNotNull(::sourceId)
        return observed.filter { it.kind != KIND }.groupBy { it.id }.flatMap { (id, heads) ->
            val markdown = heads.any { it.kind == "md" && it.text != null }
            val distinct = heads.map { it.text?.let(::fingerprint) }.distinct().size > 1
            val crossing = heads.any { it.kind != "md" }
            if (id in history || (markdown && (id in removed || distinct || crossing))) {
                heads.filter { it.kind == "md" && it.text != null }.map(::recovery)
            } else emptyList()
        }
    }
}
