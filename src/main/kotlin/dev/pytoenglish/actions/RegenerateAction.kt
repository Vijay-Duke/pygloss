package dev.pytoenglish.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAware
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.psi.PsiDocumentManager
import com.jetbrains.python.psi.PyFile
import dev.pytoenglish.cache.Profile
import dev.pytoenglish.cache.VerbosityLevel
import dev.pytoenglish.engine.EnglishModelService
import dev.pytoenglish.model.EnglishBlock

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

        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val offset = editor.caretModel.offset
        val service = EnglishModelService.getInstance(project)

        service.createConfiguredAdapterAsync { adapter ->
            ReadAction
                .nonBlocking<Pair<dev.pytoenglish.model.EnglishModel, EnglishBlock?>?> {
                    val model = service.getModel(file, Profile.INTENT_SUMMARY, VerbosityLevel.HINTS)
                    model to findBlockAtOffset(model.blocks, offset)
                }
                .expireWith(project)
                .submit(AppExecutorUtil.getAppExecutorService())
                .onSuccess { result ->
                    val (model, block) = result ?: return@onSuccess
                    if (block != null) {
                        service.regenerate(file, model, block, adapter)
                    }
                }
        }
    }

    private fun findBlockAtOffset(blocks: List<EnglishBlock>, offset: Int): EnglishBlock? {
        for (block in blocks) {
            if (block.textRange.contains(offset)) {
                return findBlockAtOffset(block.children, offset) ?: block
            }
        }
        return null
    }
}
