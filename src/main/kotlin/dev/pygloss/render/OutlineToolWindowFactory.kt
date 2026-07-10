package dev.pygloss.render

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Factory that installs the PyGloss outline tool window. */
class OutlineToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val outline = OutlineToolWindow(project)
        val outlineContent = ContentFactory.getInstance().createContent(outline.component, "Outline", false)
        outlineContent.setDisposer(outline)
        toolWindow.contentManager.addContent(outlineContent)
    }
}
