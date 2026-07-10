package dev.pygloss.render

import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.jetbrains.python.psi.PyFile
import dev.pygloss.cache.VerbosityLevel
import dev.pygloss.application.LineTranslationRequester
import dev.pygloss.settings.ExplainStyle
import dev.pygloss.settings.PyGlossProjectSettings

/** Tests for deterministic folding of tiny Python idioms. */
class FoldingBuilderTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun tearDown() {
        try {
            EditorPreferences.preset = VerbosityLevel.HINTS
            PyGlossProjectSettings.getInstance(project).update("", ExplainStyle.PLAIN, false)
        } finally {
            super.tearDown()
        }
    }

    fun testComprehensionYieldsExpectedPlaceholderAndLeavesDocumentUnchanged() {
        myFixture.configureByFile("folding/comprehension.py")
        val before = myFixture.editor.document.text

        val descriptors = buildFolding()

        assertEquals(before, myFixture.editor.document.text)
        assertEquals(1, descriptors.size)
        assertEquals(
            "the list of x × 2, for every x in items where x is over 0",
            descriptors.single().placeholderText
        )
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

    fun testProductionBuilderReadsSharedPresetPreference() {
        myFixture.configureByFile("folding/comprehension.py")
        EditorPreferences.preset = VerbosityLevel.CODE

        val descriptors = PyGlossFoldingBuilder().buildFoldRegions(myFixture.file, myFixture.editor.document, false)

        assertTrue("Production builder should honor the shared Code preset", descriptors.isEmpty())
    }

    fun testHintsPresetProducesFoldRegionsWithoutDefaultCollapse() {
        myFixture.configureByFile("folding/comprehension.py")
        val builder = PyGlossFoldingBuilder(presetProvider = { FoldingPreset.HINTS })

        val descriptors = builder.buildFoldRegions(myFixture.file, myFixture.editor.document, false)

        assertEquals(1, descriptors.size)
        assertFalse(builder.isCollapsedByDefault(descriptors.single().element))
    }

    fun testReaderPresetFoldsAugAssignmentStatementByDefault() {
        myFixture.configureByText("reader.py", "readings_skipped += 1\n")
        val builder = PyGlossFoldingBuilder(presetProvider = { FoldingPreset.READER })

        val descriptors = builder.buildFoldRegions(myFixture.file, myFixture.editor.document, false)

        assertEquals(1, descriptors.size)
        assertEquals("increase readings_skipped by 1", descriptors.single().placeholderText)
        assertTrue(builder.isCollapsedByDefault(descriptors.single().element))
    }

    fun testReaderPresetUsesDistinctStatementFoldingGroups() {
        myFixture.configureByText(
            "reader.py",
            """
            readings_skipped += 1
            approved.append(item)
            """.trimIndent()
        )

        val descriptors = buildFolding(FoldingPreset.READER)
        val groups = descriptors.map { checkNotNull(it.group) }

        assertEquals(2, descriptors.size)
        assertNotSame(groups[0], groups[1])
        assertFalse(groups[0] == groups[1])
        groups.forEach { group ->
            assertTrue(group.toString().startsWith(PY_GLOSS_STATEMENT_FOLDING_GROUP_PREFIX))
        }
    }

    fun testIdiomFoldUsesIdiomFoldingGroupPrefix() {
        myFixture.configureByFile("folding/comprehension.py")

        val descriptors = buildFolding(FoldingPreset.OUTLINE)
        val group = checkNotNull(descriptors.single().group)

        assertTrue(group.toString().startsWith(PY_GLOSS_IDIOM_FOLDING_GROUP_PREFIX))
    }

    fun testReaderPresetStatementFoldWinsOverNestedComprehension() {
        myFixture.configureByText(
            "checks.py",
            """
            def values(rows):
                return [row.value for row in rows if row.value > 0]
            """.trimIndent()
        )

        val descriptors = buildFolding(FoldingPreset.READER)

        assertEquals(1, descriptors.size)
        assertEquals(
            "give back the list of row's value, for every row in rows where row's value is over 0",
            descriptors.single().placeholderText
        )
    }

    fun testReaderPresetFoldsCompoundHeadersAndVisibleBodyStatements() {
        myFixture.configureByText(
            "reader.py",
            """
            with transaction.atomic():
                readings_skipped += 1
                approved.append(item)
            if ready:
                pass
            else:
                pass
            """.trimIndent()
        )

        val placeholders = buildFolding(FoldingPreset.READER).map { it.placeholderText }

        assertTrue(placeholders.contains("using transaction's atomic():"))
        assertTrue(placeholders.contains("increase readings_skipped by 1"))
        assertTrue(placeholders.contains("call approved's append with item"))
        assertTrue(placeholders.contains("otherwise:"))
    }

    fun testReaderPresetSkipsUntranslatableExpressionStatement() {
        myFixture.configureByText("reader.py", "configure(x)(y)[z]\n")

        val descriptors = buildFolding(FoldingPreset.READER)

        assertTrue(descriptors.isEmpty())
    }

    fun testReaderIfHeaderWinsOverBooleanExpressionFold() {
        myFixture.configureByText(
            "reader.py",
            """
            if inv.total > threshold and not inv.flagged:
                configure(x)(y)[z]
            """.trimIndent()
        )

        val descriptors = buildFolding(FoldingPreset.READER)

        assertEquals(1, descriptors.size)
        assertEquals(
            "if inv's total is over threshold and inv is not flagged:",
            descriptors.single().placeholderText
        )
    }

    fun testHintsAndOutlinePresetsDoNotProduceStatementFolds() {
        myFixture.configureByText("reader.py", "readings_skipped += 1\n")

        assertTrue(buildFolding(FoldingPreset.HINTS).isEmpty())
        assertTrue(buildFolding(FoldingPreset.OUTLINE).isEmpty())
    }

    fun testReaderPresetFlattensMultilineAssignmentWhenTranslatable() {
        myFixture.configureByText(
            "reader.py",
            """
            result = client.fetch(
                account_id,
            )
            configure(x)(y)[z]
            """.trimIndent()
        )

        val placeholders = buildFolding(FoldingPreset.READER).map { it.placeholderText }

        assertTrue(placeholders.contains("set result to client's fetch(account_id)"))
        assertFalse(placeholders.contains("configure(x)(y)[z]"))
    }

    fun testReaderPresetDescribesKwargsCallAsOptions() {
        myFixture.configureByText(
            "reader.py",
            "ax.text(0.5, 0.9, 'Institutional Equity Research', fontsize=28, fontweight='bold', ha='center', va='center')\n"
        )

        val placeholder = checkNotNull(buildFolding(FoldingPreset.READER).single().placeholderText)

        assertTrue(placeholder.contains("— options:"))
        assertFalse(placeholder.contains("fontsize to"))
    }

    fun testReaderPresetFoldsMethodCallStatement() {
        myFixture.configureByText("reader.py", "ax.add_patch(box)\n")

        val descriptors = buildFolding(FoldingPreset.READER)

        assertEquals(1, descriptors.size)
        assertEquals("call ax's add patch with box", descriptors.single().placeholderText)
    }

    fun testReaderLineTranslationUsesCachedLlmTextWhenFlagOn() {
        PyGlossProjectSettings.getInstance(project).update("", ExplainStyle.PLAIN, true)
        myFixture.configureByText("reader.py", "ax.set_xlim(0, 10)\n")
        val service = RecordingLineTranslations(
            mapOf("ax.set_xlim(0, 10)" to "limit the chart's horizontal axis to 0 to 10")
        )

        val descriptors = buildFolding(FoldingPreset.READER, service)

        assertEquals(1, descriptors.size)
        assertEquals("limit the chart's horizontal axis to 0 to 10", descriptors.single().placeholderText)
        assertTrue(service.requested.isEmpty())
    }

    fun testReaderLineTranslationFlagOffKeepsDeterministicPlaceholder() {
        PyGlossProjectSettings.getInstance(project).update("", ExplainStyle.PLAIN, false)
        myFixture.configureByText("reader.py", "ax.set_xlim(0, 10)\n")
        val service = RecordingLineTranslations(
            mapOf("ax.set_xlim(0, 10)" to "limit the chart's horizontal axis to 0 to 10")
        )

        val descriptors = buildFolding(FoldingPreset.READER, service)

        assertEquals(1, descriptors.size)
        assertEquals("call ax's set xlim with 0, 10", descriptors.single().placeholderText)
        assertTrue(service.requested.isEmpty())
    }

    fun testReaderLineTranslationCacheMissKeepsDeterministicPlaceholderAndRequestsMissing() {
        PyGlossProjectSettings.getInstance(project).update("", ExplainStyle.PLAIN, true)
        myFixture.configureByText("reader.py", "ax.set_xlim(0, 10)\n")
        val service = RecordingLineTranslations(emptyMap())

        val descriptors = buildFolding(FoldingPreset.READER, service)
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(1, descriptors.size)
        assertEquals("call ax's set xlim with 0, 10", descriptors.single().placeholderText)
        assertEquals(listOf("ax.set_xlim(0, 10)"), service.requested)
    }

    fun testDictComprehensionUsesMappingPlaceholder() {
        myFixture.configureByText(
            "checks.py",
            """
            def values(items):
                return {k: v for k, v in items.items()}
            """.trimIndent()
        )

        val descriptors = buildFolding()

        assertEquals(1, descriptors.size)
        assertEquals(
            "the mapping of k to v, for every k, v in key-value pairs from items",
            descriptors.single().placeholderText
        )
    }

    fun testGeneratorExpressionFallsBackToPlainPlaceholder() {
        myFixture.configureByText(
            "checks.py",
            """
            def values(pairs):
                return sum(x * y for x, y in pairs)
            """.trimIndent()
        )

        val descriptors = buildFolding()

        assertEquals(1, descriptors.size)
        assertEquals("build collection by looping", descriptors.single().placeholderText)
    }

    fun testBooleanFoldUsesTranslatedConditionAndIgnoresStringLiteralText() {
        myFixture.configureByText(
            "checks.py",
            """
            message = "salt and pepper"
            approved = inv.total > threshold and not inv.flagged
            """.trimIndent()
        )

        val descriptors = buildFolding()

        assertEquals(1, descriptors.size)
        assertEquals("inv's total is over threshold and inv is not flagged", descriptors.single().placeholderText)
    }

    fun testSingleReturnGuardPlaceholderShowsConditionAndReturnValue() {
        myFixture.configureByText(
            "guard.py",
            """
            def status(inv, threshold):
                if inv.total > threshold and not inv.flagged:
                    return inv.status
                return "skip"
            """.trimIndent()
        )

        val descriptors = buildFolding()

        assertEquals(1, descriptors.size)
        assertEquals(
            "if inv's total is over threshold and inv is not flagged: give back inv's status",
            descriptors.single().placeholderText
        )
    }

    private fun buildFolding(
        preset: FoldingPreset = FoldingPreset.HINTS,
        lineTranslations: LineTranslationRequester? = null
    ): Array<FoldingDescriptor> {
        val builder = if (lineTranslations == null) {
            PyGlossFoldingBuilder(presetProvider = { preset })
        } else {
            PyGlossFoldingBuilder(
                presetProvider = { preset },
                lineTranslationProvider = { lineTranslations }
            )
        }
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

    private class RecordingLineTranslations(
        private val translations: Map<String, String>
    ) : LineTranslationRequester {
        val requested = mutableListOf<String>()

        override fun readTranslation(statementText: String): String? {
            return translations[statementText]
        }

        override fun requestMissing(file: PyFile, statements: List<String>) {
            requested.addAll(statements)
        }
    }
}
