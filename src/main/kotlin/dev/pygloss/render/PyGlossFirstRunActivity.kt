package dev.pygloss.render

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager

/** Shows a one-time introduction when the user first opens a Python file. */
class PyGlossFirstRunActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val properties = ApplicationManager.getApplication().getService(PropertiesComponent::class.java)
        if (properties.getBoolean(FIRST_RUN_SEEN_KEY, false)) return

        val connection = project.messageBus.connect(project)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    if (showIfNeeded(project, file)) connection.disconnect()
                }
            }
        )
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFiles.firstOrNull(::isPythonFile)?.let { file ->
                if (showIfNeeded(project, file)) connection.disconnect()
            }
        }
    }

    private fun showIfNeeded(project: Project, file: VirtualFile): Boolean {
        if (project.isDisposed || !isPythonFile(file)) return false

        val properties = ApplicationManager.getApplication().getService(PropertiesComponent::class.java)
        if (properties.getBoolean(FIRST_RUN_SEEN_KEY, false)) return false
        properties.setValue(FIRST_RUN_SEEN_KEY, true)

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(IntentSummaryStatusService.NOTIFICATION_GROUP_ID)
            .createNotification(
                "PyGloss is active — switch to Reader view (Tools → PyGloss View → Reader) " +
                    "to read this file as English, right in the editor. Click any folded line to see the original " +
                    "Python. Your files are never modified.",
                NotificationType.INFORMATION
            )
        notification.addAction(
            object : NotificationAction("Open tool window") {
                override fun actionPerformed(event: AnActionEvent, notification: Notification) {
                    ToolWindowManager.getInstance(project).getToolWindow("PyGloss")?.show()
                    notification.expire()
                }
            }
        )
        notification.addAction(
            object : NotificationAction("Don't show again") {
                override fun actionPerformed(event: AnActionEvent, notification: Notification) {
                    notification.expire()
                }
            }
        )
        notification.notify(project)
        return true
    }

    private fun isPythonFile(file: VirtualFile): Boolean {
        return file.extension.equals("py", ignoreCase = true) ||
            file.fileType.name.equals("Python", ignoreCase = true)
    }

    private companion object {
        private const val FIRST_RUN_SEEN_KEY = "dev.pygloss.firstRunPythonFileSeen"
    }
}
