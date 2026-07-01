package dev.pytoenglish.render

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyComprehensionElement
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyForStatement
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyIfStatement
import com.jetbrains.python.psi.PyTryExceptStatement
import com.jetbrains.python.psi.PyWhileStatement
import com.jetbrains.python.psi.PyWithStatement
import dev.pytoenglish.cache.Profile
import dev.pytoenglish.cache.VerbosityLevel
import dev.pytoenglish.engine.ConceptTable
import dev.pytoenglish.engine.EnglishModelService
import dev.pytoenglish.model.BlockKind
import dev.pytoenglish.model.Concept
import dev.pytoenglish.model.EnglishBlock
import dev.pytoenglish.model.EnglishModel

/** Declarative inlay provider that renders deterministic concept callouts over Python source. */
class PyEnglishInlayProvider : InlayHintsProvider, DumbAware {

    override fun createCollector(file: PsiFile, editor: Editor): SharedBypassCollector? {
        if (file !is PyFile) return null
        val preset = readActivePreset()
        if (preset == VerbosityLevel.CODE) return null

        val settings = InlayOverlaySettings(
            profile = Profile.POLYGLOT_LENS,
            targetLanguage = OutlinePreferences.targetLanguage,
            preset = preset
        )
        val model = EnglishModelService.getInstance(file.project).getModel(file, settings.profile, settings.preset)
        val hintsByOffset = U6OverlayProjection.conceptCallouts(model, settings).groupBy { it.offset }

        return object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                if (!isAnchorElement(element)) return
                val inlays = hintsByOffset[element.textRange.startOffset].orEmpty()
                for (inlay in inlays) {
                    sink.addPresentation(
                        position = InlineInlayPosition(inlay.offset, relatedToPrevious = false),
                        payloads = emptyList<InlayPayload>(),
                        tooltip = inlay.tooltip,
                        hintFormat = HintFormat.default
                    ) {
                        text(inlay.text)
                    }
                }
            }

            private fun isAnchorElement(element: PsiElement): Boolean {
                return element is PyClass ||
                    element is PyFunction ||
                    element is PyIfStatement ||
                    element is PyForStatement ||
                    element is PyWhileStatement ||
                    element is PyWithStatement ||
                    element is PyTryExceptStatement ||
                    element is PyComprehensionElement
            }
        }
    }

    companion object {
        /** Stable declarative inlay provider ID used in plugin registration. */
        const val ID: String = "dev.pytoenglish.conceptCallouts"

        /** Read the active preset shared with the outline toolbar and preset actions. */
        fun readActivePreset(): VerbosityLevel = OutlinePreferences.preset
    }
}

/** Settings needed to project U6 overlay text from an [EnglishModel]. */
data class InlayOverlaySettings(
    /** Active reader profile. */
    val profile: Profile,
    /** Target language for deterministic Polyglot Lens callouts. */
    val targetLanguage: String,
    /** Active verbosity preset. */
    val preset: VerbosityLevel
)

/** A view-only overlay item with the source offset and rendered text. */
data class OverlayInlay(
    /** Source offset where the overlay is anchored. */
    val offset: Int,
    /** Rendered hint text. */
    val text: String,
    /** Optional tooltip with the lossy caveat. */
    val tooltip: String? = null,
    /** Whether this item is a block summary rather than a concept callout. */
    val isSummary: Boolean = false
)

/** Pure projection helpers shared by production renderers and headless tests. */
object U6OverlayProjection {

    /** Return concept callouts visible for [settings]. */
    fun conceptCallouts(model: EnglishModel, settings: InlayOverlaySettings): List<OverlayInlay> {
        if (settings.preset == VerbosityLevel.CODE) return emptyList()
        return model.blocks.flatMap { block ->
            walk(block).flatMap { conceptCalloutsForBlock(it, settings.targetLanguage) }
        }
    }

    /** Return one block summary line per meaningful block for [settings]. */
    fun blockSummaries(model: EnglishModel, settings: InlayOverlaySettings): List<OverlayInlay> {
        if (settings.preset == VerbosityLevel.CODE) return emptyList()
        return model.blocks.flatMap { block ->
            walk(block)
                .filter(::isMeaningfulBlock)
                .map { summaryForBlock(it, settings.profile) }
        }
    }

    private fun conceptCalloutsForBlock(block: EnglishBlock, targetLanguage: String): List<OverlayInlay> {
        return block.concepts
            .sortedBy(Concept::name)
            .mapNotNull { concept ->
                ConceptTable.lookup(concept, targetLanguage)?.let { entry ->
                    OverlayInlay(
                        offset = block.anchorOffset,
                        text = "${concept.name.lowercase()}: Closest analogy: ${entry.closestAnalogy} " +
                            "(${entry.confidenceTier.name.lowercase()} confidence)",
                        tooltip = entry.caveat
                    )
                }
            }
    }

    private fun summaryForBlock(block: EnglishBlock, profile: Profile): OverlayInlay {
        val text = when (profile) {
            Profile.POLYGLOT_LENS -> blockLabel(block)
            Profile.INTENT_SUMMARY -> block.summary ?: CodeVisionAdapter.PLACEHOLDER_PENDING
        }
        return OverlayInlay(block.anchorOffset, text, isSummary = true)
    }

    private fun blockLabel(block: EnglishBlock): String {
        val name = block.skeleton.substringAfter("name=", "").substringBefore("|").takeIf { it.isNotBlank() }
        return listOfNotNull(block.kind.name.lowercase(), name).joinToString(": ")
    }

    private fun isMeaningfulBlock(block: EnglishBlock): Boolean {
        return block.kind != BlockKind.COMPREHENSION
    }

    private fun walk(block: EnglishBlock): List<EnglishBlock> {
        return listOf(block) + block.children.flatMap(::walk)
    }
}
