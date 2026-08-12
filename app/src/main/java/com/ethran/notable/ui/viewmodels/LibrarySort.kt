package com.ethran.notable.ui.viewmodels

import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import java.text.Collator
import java.util.Locale

/**
 * How the library is ordered.
 *
 * The order used to be fixed — `uiState.books.reversed()`, i.e. database insertion order backwards
 * — which is neither "recent" nor "alphabetical" but an artefact. In a library of eighty covers
 * that means reading all eighty.
 */
enum class LibrarySortOrder(val label: String) {
    UPDATED("Last edited"),
    CREATED("Date created"),
    TITLE("Title");

    companion object {
        fun fromKeyOrDefault(key: String?): LibrarySortOrder =
            entries.firstOrNull { it.name == key } ?: UPDATED
    }
}

/**
 * Ordering and filtering the library, kept out of the composables so the rules are testable on the
 * JVM and so the folder list and the notebook grid answer the same way.
 *
 * The twin of bopa's `LibrarySort`.
 */
object LibrarySort {

    /**
     * Locale-aware, so accented titles file where the reader expects rather than by code point,
     * and so the order does not change with the platform's default locale mid-session.
     */
    private val collator: Collator = Collator.getInstance(Locale.getDefault()).apply {
        strength = Collator.SECONDARY
    }

    fun notebooks(
        books: List<Notebook>, order: LibrarySortOrder, descending: Boolean
    ): List<Notebook> {
        val sorted = when (order) {
            LibrarySortOrder.UPDATED -> books.sortedWith(
                compareBy<Notebook> { it.updatedAt }.thenBy { it.id })
            LibrarySortOrder.CREATED -> books.sortedWith(
                compareBy<Notebook> { it.createdAt }.thenBy { it.id })
            // The id breaks ties so two notebooks with the same name do not swap places between
            // launches.
            LibrarySortOrder.TITLE ->
                books.sortedWith(byTitle<Notebook>({ it.title }, { it.id }))
        }
        return if (descending) sorted.reversed() else sorted
    }

    fun folders(
        folders: List<Folder>, order: LibrarySortOrder, descending: Boolean
    ): List<Folder> {
        val sorted = when (order) {
            LibrarySortOrder.UPDATED -> folders.sortedWith(
                compareBy<Folder> { it.updatedAt }.thenBy { it.id })
            LibrarySortOrder.CREATED -> folders.sortedWith(
                compareBy<Folder> { it.createdAt }.thenBy { it.id })
            LibrarySortOrder.TITLE ->
                folders.sortedWith(byTitle<Folder>({ it.title }, { it.id }))
        }
        return if (descending) sorted.reversed() else sorted
    }

    /**
     * Orders by title through the collator, breaking ties on the id.
     *
     * Spelled out rather than `compareBy(collator) { … }`: [Collator] implements the raw
     * `Comparator<Any>`, so there is nothing for the key type to be inferred from. The tie-break
     * is what keeps two notebooks with the same name from swapping places between launches.
     */
    private fun <T> byTitle(title: (T) -> String, id: (T) -> String) = Comparator<T> { a, b ->
        val byName = collator.compare(title(a), title(b))
        if (byName != 0) byName else id(a).compareTo(id(b))
    }

    /**
     * Whether [title] answers [query].
     *
     * Case- and diacritic-insensitive substring matching, not prefix: people search for the word
     * they remember from the middle of a title as readily as for how it starts. Handwriting is not
     * searched — that needs recognition, which is a later and much larger thing — so this is
     * deliberately "search by name", and an empty query is the absence of a filter rather than a
     * filter that matches nothing.
     */
    fun matches(title: String, query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        return fold(title).contains(fold(trimmed))
    }

    /**
     * Casing and accents removed, so "Résumé" answers "resume".
     *
     * Decomposed first, then the combining marks dropped: lowercasing alone leaves the accent on,
     * and stripping bytes without decomposing would leave "é" (a single code point) untouched.
     */
    private fun fold(value: String): String =
        java.text.Normalizer.normalize(value.lowercase(Locale.getDefault()), java.text.Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
}
