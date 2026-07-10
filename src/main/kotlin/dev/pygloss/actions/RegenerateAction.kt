package dev.pygloss.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.jetbrains.python.psi.PyFile
import dev.pygloss.application.EnglishModelService

/** Clears the current block's Intent Summary cache entry and requests a fresh summary. */
class RegenerateAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.PSI_FILE)
        event.presentation.isEnabledAndVisible = file is PyFile && event.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val file = event.getData(CommonDataKeys.PSI_FILE) as? PyFile ?: return

        EnglishModelService.getInstance(project).regenerateAtOffset(file, editor.caretModel.offset)
    }
}
