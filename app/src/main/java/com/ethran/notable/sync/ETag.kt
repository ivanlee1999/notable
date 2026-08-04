package com.ethran.notable.sync

/**
 * An HTTP entity tag, kept in the server's own spelling.
 *
 * The same validator reaches Notable spelled two ways — from an `ETag` response header and from a
 * PROPFIND `<getetag>` — so spelling equality cannot answer "did the content change". Canonicalizing
 * on the way in fixes that but breaks something worse: a `W/`-stripped tag re-quoted for `If-Match`
 * claims to be a **strong** validator the server never issued, and RFC 9110 §13.1.1 gives a weak tag
 * no way to satisfy strong comparison, so every guarded write to a weak-ETag server 412s.
 *
 * Normalization is therefore a comparison concern, not a storage concern: [raw] persists verbatim,
 * [matches] compares weakly, and each header is rendered for its own use — [ifMatchHeader] refuses a
 * weak tag, [ifNoneMatchHeader] passes it through.
 *
 * A bare, unquoted value (`abc`) is not valid on the wire but is accepted on the way in, because
 * stored rows may hold one; it is treated as strong.
 */
@JvmInline
value class ETag private constructor(val raw: String) {

    /** Whether the server marked this validator weak (`W/"…"`), i.e. approximate for its content. */
    val isWeak: Boolean
        get() = raw.startsWith(WEAK_PREFIX, ignoreCase = true)

    /**
     * The opaque value alone: no weak prefix, no quotes. Comparison currency, never sent as-is.
     * `internal` rather than private so [matches] can be an extension on a *nullable* receiver —
     * which is what stops call sites from reaching for `==` when one side may be absent.
     */
    internal val opaque: String
        get() = (if (isWeak) raw.substring(WEAK_PREFIX.length) else raw)
            .trim()
            .removeSurrounding("\"")

    /**
     * This tag as an `If-Match` / MOVE-`If` value, or null when it may not guard a write.
     * Always quoted, so a bare stored value is not put on the wire unquoted.
     */
    fun ifMatchHeader(): String? = if (isWeak) null else "\"$opaque\""

    /**
     * This tag as an `If-None-Match` value. Never null: a conditional read uses weak comparison, so
     * the weak spelling is sent through unchanged and the server does the right thing with it.
     */
    fun ifNoneMatchHeader(): String = if (isWeak) raw else "\"$opaque\""

    override fun toString(): String = raw

    companion object {
        private const val WEAK_PREFIX = "W/"

        /**
         * Parse a tag from a header or a stored row, keeping its spelling. Returns null for a
         * missing, blank, or empty-valued (`""`) tag — all of which mean "we have no validator",
         * which callers must handle rather than compare.
         *
         * Also null when a `"` survives removing the surrounding pair: RFC 9110 §8.8.3 excludes
         * DQUOTE from the opaque value, so an unbalanced quote is garbage that would otherwise
         * reach the wire as an unparseable `If-Match` and fail for an unrecognizable reason.
         */
        fun parse(raw: String?): ETag? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val tag = ETag(trimmed)
            val opaque = tag.opaque
            return if (opaque.isEmpty() || '"' in opaque) null else tag
        }
    }
}

/**
 * Weak comparison — the only equality any caller may use on ETags.
 *
 * `W/"abc"` and `"abc"` identify the same content, so they match. A null on **either** side never
 * matches: an absent validator is not evidence of sameness, and treating it as one is how a page
 * gets skipped that should have been fetched.
 *
 * On a nullable receiver so that the common "we may hold one, the server may have sent one" shape
 * reads without a null dance, and so `==` — which compares spellings — has a replacement everywhere.
 */
fun ETag?.matches(other: ETag?): Boolean =
    this != null && other != null && opaque == other.opaque

/**
 * Whether a state-changing request can be guarded, and if not, why.
 *
 * The negative case is named because it is reachable two ways and invisible in both: a server that
 * issues no ETags leaves every stored tag null, and a weak tag cannot guard a write. Either way the
 * page PUTs and the manifest MOVE go out unguarded and the commit degrades to last-writer-wins,
 * which is not the outcome a guarded commit has. Call sites each writing `?.let { … }` would each
 * hide that separately; one named value can be logged once.
 */
sealed interface WriteGuard {
    /** Send this as `If-Match` (or inside a MOVE `If`). */
    data class Guarded(val header: String) : WriteGuard

    /** Send no precondition; the write is last-writer-wins. */
    data class Unguarded(val reason: Reason) : WriteGuard

    enum class Reason {
        /** We hold no validator: never synced, or a server that does not issue ETags. */
        NoStoredTag,

        /** The server's validator is weak and cannot legally guard a write. */
        WeakTag,
    }
}

/** The single decision point for "is this write guarded?" — see [WriteGuard]. */
fun ETag?.writeGuard(): WriteGuard = when {
    this == null -> WriteGuard.Unguarded(WriteGuard.Reason.NoStoredTag)
    else -> ifMatchHeader()?.let { WriteGuard.Guarded(it) }
        ?: WriteGuard.Unguarded(WriteGuard.Reason.WeakTag)
}

/** Human-readable reason for the sync log, phrased as what it costs. */
fun WriteGuard.Unguarded.explain(): String = when (reason) {
    WriteGuard.Reason.NoStoredTag -> "server issued no ETag"
    WriteGuard.Reason.WeakTag -> "server's ETag is weak (W/)"
}
