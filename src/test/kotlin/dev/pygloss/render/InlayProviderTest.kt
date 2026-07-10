package dev.pygloss.render

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.psi.PyFile
import dev.pygloss.cache.Profile
import dev.pygloss.cache.VerbosityLevel
import dev.pygloss.engine.BlockDetector
import dev.pygloss.application.EnglishModelService
import dev.pygloss.model.Concept
import dev.pygloss.model.EnglishBlock
import dev.pygloss.model.EnglishModel

/** Headless tests for concept and block-inlay projection. */
class InlayProviderTest : BasePlatformTestCase() {

    fun testInlaysAtExpectedOffsetsForFixtureFile() {
        val code = inlayFixture()
        val model = detect(code)
        val inlays = conceptInlays(model)

        val asyncBlock = model.blocks.first { it.stableId == "module/function:fetch_items" }
        val asyncInlay = inlays.singleOrNull {
            it.offset == asyncBlock.anchorOffset && it.text.contains("async/await")
        }

        assertNotNull("async function should emit a JS analogy inlay", asyncInlay)
        assertEquals(code.indexOf("async def fetch_items"), asyncInlay!!.offset)
        assertTrue(asyncInlay.text.startsWith("⟶ closest analogy:"))
        val tooltip = asyncInlay.tooltip.orEmpty()
        assertTrue(tooltip.contains("async: closest analogy:"))
        assertTrue(tooltip.contains("high confidence"))
    }

    fun testInlaysAddAnnotationsWithoutAlteringDocumentText() {
        val file = myFixture.configureByText("inlay_fixture.py", inlayFixture()) as PyFile
        val before = myFixture.editor.document.text

        val model = EnglishModelService.getInstance(project)
            .getModel(file, Profile.POLYGLOT_LENS, VerbosityLevel.HINTS)
        conceptInlays(model)
        U6OverlayProjection.blockSummaries(model, summarySettings())

        assertEquals(before, myFixture.editor.document.text)
        assertEquals(before.toByteArray().toList(), myFixture.editor.document.text.toByteArray().toList())
    }

    fun testAsyncDefGetsTargetLanguageAnalogyInlayAndPlainLineGetsNone() {
        val model = detect(inlayFixture())
        val asyncBlock = model.blocks.first { it.stableId == "module/function:fetch_items" }
        val plainBlock = model.blocks.first { it.stableId == "module/function:plain_helper" }

        assertTrue(asyncBlock.concepts.contains(Concept.ASYNC))
        assertTrue(plainBlock.concepts.isEmpty())

        val javaInlays = conceptInlays(model, targetLanguage = "Java")
        assertNotNull(javaInlays.singleOrNull {
            it.offset == asyncBlock.anchorOffset && it.text.contains("CompletableFuture")
        })
        assertNull(javaInlays.singleOrNull { it.offset == plainBlock.anchorOffset })
    }

    fun testConceptCalloutTextIsShortAndTooltipCarriesCaveat() {
        val model = detect(
            """
            async def load(client, *ids, **options):
                return [await client.fetch(item_id) async for item_id in ids]
            """.trimIndent()
        )

        val inlays = conceptInlays(model)
        val asyncInlay = inlays.first { it.text.contains("async/await") }

        assertEquals("⟶ closest analogy: async/await", asyncInlay.text)
        assertTrue(asyncInlay.tooltip!!.contains("Python uses OS threads too"))
        assertTrue("Fixture should produce multiple concepts for one anchor", inlays.count { it.offset == asyncInlay.offset } > 2)
    }

    fun testPendingSummaryRendersPlaceholderThenRefreshShowsSummary() {
        val model = detect(inlayFixture())
        val asyncBlock = model.blocks.first { it.stableId == "module/function:fetch_items" }

        val pending = U6OverlayProjection.blockSummaries(model, summarySettings())
        assertNotNull(pending.singleOrNull {
            it.offset == asyncBlock.anchorOffset && it.text == "summary pending: function: fetch_items"
        })

        val filledModel = withSummary(model, asyncBlock.stableId, "Fetches items asynchronously.")
        val refreshed = U6OverlayProjection.blockSummaries(filledModel, summarySettings())
        assertNotNull(refreshed.singleOrNull {
            it.offset == asyncBlock.anchorOffset && it.text == "Fetches items asynchronously."
        })
        assertNull(refreshed.singleOrNull {
            it.offset == asyncBlock.anchorOffset && it.text.startsWith("summary pending:")
        })
    }

    fun testCodePresetSuppressesInlaysAndHintsPresetShowsThem() {
        val model = detect(inlayFixture())

        assertTrue(
            U6OverlayProjection.conceptCallouts(
                model,
                InlayOverlaySettings(Profile.POLYGLOT_LENS, "JS", VerbosityLevel.CODE)
            ).isEmpty()
        )
        assertTrue(
            U6OverlayProjection.blockSummaries(
                model,
                InlayOverlaySettings(Profile.INTENT_SUMMARY, "JS", VerbosityLevel.CODE)
            ).isEmpty()
        )

        assertTrue(conceptInlays(model, preset = VerbosityLevel.HINTS).isNotEmpty())
        assertTrue(
            U6OverlayProjection.blockSummaries(model, summarySettings(VerbosityLevel.HINTS)).isNotEmpty()
        )
    }

    fun testPlainFunctionProducesNoInlineSummaryWithoutConcepts() {
        val model = detect(
            """
            def f(*, raw):
                try:
                    return raw.strip()
                except AttributeError:
                    return ""
            """.trimIndent()
        )

        val function = model.blocks.single { it.stableId == "module/function:f" }
        assertTrue("Fixture should have no deterministic concept callouts", function.concepts.isEmpty())

        val inlays = U6OverlayProjection.editorInlays(
            model,
            InlayOverlaySettings(Profile.POLYGLOT_LENS, "JS", VerbosityLevel.HINTS)
        )

        assertNull(inlays.singleOrNull { it.offset == function.anchorOffset && it.isSummary })
    }

    fun testIntentSummaryInlineInlaysDoNotDuplicateBlockSummaryPlaceholder() {
        val model = detect("def plain(value):\n    return value\n")
        val function = model.blocks.single()

        val inlays = U6OverlayProjection.editorInlays(
            model,
            InlayOverlaySettings(Profile.INTENT_SUMMARY, "JS", VerbosityLevel.HINTS)
        )

        assertNull(inlays.singleOrNull { it.offset == function.anchorOffset && it.isSummary })
    }

    fun testReaderPresetSuppressesRightLineHintsButOutlineKeepsThem() {
        assertFalse(
            shouldShowRightLineHints(InlayOverlaySettings(Profile.INTENT_SUMMARY, "JS", VerbosityLevel.READER))
        )
        assertTrue(
            shouldShowRightLineHints(InlayOverlaySettings(Profile.INTENT_SUMMARY, "JS", VerbosityLevel.OUTLINE))
        )
        assertFalse(
            shouldShowRightLineHints(InlayOverlaySettings(Profile.POLYGLOT_LENS, "JS", VerbosityLevel.OUTLINE))
        )
    }

    private fun detect(code: String): EnglishModel {
        val file = myFixture.configureByText("inlay_fixture.py", code) as PyFile
        return BlockDetector.detect(file)
    }

    private fun conceptInlays(
        model: EnglishModel,
        targetLanguage: String = "JS",
        preset: VerbosityLevel = VerbosityLevel.HINTS
    ): List<OverlayInlay> {
        return U6OverlayProjection.conceptCallouts(
            model,
            InlayOverlaySettings(Profile.POLYGLOT_LENS, targetLanguage, preset)
        )
    }

    private fun summarySettings(preset: VerbosityLevel = VerbosityLevel.HINTS): InlayOverlaySettings {
        return InlayOverlaySettings(Profile.INTENT_SUMMARY, "JS", preset)
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

    private fun inlayFixture(): String {
        return """
            import asyncio

            async def fetch_items(source):
                return [item async for item in source]

            def plain_helper(values):
                return values
        """.trimIndent()
    }
}
