package dev.pygloss.render

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.psi.PyStatement

class PsiStatementEnglishTest : BasePlatformTestCase() {
    fun testDescribesMethodCallStatement() {
        assertStatementDescription(
            "ax.add_patch(box)",
            "call ax's add patch with box"
        )
    }

    fun testDescribesKeywordOptionsAfterPositionals() {
        assertStatementDescription(
            "ax.text(0.5, 0.9, 'Institutional Equity Research', fontsize=28, fontweight='bold', ha='center', va='center')",
            "call ax's text with 0.5, 0.9, 'Institutional Equity Research' — options: fontsize 28, fontweight 'bold', ha 'center', va 'center'"
        )
    }

    fun testSummarizesLargeListAssignment() {
        assertStatementDescription(
            "metrics = [('Current Price', 'A$0.052', NAVY_BLUE), ('Market Cap', '~A$20M', NAVY_BLUE), ('Risk', 'HIGH', RED)]",
            "set metrics to a list of 3 entries: ('Current Price', 'A$0.052', NAVY_BLUE), +2 more"
        )
    }

    fun testDescribesSmallListAssignment() {
        assertStatementDescription(
            "pair = [a, b]",
            "set pair to [a, b]"
        )
    }

    fun testDescribesSmallTupleAssignment() {
        assertStatementDescription(
            "point = (1, 2)",
            "set point to (1, 2)"
        )
    }

    fun testDescribesSmallDictAssignment() {
        assertStatementDescription(
            "d = {'a': 1}",
            "set d to {'a': 1}"
        )
    }

    fun testDescribesSimpleAssignment() {
        assertStatementDescription(
            "y_pos = 0.55",
            "set y_pos to 0.55"
        )
    }

    fun testSharedStatementPhrasesMatchTextProjector() {
        listOf(
            "total = price * quantity",
            "total += tax",
            "total *= 2",
            "return total",
        ).forEach { source ->
            assertStatementDescription(source, EnglishViewProjector.translateLine(source))
        }
    }

    private fun assertStatementDescription(source: String, expected: String) {
        myFixture.configureByText("reader.py", "$source\n")
        val statement = PsiTreeUtil.collectElementsOfType(myFixture.file, PyStatement::class.java).single()

        assertEquals(expected, PsiStatementEnglish.describe(statement))
    }
}
