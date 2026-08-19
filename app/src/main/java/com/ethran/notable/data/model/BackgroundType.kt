package com.ethran.notable.data.model

import com.ethran.notable.data.AppRepository
import io.shipbook.shipbooksdk.ShipBook

private val log = ShipBook.getLogger("BackgroundType")

/**
 * Where a template chosen in the background picker should land.
 *
 * A page's background used to be the only thing the picker could touch, which made "put this
 * template on a *new* page here" a three-step errand: leave the page, add a blank one in the pages
 * view, come back, open the picker again. Every other note-taking app asks the question the other
 * way round — GoodNotes offers "Add page before / after" with a template to print on it, Supernote
 * "Insert Before / Insert After" — so the picker asks it too.
 *
 * Only the *page* picker offers the choice. A notebook's default background is a property of the
 * notebook and has no page to be before or after.
 */
enum class TemplatePlacement {
    /** Print it on the page that is open — what the picker has always done. */
    CURRENT_PAGE,

    /** Make a page carrying it immediately before the open one, and go there. */
    NEW_PAGE_BEFORE,

    /** Make a page carrying it immediately after the open one, and go there. */
    NEW_PAGE_AFTER,
}

sealed class BackgroundType(val key: String, val folderName: String) {
    data object Image : BackgroundType("image", "images")
    data object ImageRepeating : BackgroundType("imagerepeating", "images")
    data object CoverImage : BackgroundType("coverImage", "covers")
    data object Native : BackgroundType("native", "")

    // If notebook is of type AutoPdf, its consider Observable.
    // If page is of type AutoPdf, it will follow the page number in notebook.
    data object AutoPdf : BackgroundType("autoPdf", "pdfs")

    // Static page of pdf
    data class Pdf(val page: Int) : BackgroundType("pdf$page", "pdfs")

    suspend fun AutoPdf.getPage(appRepository: AppRepository, bookId: String?, pageId: String): Int? {
        if (bookId == null) return 0
        return try {
            appRepository.getPageNumber(bookId, pageId)
        } catch (e: Exception) {
            log.e("PageView.currentPageNumber: Error getting page number: ${e.message}")
            null
        }
    }
    companion object {
        fun fromKey(key: String): BackgroundType = when {
            key == Image.key -> Image
            key == ImageRepeating.key -> ImageRepeating
            key == CoverImage.key -> CoverImage
            key == Native.key -> Native
            key == AutoPdf.key -> AutoPdf
            key.startsWith("pdf") && key.removePrefix("pdf").toIntOrNull() != null -> {
                val page = key.removePrefix("pdf").toInt()
                Pdf(page)
            }

            else -> {
                log.e("BackgroundType.fromKey: Unknown key: $key")
                Native
            } // fallback
        }
    }

    fun resolveForExport(currentPage: Int?): BackgroundType =
        when (this) {
            AutoPdf ->
                if (currentPage == null) {
                    log.e("BackgroundType.resolveForExport: missing currentPage for AutoPdf")
                    Native
                } else {
                    Pdf(currentPage)
                }

            else -> this
        }
}