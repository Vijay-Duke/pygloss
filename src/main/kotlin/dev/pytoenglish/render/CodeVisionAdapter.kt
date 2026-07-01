package dev.pytoenglish.render

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.TextCodeVisionEntry
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayGroup
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.python.psi.PyFile
import dev.pytoenglish.cache.Profile
import dev.pytoenglish.cache.VerbosityLevel
import dev.pytoenglish.engine.EnglishModelService
import javax.swing.JPanel
import kotlin.Pair

/** Adapter that supplies block-level summaries through the daemon-bound Code Vision API. */
class CodeVisionAdapter : DaemonBoundCodeVisionProvider, DumbAware {

    override val name: String = "py-to-english block summaries"

    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()

    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top

    override val id: String = ID

    override val groupId: String = GROUP_ID

    override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        if (file !is PyFile) return emptyList()
        val settings = readOverlaySettings()
        if (settings.preset == VerbosityLevel.CODE) return emptyList()

        val model = EnglishModelService.getInstance(file.project).getModel(file, settings.profile, settings.preset)
        return U6OverlayProjection.blockSummaries(model, settings).map { inlay ->
            Pair(
                TextRange.create(inlay.offset, inlay.offset),
                TextCodeVisionEntry(
                    inlay.text,
                    ID,
                    null,
                    inlay.text,
                    inlay.text,
                    emptyList()
                )
            )
        }
    }

    companion object {
        /** Stable Code Vision provider ID used in plugin registration. */
        const val ID: String = "dev.pytoenglish.codeVision"

        /** Code Vision settings group ID. */
        const val GROUP_ID: String = "py-to-english"

        /** Placeholder text shown while the Intent Summary model has no summary yet. */
        const val PLACEHOLDER_PENDING: String = "…"

        /** Read shared outline preferences for block summary projection. */
        fun readOverlaySettings(): InlayOverlaySettings {
            return InlayOverlaySettings(
                profile = OutlinePreferences.profile,
                targetLanguage = OutlinePreferences.targetLanguage,
                preset = OutlinePreferences.preset
            )
        }
    }
}

/** Stable block-inlay fallback for environments where Code Vision rendering is unavailable. */
class BlockInlayFallbackProvider : InlayHintsProvider<NoSettings>, DumbAware {

    override val name: String = "py-to-english block summaries"

    override val key: SettingsKey<NoSettings> = SettingsKey("dev.pytoenglish.blockSummaries")

    override val previewText: String = "def load_items():"

    override val group: InlayGroup = InlayGroup.CODE_VISION_GROUP_NEW

    override fun createSettings(): NoSettings = NoSettings()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: com.intellij.codeInsight.hints.ChangeListener) = JPanel()

            override val mainCheckboxText: String = "Show py-to-english block summaries"
        }
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ): FactoryInlayHintsCollector? {
        if (file !is PyFile) return null
        val overlaySettings = CodeVisionAdapter.readOverlaySettings()
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

        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            val offset = readPsi { element.textRange.startOffset }
            val inlays = summaries[offset].orEmpty()
            for (inlay in inlays) {
                sink.addBlockElement(
                    offset,
                    true,
                    true,
                    0,
                    factory.smallText(inlay.text)
                )
            }
            return true
        }
    }
}

private fun <T> readPsi(action: () -> T): T {
    val application = ApplicationManager.getApplication()
    return if (application.isReadAccessAllowed) action() else ReadAction.compute<T, RuntimeException> { action() }
}
