package dev.pytoenglish.render

import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Tests for deterministic folding of tiny Python idioms. */
class FoldingBuilderTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    fun testComprehensionYieldsExpectedPlaceholderAndLeavesDocumentUnchanged() {
        myFixture.configureByFile("folding/comprehension.py")
        val before = myFixture.editor.document.text

        val descriptors = buildFolding()

        assertEquals(before, myFixture.editor.document.text)
        assertEquals(1, descriptors.size)
        assertEquals("build collection by looping and filtering", descriptors.single().placeholderText)
    }

    fun testPlainFunctionBodyProducesNoFoldRegion() {
        myFixture.configureByFile("folding/plain_function.py")

        val descriptors = buildFolding()

        assertTrue("Plain multi-statement function should not fold", descriptors.isEmpty())
    }

    fun testNestedComprehensionFoldsAtOuterElementOnly() {
        myFixture.configureByFile("folding/nested_comprehension.py")

        val descriptors = buildFolding()

        assertEquals(1, descriptors.size)
        assertNoOverlappingRegions(descriptors)
    }

    fun testCodePresetProducesNoEnglishFoldRegions() {
        myFixture.configureByFile("folding/comprehension.py")

        val descriptors = buildFolding(FoldingPreset.CODE)

        assertTrue("Code preset should hide English fold placeholders", descriptors.isEmpty())
    }

    fun testHintsPresetProducesFoldRegionsWithoutDefaultCollapse() {
        myFixture.configureByFile("folding/comprehension.py")
        val builder = PyEnglishFoldingBuilder { FoldingPreset.HINTS }

        val descriptors = builder.buildFoldRegions(myFixture.file, myFixture.editor.document, false)

        assertEquals(1, descriptors.size)
        assertFalse(builder.isCollapsedByDefault(descriptors.single().element))
    }

    private fun buildFolding(preset: FoldingPreset = FoldingPreset.HINTS): Array<FoldingDescriptor> {
        val builder = PyEnglishFoldingBuilder { preset }
        return builder.buildFoldRegions(myFixture.file, myFixture.editor.document, false)
    }

    private fun assertNoOverlappingRegions(descriptors: Array<FoldingDescriptor>) {
        val ranges = descriptors.map { it.range }
        ranges.forEachIndexed { index, range ->
            ranges.drop(index + 1).forEach { other ->
                assertFalse("${range.debug()} overlaps ${other.debug()}", range.intersects(other))
            }
        }
    }

    private fun TextRange.debug(): String = "(${startOffset},$endOffset)"
}
