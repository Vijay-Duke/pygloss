package dev.pygloss.render

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.jetbrains.python.psi.PyFile
import dev.pygloss.cache.Profile
import dev.pygloss.cache.VerbosityLevel
import dev.pygloss.engine.BlockDetector
import dev.pygloss.model.BlockKind
import dev.pygloss.model.EnglishBlock
import dev.pygloss.model.EnglishModel

/** Headless tests for the data model that feeds the outline tree. */
class OutlineModelTest : BasePlatformTestCase() {

    fun testOutlineModelMatchesExpectedNodesAndRanges() {
        val code = outlineFixture()
        val model = detect(code)

        val nodes = OutlineModelBuilder.flatten(
            OutlineModelBuilder.build(model, readerSettings())
        )

        assertEquals(
            listOf(
                "module/class:Worker",
                "module/class:Worker/method:run",
                "module/class:Worker/method:run/with@0",
                "module/class:Worker/method:run/with@0/for@0",
                "module/class:Worker/method:run/with@0/for@0/if@0",
                "module/function:helper",
                "module/function:helper/comprehension@0"
            ),
            nodes.map { it.stableId }
        )
        assertEquals(
            listOf(
                BlockKind.CLASS,
                BlockKind.FUNCTION,
                BlockKind.WITH,
                BlockKind.FOR,
                BlockKind.IF,
                BlockKind.FUNCTION,
                BlockKind.COMPREHENSION
            ),
            nodes.map { it.kind }
        )
        assertRange(nodes[0], code.indexOf("class Worker"), code.indexOf("\n\ndef helper"))
        assertRange(nodes[1], code.indexOf("def run"), code.indexOf("\n\ndef helper"))
        assertRange(nodes[2], code.indexOf("with open"), code.indexOf("\n\ndef helper"))
        assertRange(nodes[5], code.indexOf("def helper"), code.length)
        assertEquals("class Worker", nodes[0].label)
        assertEquals("function run", nodes[1].label)
    }

    fun testCaretInsideNestedMethodResolvesToMethodBlock() {
        val code = outlineFixture()
        val model = detect(code)
        val caretOffset = code.indexOf("normalized")

        val blockId = OutlineModelBuilder.containingBlockId(model, caretOffset, VerbosityLevel.READER)

        assertEquals("module/class:Worker/method:run", blockId)
    }

    fun testPresetSwitchChangesVisibleNodesAndDetail() {
        val model = detect(outlineFixture())

        val codeNodes = OutlineModelBuilder.flatten(
            OutlineModelBuilder.build(model, readerSettings(preset = VerbosityLevel.CODE))
        )
        val readerNodes = OutlineModelBuilder.flatten(
            OutlineModelBuilder.build(model, readerSettings(preset = VerbosityLevel.READER))
        )

        assertEquals(
            listOf("module/class:Worker", "module/function:helper"),
            codeNodes.map { it.stableId }
        )
        assertTrue(codeNodes.all { it.detailText.isEmpty() })
        assertTrue(readerNodes.size > codeNodes.size)
        assertTrue(readerNodes.any { it.kind == BlockKind.COMPREHENSION })
        assertTrue(readerNodes.any { it.detailText.contains("Closest analogy:") })
    }

    fun testProfileSwitchFlipsAnalogyAndSummaryContentForSameBlock() {
        val model = withSummary(
            detect(outlineFixture()),
            "module/class:Worker/method:run/with@0",
            "Opens the output file and writes selected items."
        )

        val polyglot = OutlineModelBuilder.flatten(
            OutlineModelBuilder.build(model, readerSettings(profile = Profile.POLYGLOT_LENS))
        ).first { it.stableId == "module/class:Worker/method:run/with@0" }
        val summary = OutlineModelBuilder.flatten(
            OutlineModelBuilder.build(
                model,
                readerSettings(profile = Profile.INTENT_SUMMARY),
                isStale = { it == "module/class:Worker/method:run/with@0" }
            )
        ).first { it.stableId == "module/class:Worker/method:run/with@0" }

        assertTrue(polyglot.detailText.contains("Closest analogy:"))
        assertTrue(polyglot.detailText.contains("Caveat:"))
        assertFalse(polyglot.detailText.contains("Opens the output file"))
        assertTrue(summary.detailText.contains("[stale]"))
        assertTrue(summary.detailText.contains("Opens the output file and writes selected items."))
        assertFalse(summary.detailText.contains("Closest analogy:"))
    }

    fun testReadableHeaderDoesNotExposeSkeletonFacts() {
        assertEquals(
            "function load(self, path)",
            OutlineModelBuilder.readableHeader(
                BlockKind.FUNCTION,
                "name=load|params=self,path|async=false|children=if,for"
            )
        )
        assertEquals(
            "async function fetch(url) — decorated with @retry",
            OutlineModelBuilder.readableHeader(
                BlockKind.FUNCTION,
                "name=fetch|params=url|async=true|decorators=retry|children="
            )
        )
        assertEquals(
            "class Invoice (extends Base)",
            OutlineModelBuilder.readableHeader(
                BlockKind.CLASS,
                "name=Invoice|bases=Base|children=function"
            )
        )
    }

    fun testSummaryUnavailableMessageAppearsWhenLastBatchFailed() {
        val model = detect(outlineFixture())

        val summaryNode = OutlineModelBuilder.flatten(
            OutlineModelBuilder.build(
                model,
                readerSettings(profile = Profile.INTENT_SUMMARY),
                isSummaryUnavailable = { true }
            )
        ).first { it.kind == BlockKind.FUNCTION }

        assertTrue(summaryNode.detailText.contains("Summary unavailable"))
        assertTrue(summaryNode.detailText.contains("Settings"))
    }

    fun testOutlinePreferencesNotifyWhenPersistedValueChanges() {
        val previousProfile = OutlinePreferences.profile
        val previousTargetLanguage = OutlinePreferences.targetLanguage
        val previousPreset = OutlinePreferences.preset
        val disposable = Disposer.newDisposable()
        var notificationCount = 0

        try {
            OutlinePreferences.addListener(
                disposable,
                object : OutlinePreferences.Listener {
                    override fun preferencesChanged() {
                        notificationCount++
                    }
                }
            )
            val nextPreset = if (previousPreset == VerbosityLevel.CODE) {
                VerbosityLevel.HINTS
            } else {
                VerbosityLevel.CODE
            }

            OutlinePreferences.update(previousProfile, previousTargetLanguage, nextPreset)
            OutlinePreferences.update(previousProfile, previousTargetLanguage, nextPreset)

            assertEquals(1, notificationCount)
        } finally {
            Disposer.dispose(disposable)
            OutlinePreferences.update(previousProfile, previousTargetLanguage, previousPreset)
        }
    }

    fun testSafeSelectionRangeAcceptsCurrentDocumentBounds() {
        val range = TextRange(2, 5)

        assertSame(range, safeSelectionRange(range, documentLength = 5))
    }

    fun testSafeSelectionRangeRejectsStaleStartBeyondDocument() {
        assertNull(safeSelectionRange(TextRange(6, 7), documentLength = 5))
    }

    fun testSafeSelectionRangeRejectsStaleEndBeyondDocument() {
        assertNull(safeSelectionRange(TextRange(2, 7), documentLength = 5))
    }

    private fun detect(code: String): EnglishModel {
        val file = myFixture.configureByText("outline.py", code) as PyFile
        return BlockDetector.detect(file)
    }

    private fun readerSettings(
        profile: Profile = Profile.POLYGLOT_LENS,
        preset: VerbosityLevel = VerbosityLevel.READER
    ): OutlineSettings {
        return OutlineSettings(profile = profile, targetLanguage = "Java", preset = preset)
    }

    private fun withSummary(model: EnglishModel, stableId: String, summary: String): EnglishModel {
        fun update(blocks: List<EnglishBlock>): List<EnglishBlock> {
            return blocks.map { block ->
                val children = update(block.children)
                if (block.stableId == stableId) {
                    block.copy(summary = summary, children = children)
                } else {
                    block.copy(children = children)
                }
            }
        }
        return model.copy(blocks = update(model.blocks))
    }

    private fun assertRange(node: OutlineNode, start: Int, end: Int) {
        assertEquals("start for ${node.stableId}", start, node.range.startOffset)
        assertEquals("end for ${node.stableId}", end, node.range.endOffset)
    }

    private fun outlineFixture(): String {
        return """
            class Worker:
                def run(self, items):
                    normalized = list(items)
                    with open("out.txt") as handle:
                        for item in normalized:
                            if item:
                                handle.write(str(item))

            def helper(values):
                return [value for value in values if value]
        """.trimIndent()
    }
}
