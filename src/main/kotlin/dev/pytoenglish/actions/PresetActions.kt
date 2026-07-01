package dev.pytoenglish.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.DumbAware
import dev.pytoenglish.cache.VerbosityLevel
import dev.pytoenglish.render.OutlinePreferences

/** Base action that persists the selected py-to-english verbosity preset. */
abstract class PresetAction(
    private val preset: VerbosityLevel,
    text: String,
    description: String
) : AnAction(text, description, null), DumbAware {

    override fun actionPerformed(event: AnActionEvent) {
        OutlinePreferences.preset = preset
    }
}

/** Selects the source-first Code preset. */
class CodePresetAction : PresetAction(
    VerbosityLevel.CODE,
    "Code",
    "Show source-first py-to-english output"
)

/** Selects the light Hints preset. */
class HintsPresetAction : PresetAction(
    VerbosityLevel.HINTS,
    "Hints",
    "Show concise py-to-english hints"
)

/** Selects the structural Outline preset. */
class OutlinePresetAction : PresetAction(
    VerbosityLevel.OUTLINE,
    "Outline",
    "Show py-to-english outline details"
)

/** Selects the detailed Reader preset. */
class ReaderPresetAction : PresetAction(
    VerbosityLevel.READER,
    "Reader",
    "Show detailed py-to-english reader output"
)
