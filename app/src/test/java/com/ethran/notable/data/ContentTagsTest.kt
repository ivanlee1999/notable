package com.ethran.notable.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentTagsTest {
    @Test fun assignsKnownPhrasesAndNewHashtagsWithoutDuplicates() {
        assertEquals(listOf("Book Club", "Work", "travel"), contentTags(
            "WORK update for book\nclub. #work #travel #travel", listOf("Work", "Book Club")))
    }

    @Test fun requiresWholeNamesAndKeepsShortNamesExplicit() {
        assertEquals(listOf("AI", "a"), contentTags("Daily chair workshop. AI plan #a", listOf("AI", "Work", "a")))
        assertEquals(emptyList<String>(), contentTags("#workshop and #work-life", listOf("Work")).filter { it == "Work" })
    }

    @Test fun ignoresCodeLinksEmailsAndMarkdownHeadings() {
        assertEquals(listOf("real"), contentTags("""
            # Work heading
            ```
            #hidden work
            ```
            ~~~
            #hidden work
            ~~~
            `#inline work` https://example.com/#remote www.example.com/#remote name#mail@example.com
            ##heading #real
        """.trimIndent(), emptyList()))
    }

    @Test fun supportsUnicodeNormalizationAndNestedTags() {
        assertEquals(listOf("Café", "研究", "work/project"), contentTags(
            "Cafe\u0301 #研究 #work/project/", listOf("Café")))
    }

    @Test fun preservesKnownCanonicalSpellingAndRejectsUnboundedHashtags() {
        assertEquals(listOf("Work"), contentTags("#WORK #" + "x".repeat(65), listOf("Work")))
    }

    @Test fun usesTheSyncedAsciiIdentityForMatching() {
        assertEquals(listOf("CafÉ"), contentTags("CAFÉ", listOf("Café", "CafÉ")))
        assertEquals(listOf("z", "Áb"), contentTags("#z #Áb", listOf("Áb", "z")))
    }

    @Test(timeout = 2000) fun handlesLongUnbrokenProse() {
        assertEquals(listOf("Work"), contentTags("研".repeat(80_000) + " Work", listOf("Work")))
    }

    @Test fun keepsShorterAndInfoBearingFencesInsideCode() {
        assertEquals(listOf("real"), contentTags("````markdown\n```\n#hidden\n````example\n#hidden\n````\n#real", emptyList()))
    }

    @Test fun ignoresEscapedHashtags() {
        assertEquals(listOf("real"), contentTags("\\#literal ##heading #real", emptyList()))
    }

    @Test fun oneHashtagCannotAssignBothALiteralHashLabelAndAnOrdinaryTag() {
        assertEquals(listOf("Work"), contentTags("#Work", listOf("#Work", "Work")))
        assertEquals(emptyList<String>(), contentTags("##Work", listOf("#Work", "Work")))
    }
}
