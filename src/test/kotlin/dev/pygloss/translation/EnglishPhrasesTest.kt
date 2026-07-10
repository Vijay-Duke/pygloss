package dev.pygloss.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishPhrasesTest {
    @Test
    fun `renders shared statement wording`() {
        assertEquals("set total to price × quantity", EnglishPhrases.assignment("total", "price × quantity"))
        assertEquals("increase total by tax", EnglishPhrases.augmentedAssignment("total", "+=", "tax"))
        assertEquals("set total to total // 2", EnglishPhrases.augmentedAssignment("total", "//=", "2"))
        assertEquals("give back total", EnglishPhrases.returned("total"))
        assertEquals("give back", EnglishPhrases.returned(null))
        assertEquals(
            "call print with message — options: flush true",
            EnglishPhrases.call(
                EnglishPhrases.CallPhrase(
                    target = "print",
                    positionals = listOf("message"),
                    options = listOf(EnglishPhrases.CallOption("flush", "true")),
                )
            )
        )
    }

    @Test
    fun `renders shared expression wording`() {
        assertEquals("invoice's total", EnglishPhrases.attribute("invoice", "total"))
        assertEquals("invoice's total is over limit", EnglishPhrases.binary("invoice's total", ">", "limit"))
    }
}
