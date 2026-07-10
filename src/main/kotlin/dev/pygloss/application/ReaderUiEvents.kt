package dev.pygloss.application

import com.intellij.util.messages.Topic
import com.intellij.openapi.project.Project
import com.jetbrains.python.psi.PyFile
import dev.pygloss.llm.LlmResult

/** UI-facing events emitted by background reader workflows. */
interface ReaderUiEvents {
    fun summaryStarted(project: Project) = Unit

    fun summaryFinished(project: Project) = Unit

    fun summaryFailed(project: Project, error: LlmResult) = Unit

    fun summarySucceeded(project: Project) = Unit

    fun refreshFile(project: Project, file: PyFile) = Unit

    companion object {
        @JvmField
        val TOPIC: Topic<ReaderUiEvents> = Topic.create("PyGloss reader UI events", ReaderUiEvents::class.java)
    }
}
