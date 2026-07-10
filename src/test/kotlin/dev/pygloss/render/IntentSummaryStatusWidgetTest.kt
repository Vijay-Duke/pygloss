package dev.pygloss.render

import dev.pygloss.llm.LlmResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for Intent Summary status text shown to users. */
class IntentSummaryStatusWidgetTest {

    @Test
    fun `rate limit message includes PyGloss prefix`() {
        assertEquals(
            "PyGloss: provider rate limit reached; summaries will stay pending.",
            llmFailureMessage(LlmResult.RateLimited),
        )
    }

    @Test
    fun `failure notification gate suppresses repeats until success`() {
        val gate = FailureNotificationGate()

        assertTrue(gate.shouldNotify(LlmResult.AuthError))
        assertFalse(gate.shouldNotify(LlmResult.AuthError))
        assertTrue("A different failure should still be surfaced", gate.shouldNotify(LlmResult.NetworkError))
        assertFalse(gate.shouldNotify(LlmResult.NetworkError))

        gate.reset()

        assertTrue("A success resets notification suppression", gate.shouldNotify(LlmResult.NetworkError))
    }
}
