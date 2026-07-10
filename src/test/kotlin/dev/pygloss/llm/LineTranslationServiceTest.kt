package dev.pygloss.llm

import dev.pygloss.application.LineTranslationService

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.psi.PsiDocumentManager
import com.jetbrains.python.psi.PyFile
import dev.pygloss.cache.ModelCacheService
import dev.pygloss.settings.ExplainStyle
import dev.pygloss.settings.PyGlossProjectSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Tests for Reader line translation prompts, cache keys, parsing, and async guards. */
class LineTranslationServiceTest : BasePlatformTestCase() {

    private lateinit var cacheService: ModelCacheService

    override fun setUp() {
        super.setUp()
        cacheService = ModelCacheService.getInstance(project)
        cacheService.clearCache()
        resetProjectSettings()
    }

    override fun tearDown() {
        try {
            cacheService.clearCache()
            resetProjectSettings()
        } finally {
            super.tearDown()
        }
    }

    fun testPromptIncludesNumberedStatementsAudienceAndDomain() {
        projectSettings().update(
            domainDescription = "Generates equity research charts.",
            explainStyle = ExplainStyle.PLAIN,
            translateLinesWithLlm = true
        )

        val request = LineTranslationPrompt.request(project, listOf("ax.set_xlim(0, 10)", "print('done')"))

        assertEquals(2048, request.maxTokens)
        assertTrue(request.prompt.contains("Project context: Generates equity research charts."))
        assertTrue(request.prompt.contains("The reader is a professional who does not know Python or programming."))
        assertTrue(request.prompt.contains("1. ax.set_xlim(0, 10)"))
        assertTrue(request.prompt.contains("2. print('done')"))
        assertTrue(request.prompt.contains("Reply with the same numbers, one line each, nothing else."))
    }

    fun testParseNumberedResponseIgnoresMissingAndGarbageLines() {
        val parsed = LineTranslationPrompt.parseResponse(
            """
            1. limit the chart's horizontal axis to 0 to 10
            note: extra prose
            3) show the chart
            """.trimIndent()
        )

        assertEquals("limit the chart's horizontal axis to 0 to 10", parsed[1])
        assertNull(parsed[2])
        assertEquals("show the chart", parsed[3])
        assertTrue(LineTranslationPrompt.parseResponse("not numbered").isEmpty())
    }

    fun testCacheKeyUsesPromptSettingsAndLinePrefix() {
        val service = createSynchronousService(LlmResult.Success(""))
        val statement = "ax.set_xlim(0, 10)"

        service.writeTranslation(statement, "limit the chart's horizontal axis to 0 to 10")

        assertEquals("limit the chart's horizontal axis to 0 to 10", service.readTranslation(statement))
        val originalLineKey = lineCacheKey(project, statement)
        assertTrue(originalLineKey.startsWith("line:"))
        cacheService.writeLlmBody(originalLineKey.removePrefix("line:"), "block namespace body")
        assertEquals("limit the chart's horizontal axis to 0 to 10", service.readTranslation(statement))

        projectSettings().update("", ExplainStyle.TECHNICAL, true)

        assertNull("Changed style should use a different prompt-aware key", service.readTranslation(statement))

        projectSettings().update("Charts for equity research.", ExplainStyle.PLAIN, true)

        assertNull("Changed domain should use a different prompt-aware key", service.readTranslation(statement))
    }

    fun testSuccessfulBatchCachesAllTranslations() {
        val service = createSynchronousService(
            LlmResult.Success(
                """
                1. limit the chart's horizontal axis to 0 to 10
                2. show the chart
                """.trimIndent()
            )
        )
        val file = configure("ax.set_xlim(0, 10)\nplt.show()\n")

        service.requestMissing(file, listOf("ax.set_xlim(0, 10)", "plt.show()"))

        assertEquals("limit the chart's horizontal axis to 0 to 10", service.readTranslation("ax.set_xlim(0, 10)"))
        assertEquals("show the chart", service.readTranslation("plt.show()"))
    }

    fun testMissingNumberLeavesThatLineUncached() {
        val service = createSynchronousService(LlmResult.Success("1. limit the chart's horizontal axis to 0 to 10"))
        val file = configure("ax.set_xlim(0, 10)\nplt.show()\n")

        service.requestMissing(file, listOf("ax.set_xlim(0, 10)", "plt.show()"))

        assertEquals("limit the chart's horizontal axis to 0 to 10", service.readTranslation("ax.set_xlim(0, 10)"))
        assertNull(service.readTranslation("plt.show()"))
    }

    fun testGarbageResponseCachesNothingAndDoesNotThrow() {
        val service = createSynchronousService(LlmResult.Success("Here are the translations:"))
        val file = configure("ax.set_xlim(0, 10)\n")

        service.requestMissing(file, listOf("ax.set_xlim(0, 10)"))

        assertNull(service.readTranslation("ax.set_xlim(0, 10)"))
    }

    fun testSupersededByPsiEditDiscardsAdapterResultAndSkipsRefresh() {
        val enteredAdapter = CountDownLatch(1)
        val releaseAdapter = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val refreshes = AtomicInteger(0)
        val adapter = object : LlmAdapter {
            override fun summarize(request: LlmRequest): LlmResult {
                enteredAdapter.countDown()
                releaseAdapter.await(2, TimeUnit.SECONDS)
                return LlmResult.Success("1. stale translation")
            }
        }
        val service = object : LineTranslationService(
            project,
            cacheService,
            executor,
            adapterProvider = { callback -> callback(adapter) },
            debounceMillis = 0L
        ) {
            override fun refresh(file: PyFile) {
                refreshes.incrementAndGet()
            }
        }
        val file = configure("ax.set_xlim(0, 10)\n")

        try {
            service.requestMissing(file, listOf("ax.set_xlim(0, 10)"))
            assertTrue("Adapter should start", enteredAdapter.await(2, TimeUnit.SECONDS))

            myFixture.type("\n")
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            releaseAdapter.countDown()
            executor.shutdown()
            assertTrue("Executor should finish", executor.awaitTermination(2, TimeUnit.SECONDS))

            assertNull(service.readTranslation("ax.set_xlim(0, 10)"))
            assertEquals(0, refreshes.get())
        } finally {
            executor.shutdownNow()
        }
    }

    fun testEditInAnotherFileDoesNotDiscardLineTranslation() {
        val enteredAdapter = CountDownLatch(1)
        val releaseAdapter = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val adapter = object : LlmAdapter {
            override fun summarize(request: LlmRequest): LlmResult {
                enteredAdapter.countDown()
                releaseAdapter.await(2, TimeUnit.SECONDS)
                return LlmResult.Success("1. keep the translated line")
            }
        }
        val service = LineTranslationService(
            project,
            cacheService,
            executor,
            adapterProvider = { callback -> callback(adapter) },
            debounceMillis = 0L
        )
        val file = configure("ax.set_xlim(0, 10)\n")

        try {
            service.requestMissing(file, listOf("ax.set_xlim(0, 10)"))
            assertTrue("Adapter should start", enteredAdapter.await(2, TimeUnit.SECONDS))

            myFixture.configureByText("unrelated.py", "value = 1\n")
            myFixture.type("# unrelated edit\n")
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            releaseAdapter.countDown()
            executor.shutdown()
            assertTrue("Executor should finish", executor.awaitTermination(2, TimeUnit.SECONDS))

            assertEquals("keep the translated line", service.readTranslation("ax.set_xlim(0, 10)"))
        } finally {
            executor.shutdownNow()
        }
    }

    private fun createSynchronousService(result: LlmResult): LineTranslationService {
        val adapter = object : LlmAdapter {
            override fun summarize(request: LlmRequest): LlmResult = result
        }
        return LineTranslationService(
            project,
            cacheService,
            executor = Executor { it.run() },
            adapterProvider = { callback -> callback(adapter) },
            debounceMillis = 0L,
        )
    }

    private fun configure(code: String): PyFile {
        return myFixture.configureByText("line_translation.py", code) as PyFile
    }

    private fun resetProjectSettings() {
        projectSettings().update("", ExplainStyle.PLAIN, false)
    }

    private fun projectSettings(): PyGlossProjectSettings {
        return PyGlossProjectSettings.getInstance(project)
    }
}
