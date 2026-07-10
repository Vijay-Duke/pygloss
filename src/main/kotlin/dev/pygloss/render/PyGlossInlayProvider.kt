package dev.pygloss.render

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.EndOfLinePosition
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
import com.jetbrains.python.psi.PyReturnStatement
import com.jetbrains.python.psi.PyTryExceptStatement
import com.jetbrains.python.psi.PyWhileStatement
import com.jetbrains.python.psi.PyWithStatement
import dev.pygloss.cache.Profile
import dev.pygloss.cache.VerbosityLevel
import dev.pygloss.engine.ConceptTable
import dev.pygloss.engine.withPsiReadAccess
import dev.pygloss.application.EnglishModelService
import dev.pygloss.model.BlockKind
import dev.pygloss.model.Concept
import dev.pygloss.model.EnglishBlock
import dev.pygloss.model.EnglishModel

/** Declarative inlay provider that renders deterministic concept callouts over Python source. */
class PyGlossInlayProvider : InlayHintsProvider, DumbAware {

    override fun createCollector(file: PsiFile, editor: Editor): SharedBypassCollector? {
        if (file !is PyFile) return null
        val settings = readOverlaySettings()
        if (settings.preset == VerbosityLevel.CODE) return null
        val showConceptCallouts = settings.profile == Profile.POLYGLOT_LENS
        val showRightLineHints = shouldShowRightLineHints(settings)
        if (!showConceptCallouts && !showRightLineHints) return null

        val conceptHintsByLine = if (showConceptCallouts) {
            val model = EnglishModelService.getInstance(file.project)
                .getModel(file, settings.profile, settings.preset)
            U6OverlayProjection.editorInlays(model, settings)
                .groupBy { editor.document.getLineNumber(it.offset) }
        } else {
            emptyMap()
        }

        return object : SharedBypassCollector {
            private val emittedRightHints = mutableSetOf<Int>()
            private val emittedConceptHintLines = mutableSetOf<Int>()

            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                if (isAnchorElement(element)) {
                    val offset = withPsiReadAccess { element.textRange.startOffset }
                    val lineNumber = editor.document.getLineNumber(offset)
                    emitConceptCalloutsForLine(lineNumber, conceptHintsByLine[lineNumber].orEmpty(), sink)
                }
                if (showRightLineHints && isRightHintElement(element)) {
                    emitRightLineHint(element, sink)
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

            private fun isRightHintElement(element: PsiElement): Boolean {
                return element is PyClass ||
                    element is PyFunction ||
                    element is PyIfStatement ||
                    element is PyForStatement ||
                    element is PyWhileStatement ||
                    element is PyWithStatement ||
                    element is PyTryExceptStatement ||
                    element is PyReturnStatement
            }

            private fun emitConceptCalloutsForLine(
                lineNumber: Int,
                inlays: List<OverlayInlay>,
                sink: InlayTreeSink
            ) {
                if (inlays.isEmpty() || !emittedConceptHintLines.add(lineNumber)) return
                val visible = inlays.take(MAX_CONCEPT_CALLOUTS_PER_LINE)
                val folded = inlays.drop(MAX_CONCEPT_CALLOUTS_PER_LINE)

                visible.forEach { inlay ->
                    sink.addPresentation(
                        position = EndOfLinePosition(lineNumber),
                        payloads = emptyList<InlayPayload>(),
                        tooltip = inlay.tooltip,
                        hintFormat = HintFormat.default
                    ) {
                        text("  ${inlay.text}")
                    }
                }

                if (folded.isNotEmpty()) {
                    sink.addPresentation(
                        position = EndOfLinePosition(lineNumber),
                        payloads = emptyList<InlayPayload>(),
                        tooltip = folded.joinToString("\n\n") { it.tooltip ?: it.text },
                        hintFormat = HintFormat.default
                    ) {
                        text("  +${folded.size} more concepts")
                    }
                }
            }

            private fun emitRightLineHint(element: PsiElement, sink: InlayTreeSink) {
                val textRange = withPsiReadAccess { element.textRange }
                val lineNumber = editor.document.getLineNumber(textRange.startOffset)
                val lineStart = editor.document.getLineStartOffset(lineNumber)
                val lineEnd = editor.document.getLineEndOffset(lineNumber)
                if (!emittedRightHints.add(lineEnd)) return

                val sourceLine = editor.document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))
                val english = EnglishViewProjector.translateLine(sourceLine).trim()
                if (english.isBlank() || english == sourceLine.trim()) return

                val fullEnglish = english.replace(Regex("""\s+"""), " ")
                val hint = EnglishViewProjector.truncateAtWordBoundary(fullEnglish, MAX_RIGHT_HINT_CHARS)
                sink.addPresentation(
                    position = EndOfLinePosition(lineNumber),
                    payloads = emptyList<InlayPayload>(),
                    tooltip = "$fullEnglish\nSource is unchanged.",
                    hintFormat = HintFormat.default
                ) {
                    text("  -> $hint")
                }
            }
        }
    }

    companion object {
        /** Stable declarative inlay provider ID used in plugin registration. */
        const val ID: String = "dev.pygloss.conceptCallouts"

        private const val MAX_RIGHT_HINT_CHARS = 96
        private const val MAX_CONCEPT_CALLOUTS_PER_LINE = 2
    }
}

internal fun shouldShowRightLineHints(settings: InlayOverlaySettings): Boolean {
    return settings.profile == Profile.INTENT_SUMMARY && settings.preset == VerbosityLevel.OUTLINE
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

    /** Return inline concept inlays. Block summaries are rendered by the block-inlay provider. */
    fun editorInlays(model: EnglishModel, settings: InlayOverlaySettings): List<OverlayInlay> {
        if (settings.preset == VerbosityLevel.CODE) return emptyList()
        return when (settings.profile) {
            Profile.POLYGLOT_LENS -> conceptCallouts(model, settings)
            Profile.INTENT_SUMMARY -> emptyList()
        }
    }

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
                .filter { isVisibleSummaryBlock(it, settings.preset) }
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
                        text = "⟶ closest analogy: ${entry.closestAnalogy}",
                        tooltip = "${concept.name.lowercase()}: closest analogy: ${entry.closestAnalogy} " +
                            "(${entry.confidenceTier.name.lowercase()} confidence).\n${entry.caveat}"
                    )
                }
            }
    }

    private fun summaryForBlock(block: EnglishBlock, profile: Profile): OverlayInlay {
        val text = when (profile) {
            Profile.POLYGLOT_LENS -> blockLabel(block)
            Profile.INTENT_SUMMARY -> block.summary ?: "summary pending: ${blockLabel(block)}"
        }
        return OverlayInlay(block.anchorOffset, text, isSummary = true)
    }

    private fun blockLabel(block: EnglishBlock): String {
        val name = block.skeleton.substringAfter("name=", "").substringBefore("|").takeIf { it.isNotBlank() }
        return listOfNotNull(block.kind.name.lowercase(), name).joinToString(": ")
    }

    private fun isVisibleSummaryBlock(block: EnglishBlock, preset: VerbosityLevel): Boolean {
        return when (preset) {
            VerbosityLevel.CODE -> false
            VerbosityLevel.HINTS -> block.kind != BlockKind.COMPREHENSION
            VerbosityLevel.OUTLINE -> block.kind != BlockKind.COMPREHENSION || block.concepts.isNotEmpty()
            VerbosityLevel.READER -> true
        }
    }

    private fun walk(block: EnglishBlock): List<EnglishBlock> {
        return listOf(block) + block.children.flatMap(::walk)
    }
}
