package dev.pytoenglish.render

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Factory that installs the py-to-english outline tool window. */
class OutlineToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val outline = OutlineToolWindow(project)
        val content = ContentFactory.getInstance().createContent(outline.component, "Outline", false)
        content.setDisposer(outline)
        toolWindow.contentManager.addContent(content)
    }
}
