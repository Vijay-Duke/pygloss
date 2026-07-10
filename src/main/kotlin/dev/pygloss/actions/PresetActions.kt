package dev.pygloss.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import dev.pygloss.cache.VerbosityLevel
import dev.pygloss.render.OutlinePreferences

/** Base action that persists the selected PyGloss verbosity preset. */
abstract class PresetAction(
    private val preset: VerbosityLevel,
    text: String,
    description: String
) : ToggleAction(text, description, null), DumbAware {

    override fun isSelected(event: AnActionEvent): Boolean {
        return OutlinePreferences.preset == preset
    }

    override fun setSelected(event: AnActionEvent, state: Boolean) {
        if (state) {
            OutlinePreferences.preset = preset
        }
    }
}

/** Selects the source-first Code preset. */
class CodePresetAction : PresetAction(
    VerbosityLevel.CODE,
    "Code",
    "Show source-first PyGloss output"
)

/** Selects the light Hints preset. */
class HintsPresetAction : PresetAction(
    VerbosityLevel.HINTS,
    "Hints",
    "Show concise PyGloss hints"
)

/** Selects the structural Outline preset. */
class OutlinePresetAction : PresetAction(
    VerbosityLevel.OUTLINE,
    "Outline",
    "Show PyGloss outline details"
)

/** Selects the detailed Reader preset. */
class ReaderPresetAction : PresetAction(
    VerbosityLevel.READER,
    "Reader",
    "Show detailed PyGloss reader output"
)
