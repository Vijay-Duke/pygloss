package dev.pygloss.render

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.python.psi.PyFile
import dev.pygloss.cache.VerbosityLevel

/** Central invalidation point for editor render surfaces backed by PSI/document-stamped passes. */
object PyGlossRefresher {

    private val log = Logger.getInstance(PyGlossRefresher::class.java)

    /** Cached render surfaces refreshed when PyGloss settings or generated summaries change. */
    internal fun surfacesForRefresh(): Set<RefreshSurface> = RefreshSurface.entries.toSet()

    /** Refresh all open projects after global PyGloss preferences change. */
    fun refreshAllProjects() {
        ProjectManager.getInstance().openProjects.forEach { project ->
            refreshProject(project, file = null)
        }
    }

    /** Refresh editor renderers after summaries for [file] have landed or regenerated. */
    fun refreshFile(project: Project, file: PyFile) {
        refreshProject(project, file)
    }

    private fun refreshProject(project: Project, file: PyFile?) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val virtualFile = file?.virtualFile
            restartDaemon(project, file)
            refreshOpenPythonFoldRegions(project, virtualFile, OutlinePreferences.preset)
        }
    }

    private fun restartDaemon(project: Project, file: PyFile?) {
        runRefreshStep("restart daemon") {
            val analyzer = DaemonCodeAnalyzer.getInstance(project)
            if (file == null) analyzer.restart() else analyzer.restart(file)
        }
    }

    private fun refreshOpenPythonFoldRegions(project: Project, file: VirtualFile?, preset: VerbosityLevel) {
        val foldingManager = CodeFoldingManager.getInstance(project)
        pythonEditors(project, file).forEach { editor ->
            runRefreshStep("refresh fold regions") {
                editor.foldingModel.runBatchFoldingOperation {
                    foldingManager.updateFoldRegions(editor)
                    updateEnglishFoldExpansion(editor, preset)
                }
            }
        }
    }

    private fun updateEnglishFoldExpansion(editor: Editor, preset: VerbosityLevel) {
        val foldingPreset = foldingPresetFor(preset)
        editor.foldingModel.allFoldRegions.forEach { region ->
            val groupName = region.group?.toString() ?: return@forEach
            when {
                groupName.startsWith(PY_GLOSS_STATEMENT_FOLDING_GROUP_PREFIX) ->
                    region.isExpanded = !foldingPreset.collapsesStatementFolds
                groupName.startsWith(PY_GLOSS_IDIOM_FOLDING_GROUP_PREFIX) ->
                    region.isExpanded = !foldingPreset.collapsesIdiomFolds
            }
        }
    }

    private fun pythonEditors(project: Project, file: VirtualFile?): List<Editor> {
        return FileEditorManager.getInstance(project).allEditors
            .filterIsInstance<TextEditor>()
            .map { it.editor }
            .filter { editor ->
                val editorFile = FileDocumentManager.getInstance().getFile(editor.document)
                editorFile != null &&
                    (file == null || editorFile == file) &&
                    editorFile.extension.equals("py", ignoreCase = true)
            }
    }

    private fun runRefreshStep(name: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            log.warn("PyGloss failed to $name", error)
        }
    }
}

internal enum class RefreshSurface {
    DAEMON,
    FOLD_REGIONS
}
