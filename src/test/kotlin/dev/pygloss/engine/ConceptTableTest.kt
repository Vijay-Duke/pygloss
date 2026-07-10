package dev.pygloss.engine

import dev.pygloss.model.Concept
import org.junit.Assert.*
import org.junit.Test

/** Tests for ConceptTable deterministic Polyglot Lens data. */
class ConceptTableTest {

    @Test
    fun everyConceptHasEntryForAllFourLanguages() {
        val languages = listOf("JS", "Java", "Go", "C#")
        for (concept in Concept.entries) {
            for (lang in languages) {
                val entry = ConceptTable.lookup(concept, lang)
                assertNotNull("Missing entry for $concept/$lang", entry)
                assertTrue(
                    "Non-empty analogy for $concept/$lang",
                    entry!!.closestAnalogy.isNotBlank()
                )
                assertTrue(
                    "Non-empty caveat for $concept/$lang",
                    entry.caveat.isNotBlank()
                )
            }
        }
    }

    @Test
    fun noEntryUsesForbiddenWording() {
        for (concept in Concept.entries) {
            for (lang in listOf("JS", "Java", "Go", "C#")) {
                val entry = ConceptTable.lookup(concept, lang)!!
                val rendered = listOf(entry.closestAnalogy, entry.caveat).joinToString(" ")
                assertFalse(
                    "Entry for $concept/$lang uses forbidden wording",
                    rendered.contains("equiv", ignoreCase = true)
                )
            }
        }
    }

    @Test
    fun confidenceTierIsHighMediumOrLow() {
        for (concept in Concept.entries) {
            for (lang in listOf("JS", "Java", "Go", "C#")) {
                val entry = ConceptTable.lookup(concept, lang)!!
                assertTrue(
                    "Confidence tier must be HIGH/MEDIUM/LOW for $concept/$lang",
                    entry.confidenceTier in ConfidenceTier.entries
                )
            }
        }
    }

    @Test
    fun conceptCountMatchesModelEnum() {
        // Ensure table covers every concept from U2's enum
        assertEquals(Concept.entries.size, ConceptTable.allEntries().size)
    }
}
