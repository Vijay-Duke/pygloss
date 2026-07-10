package dev.pygloss.engine

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.psi.PyFile
import dev.pygloss.model.BlockKind
import dev.pygloss.model.EnglishBlock
import dev.pygloss.model.EnglishModel

class PsiHashTest : BasePlatformTestCase() {

    fun testWhitespaceCommentAndLiteralValueChangesAreHashStable() {
        val original = firstFunction(
            """
            def foo(x):
                # keep comments out of the semantic hash
                total = 1 + 2
                return "alpha", True, None
            """.trimIndent()
        )
        val changed = firstFunction(
            """
            def foo( x ):

                # different comment
                total   =   99   +   100
                return "beta", False, Ellipsis
            """.trimIndent()
        )

        assertEquals(original.psiHash, changed.psiHash)
    }

    fun testSignatureChangeBustsHash() {
        assertDifferentHash(
            firstFunction(
                """
                def foo(x):
                    return x
                """.trimIndent()
            ),
            firstFunction(
                """
                def foo(x, y):
                    return x + y
                """.trimIndent()
            )
        )
    }

    fun testBranchChangeBustsHash() {
        val originalIf = flatten(
            detect(
                """
                def foo(x):
                    if x > 0:
                        return x
                """.trimIndent()
            )
        ).first { it.kind == BlockKind.IF }
        val changedIf = flatten(
            detect(
                """
                def foo(x):
                    if x > 0:
                        return x
                    else:
                        return -x
                """.trimIndent()
            )
        ).first { it.kind == BlockKind.IF }

        assertDifferentHash(originalIf, changedIf)
    }

    fun testDecoratorChangeBustsHash() {
        assertDifferentHash(
            firstFunction(
                """
                def foo():
                    pass
                """.trimIndent()
            ),
            firstFunction(
                """
                @property
                def foo():
                    pass
                """.trimIndent()
            )
        )
    }

    fun testParameterRenameBustsHash() {
        assertDifferentHash(
            firstFunction(
                """
                def foo(x, y):
                    return x + y
                """.trimIndent()
            ),
            firstFunction(
                """
                def foo(a, b):
                    return a + b
                """.trimIndent()
            )
        )
    }

    private fun firstFunction(code: String): EnglishBlock {
        return flatten(detect(code)).firstOrNull { it.kind == BlockKind.FUNCTION }
            ?: throw AssertionError("No function block found in code:\n$code")
    }

    private fun detect(code: String): EnglishModel {
        val file = myFixture.configureByText("hash.py", code) as PyFile
        return BlockDetector.detect(file)
    }

    private fun flatten(model: EnglishModel): List<EnglishBlock> {
        val result = mutableListOf<EnglishBlock>()
        fun collect(blocks: List<EnglishBlock>) {
            blocks.forEach { block ->
                result.add(block)
                collect(block.children)
            }
        }
        collect(model.blocks)
        return result
    }

    private fun assertDifferentHash(first: EnglishBlock, second: EnglishBlock) {
        assertTrue("Expected different hashes for ${first.stableId}", first.psiHash != second.psiHash)
    }
}
