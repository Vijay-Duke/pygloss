package dev.pygloss.render

import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayGroup
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyComprehensionElement
import com.jetbrains.python.psi.PyDocStringOwner
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyForStatement
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyIfStatement
import com.jetbrains.python.psi.PyTryExceptStatement
import com.jetbrains.python.psi.PyWhileStatement
import com.jetbrains.python.psi.PyWithStatement
import dev.pygloss.cache.Profile
import dev.pygloss.cache.VerbosityLevel
import dev.pygloss.application.EnglishModelService
import dev.pygloss.engine.withPsiReadAccess
import javax.swing.JPanel
/** Prefix used for summaries that are not ready yet. */
private const val PENDING_PREFIX: String = "summary pending:"

/** Read shared editor preferences for block-summary projection. */
internal fun readOverlaySettings(): InlayOverlaySettings {
    return InlayOverlaySettings(
        profile = EditorPreferences.profile,
        targetLanguage = EditorPreferences.targetLanguage,
        preset = EditorPreferences.preset
    )
}

/** Block inlays for cached summaries and deterministic docstring fallbacks. */
class BlockInlayFallbackProvider : InlayHintsProvider<NoSettings>, DumbAware {

    override val name: String = "PyGloss block summaries"

    override val key: SettingsKey<NoSettings> = SettingsKey("dev.pygloss.blockSummaries")

    override val previewText: String = "def load_items():"

    override val group: InlayGroup = InlayGroup.CODE_VISION_GROUP_NEW

    override fun createSettings(): NoSettings = NoSettings()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: com.intellij.codeInsight.hints.ChangeListener) = JPanel()

            override val mainCheckboxText: String = "Show PyGloss block summaries"
        }
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ): FactoryInlayHintsCollector? {
        if (file !is PyFile) return null
        val overlaySettings = readOverlaySettings()
        if (overlaySettings.preset == VerbosityLevel.CODE) return null

        val model = EnglishModelService.getInstance(file.project).getModel(file, overlaySettings.profile, overlaySettings.preset)
        val summaries = U6OverlayProjection.blockSummaries(model, overlaySettings).groupBy { it.offset }
        return SummaryFallbackCollector(editor, summaries)
    }

    /** Collector that renders block summaries as view-only block inlays. */
    private class SummaryFallbackCollector(
        editor: Editor,
        private val summaries: Map<Int, List<OverlayInlay>>
    ) : FactoryInlayHintsCollector(editor) {
        private val emittedOffsets = mutableSetOf<Int>()

        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            if (!isAnchorElement(element)) return true
            val offset = withPsiReadAccess { element.textRange.startOffset }
            if (!emittedOffsets.add(offset)) return true
            val inlays = summaries[offset].orEmpty()
            for (inlay in inlays) {
                val text = inlayText(inlay, element) ?: continue
                wrappedLines(text).forEachIndexed { index, line ->
                    sink.addBlockElement(
                        offset,
                        true,
                        true,
                        index,
                        factory.smallText(line)
                    )
                }
            }
            return true
        }

        private fun isAnchorElement(element: PsiElement): Boolean = isBlockSummaryAnchor(element)

        private fun inlayText(inlay: OverlayInlay, element: PsiElement): String? {
            return if (inlay.text.startsWith(PENDING_PREFIX)) {
                docstringFallback(element)
            } else {
                inlay.text
            }
        }

        private fun docstringFallback(element: PsiElement): String? {
            val docText = (element as? PyDocStringOwner)?.docStringExpression?.text ?: return null
            return plainDocstring(docText)
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()
                ?.trimEnd('.')
                ?.takeIf { it.isNotBlank() }
        }

        private fun plainDocstring(text: String): String? {
            val trimmed = text.trim()
            val match = Regex("""(?s)^[rRuUbBfF]*(""" + "\"\"\"|'''|\"|'" + """)(.*)\1$""")
                .matchEntire(trimmed)
                ?: return null
            return match.groupValues[2]
        }

        private fun wrappedLines(text: String): List<String> {
            val words = text.trim().replace(Regex("""\s+"""), " ").split(" ")
            val lines = mutableListOf<String>()
            var current = StringBuilder()
            for (word in words) {
                if (current.isNotEmpty() && current.length + 1 + word.length > MAX_INLAY_LINE_CHARS) {
                    lines.add(current.toString())
                    current = StringBuilder(word)
                } else {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(word)
                }
            }
            if (current.isNotEmpty()) lines.add(current.toString())
            val wrapped = lines.ifEmpty { listOf(text) }
            if (wrapped.size <= MAX_INLAY_LINES) return wrapped

            val visible = wrapped.take(MAX_INLAY_LINES).toMutableList()
            visible[visible.lastIndex] = ellipsizeDroppedLine(visible.last())
            return visible
        }

        private fun ellipsizeDroppedLine(line: String): String {
            val marker = "…"
            if (line.endsWith(marker)) return line
            if (line.length < MAX_INLAY_LINE_CHARS) return "$line$marker"
            return EnglishViewProjector.truncateAtWordBoundary(line, MAX_INLAY_LINE_CHARS)
        }

        private companion object {
            private const val MAX_INLAY_LINE_CHARS = 92
            private const val MAX_INLAY_LINES = 4
        }
    }
}

/** Return whether [element] can anchor a semantic block summary inside the editor. */
internal fun isBlockSummaryAnchor(element: PsiElement): Boolean {
    return element is PyClass ||
        element is PyFunction ||
        element is PyIfStatement ||
        element is PyForStatement ||
        element is PyWhileStatement ||
        element is PyWithStatement ||
        element is PyTryExceptStatement ||
        element is PyComprehensionElement
}
