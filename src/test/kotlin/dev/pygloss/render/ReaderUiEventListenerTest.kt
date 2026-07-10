package dev.pygloss.render

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pygloss.application.ReaderUiEvents

class ReaderUiEventListenerTest : BasePlatformTestCase() {
    fun testRoutesApplicationStatusEventsThroughMessageBus() {
        val status = IntentSummaryStatusService.getInstance(project)
        status.summarySucceeded()
        val publisher = project.messageBus.syncPublisher(ReaderUiEvents.TOPIC)

        publisher.summaryStarted(project)
        assertEquals("PyGloss: summarizing...", status.text())

        publisher.summaryFinished(project)
        assertEquals("PyGloss", status.text())
    }
}
