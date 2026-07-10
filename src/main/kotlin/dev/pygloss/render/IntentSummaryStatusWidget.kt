package dev.pygloss.render

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import dev.pygloss.application.ReaderUiEvents
import dev.pygloss.llm.LlmResult
import dev.pygloss.render.config.PyGlossConfigurable
import java.util.concurrent.atomic.AtomicInteger

/** Project-level status for background Intent Summary generation. */
@Service(Service.Level.PROJECT)
class IntentSummaryStatusService(private val project: Project) {

    private val pendingBatches = AtomicInteger(0)
    private val notificationGate = FailureNotificationGate()
    @Volatile
    private var lastFailure: LlmResult? = null

    /** Mark one Intent Summary batch as waiting on the provider. */
    fun summaryStarted() {
        pendingBatches.incrementAndGet()
        updateWidget()
    }

    /** Mark one Intent Summary batch as complete, failed, or superseded. */
    fun summaryFinished() {
        pendingBatches.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        updateWidget()
    }

    /** Record that the last Intent Summary batch could not generate every requested summary. */
    fun summaryFailed(error: LlmResult) {
        lastFailure = error
        updateWidget()
    }

    /** Clear the failed state after a later batch completes without provider errors. */
    fun summarySucceeded() {
        lastFailure = null
        notificationGate.reset()
        updateWidget()
    }

    /** Short status-bar text. */
    fun text(): String {
        return if (pendingBatches.get() > 0) {
            "PyGloss: summarizing..."
        } else if (lastFailure != null) {
            "PyGloss: summaries unavailable"
        } else {
            "PyGloss"
        }
    }

    /** Tooltip with a little more context. */
    fun tooltip(): String {
        return if (pendingBatches.get() > 0) {
            "Intent Summary is waiting for the configured LLM provider. Existing folds use cached summaries or docstrings meanwhile."
        } else if (lastFailure != null) {
            lastFailure?.let(::llmFailureMessage) ?: "Intent Summary could not generate summaries."
        } else {
            "PyGloss reader is ready."
        }
    }

    /** Show one provider-failure balloon for a failed batch. */
    fun notifyFirstBatchFailure(error: LlmResult) {
        if (!notificationGate.shouldNotify(error)) return
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(llmFailureMessage(error), NotificationType.WARNING)

        if (error.opensSettings()) {
            notification.addAction(
                NotificationAction.createSimple("Open settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, PyGlossConfigurable::class.java)
                }
            )
        }
        notification.notify(project)
    }

    private fun updateWidget() {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                WindowManager.getInstance().getStatusBar(project)?.updateWidget(WIDGET_ID)
            }
        }
    }

    companion object {
        /** Stable status-bar widget ID. */
        const val WIDGET_ID: String = "dev.pygloss.intentSummaryStatus"
        const val NOTIFICATION_GROUP_ID: String = "dev.pygloss.notifications"

        /** Return the project status service. */
        fun getInstance(project: Project): IntentSummaryStatusService = project.service()
    }
}

/** Declarative message-bus bridge from application events to renderer services. */
class ReaderUiEventListener : ReaderUiEvents {
    override fun summaryStarted(project: Project) {
        IntentSummaryStatusService.getInstance(project).summaryStarted()
    }

    override fun summaryFinished(project: Project) {
        IntentSummaryStatusService.getInstance(project).summaryFinished()
    }

    override fun summaryFailed(project: Project, error: LlmResult) {
        IntentSummaryStatusService.getInstance(project).apply {
            summaryFailed(error)
            notifyFirstBatchFailure(error)
        }
    }

    override fun summarySucceeded(project: Project) {
        IntentSummaryStatusService.getInstance(project).summarySucceeded()
    }

    override fun refreshFile(project: Project, file: com.jetbrains.python.psi.PyFile) {
        PyGlossRefresher.refreshFile(project, file)
    }
}

/** Suppresses repeated balloons for the same provider failure until a successful batch. */
internal class FailureNotificationGate {
    private var lastNotifiedFailure: LlmResult? = null

    @Synchronized
    fun shouldNotify(error: LlmResult): Boolean {
        if (error is LlmResult.Success || error == lastNotifiedFailure) return false
        lastNotifiedFailure = error
        return true
    }

    @Synchronized
    fun reset() {
        lastNotifiedFailure = null
    }
}

/** Human message for typed provider failures. */
fun llmFailureMessage(error: LlmResult): String {
    return when (error) {
        LlmResult.AuthError -> "PyGloss: the LLM provider rejected the API key."
        LlmResult.NetworkError -> "PyGloss: cannot reach the LLM provider (check Base URL — is Ollama running?)"
        LlmResult.ParseError -> "PyGloss: the LLM provider returned a response PyGloss could not read."
        LlmResult.RateLimited -> "PyGloss: provider rate limit reached; summaries will stay pending."
        LlmResult.Timeout -> "PyGloss: the LLM provider did not respond before the timeout."
        is LlmResult.Success -> "PyGloss: summaries are available."
    }
}

private fun LlmResult.opensSettings(): Boolean {
    return when (this) {
        LlmResult.AuthError,
        LlmResult.NetworkError,
        LlmResult.ParseError,
        LlmResult.Timeout -> true
        LlmResult.RateLimited,
        is LlmResult.Success -> false
    }
}

/** Registers the PyGloss status item in the IDE status bar. */
class IntentSummaryStatusWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = IntentSummaryStatusService.WIDGET_ID

    override fun getDisplayName(): String = "PyGloss Intent Summary"

    override fun isAvailable(project: Project): Boolean = true

    override fun isEnabledByDefault(): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = IntentSummaryStatusWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }
}

private class IntentSummaryStatusWidget(
    private val project: Project,
) : StatusBarWidget, StatusBarWidget.TextPresentation, Disposable {

    override fun ID(): String = IntentSummaryStatusService.WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) = Unit

    override fun dispose() = Unit

    override fun getText(): String = IntentSummaryStatusService.getInstance(project).text()

    override fun getTooltipText(): String = IntentSummaryStatusService.getInstance(project).tooltip()

    override fun getAlignment(): Float = 0.5f
}
