package com.ethran.notable.recognition

import org.junit.Assert.*
import org.junit.Test

class AutomaticTitleTest {
    @Test fun meaningfulVisibleTextHasTheSameSmallGrammarAsTheOtherClients() {
        val cases = mapOf(
            "" to null, "\n# **Project plan**\nMore" to "Project plan",
            "> - [x] **Buy milk**" to "Buy milk", "1) _Next step_" to "Next step",
            "```\n[?]\n---\nMeeting notes" to "Meeting notes",
            "| --- | :---: |\n# Café 中文" to "Café 中文",
            "See [roadmap](https://example.com) now" to "See roadmap now",
            "snake_case and 2*3" to "snake_case and 2*3", "##" to null,
            "[?]" to null, "..." to null, "=" to null, "+" to null,
            "| item | kg |" to "item | kg", "# Heading ###" to "Heading",
            "> - ## Heading" to "Heading", "![Cover](url)\nActual note" to "Actual note",
            "> ![Cover](url)\nActual note" to "Actual note", "①" to null, "① Heading" to "Heading",
            "![icon](url) Actual title" to "icon Actual title",
            "![icon](url) Actual ![end](url)" to "icon Actual end",
            "![[icon]] Actual title" to "icon Actual title",
            "\\# literal" to "# literal", "See [[Roadmap|next steps]]" to "See next steps",
        )
        cases.forEach { (source, expected) -> assertEquals(source, expected, automaticTitle(source)) }
    }

    @Test fun truncationUsesUnicodeCodepointsWithoutSplittingSurrogatePairs() {
        assertEquals("😀".repeat(60) + "…", automaticTitle("😀".repeat(65)))
        assertEquals("字".repeat(60), automaticTitle("字".repeat(60)))
    }
}
