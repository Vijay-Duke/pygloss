package dev.pygloss.llm

import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.psi.PyFile
import dev.pygloss.cache.ModelCacheService
import dev.pygloss.engine.BlockDetector
import dev.pygloss.application.EnglishModelService
import dev.pygloss.model.BlockKind
import dev.pygloss.model.EnglishBlock
import dev.pygloss.model.EnglishModel
import dev.pygloss.render.IntentSummaryStatusService
import dev.pygloss.settings.ExplainStyle
import dev.pygloss.settings.PyGlossProjectSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Tests for the Intent Summary snapshot, adapter, cache, and model-enrichment contract. */
class IntentSummaryPipelineTest : BasePlatformTestCase() {

    private lateinit var adapter: RecordingAdapter
    private lateinit var cacheService: ModelCacheService

    override fun setUp() {
        super.setUp()
        cacheService = ModelCacheService.getInstance(project)
        cacheService.clearCache()
        IntentSummaryStatusService.getInstance(project).summarySucceeded()
        resetProjectSettings()
        adapter = RecordingAdapter()
    }

    override fun tearDown() {
        try {
            cacheService.clearCache()
        } finally {
            super.tearDown()
        }
    }

    fun testPipelineWritesSummaryToCacheAndModelExposesIt() {
        val name = uniqueFunctionName("greet")
        val file = configure(
            """
            def $name(name):
                return f"Hello, {name}!"
            """.trimIndent()
        )
        val model = BlockDetector.detect(file)
        val function = flatten(model).first { it.kind == BlockKind.FUNCTION }
        adapter.nextResult = LlmResult.Success("Greets the supplied name.")

        val pipeline = createSynchronousPipeline()
        pipeline.process(file, model)

        assertEquals("Greets the supplied name.", pipeline.readSummary(function.psiHash))
        val enriched = EnglishModelService.getInstance(project).withCachedSummaries(model)
        val enrichedFunction = flatten(enriched).first { it.stableId == function.stableId }
        assertEquals("Greets the supplied name.", enrichedFunction.summary)
    }

    fun testOnlyChangedBlocksAreReRequestedAfterSemanticEdit() {
        val greetName = uniqueFunctionName("greet")
        val farewellName = uniqueFunctionName("farewell")
        val originalFile = configure(
            """
            def $greetName(name):
                return "hello"

            def $farewellName(name):
                return "bye"
            """.trimIndent()
        )
        val original = BlockDetector.detect(originalFile)
        val originalGreet = functionNamed(original, greetName)
        val originalFarewell = functionNamed(original, farewellName)
        val pipeline = createSynchronousPipeline()
        adapter.nextResult = LlmResult.Success("cached")
        pipeline.process(originalFile, original)
        assertEquals(2, adapter.callCount)

        val editedFile = configure(
            """
            def $greetName(first, last):
                return "hello"

            def $farewellName(name):
                return "bye"
            """.trimIndent()
        )
        val edited = BlockDetector.detect(editedFile)
        val editedGreet = functionNamed(edited, greetName)
        val editedFarewell = functionNamed(edited, farewellName)

        assertFalse(originalGreet.psiHash == editedGreet.psiHash)
        assertEquals(originalFarewell.psiHash, editedFarewell.psiHash)

        adapter.nextResult = LlmResult.Success("fresh greet")
        pipeline.process(editedFile, edited)

        assertEquals("Only the changed function should be requested again", 3, adapter.callCount)
        assertEquals("fresh greet", pipeline.readSummary(editedGreet.psiHash))
        assertEquals("cached", pipeline.readSummary(editedFarewell.psiHash))
    }

    fun testCancellationOnEditPreventsStaleSummaryWrite() {
        val name = uniqueFunctionName("slow")
        val file = configure(
            """
            def $name(value):
                return value
            """.trimIndent()
        )
        val model = BlockDetector.detect(file)
        val function = functionNamed(model, name)
        val enteredAdapter = CountDownLatch(1)
        val releaseAdapter = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val slowAdapter = object : LlmAdapter {
            override fun summarize(request: LlmRequest): LlmResult {
                enteredAdapter.countDown()
                releaseAdapter.await(2, TimeUnit.SECONDS)
                return LlmResult.Success("stale summary")
            }
        }
        val pipeline = createThreadedPipeline(slowAdapter)

        pipeline.process(file, model) { completed.countDown() }
        assertTrue("Adapter should start", enteredAdapter.await(2, TimeUnit.SECONDS))

        myFixture.type("\n")
        pipeline.cancelProcessing()
        releaseAdapter.countDown()

        assertTrue("Cancelled batch should complete", completed.await(2, TimeUnit.SECONDS))
        assertNull("Superseded request must not write stale text", pipeline.readSummary(function.psiHash))
    }

    fun testEditInAnotherFileDoesNotCancelSummaryWrite() {
        val name = uniqueFunctionName("isolated")
        val file = configure(
            """
            def $name(value):
                return value
            """.trimIndent()
        )
        val model = BlockDetector.detect(file)
        val function = functionNamed(model, name)
        val enteredAdapter = CountDownLatch(1)
        val releaseAdapter = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val slowAdapter = object : LlmAdapter {
            override fun summarize(request: LlmRequest): LlmResult {
                enteredAdapter.countDown()
                releaseAdapter.await(2, TimeUnit.SECONDS)
                return LlmResult.Success("kept summary")
            }
        }
        val pipeline = createThreadedPipeline(slowAdapter)

        pipeline.process(file, model) { completed.countDown() }
        assertTrue("Adapter should start", enteredAdapter.await(2, TimeUnit.SECONDS))

        myFixture.configureByText("unrelated.py", "value = 1\n")
        myFixture.type("# unrelated edit\n")
        releaseAdapter.countDown()

        assertTrue("Summary batch should complete", completed.await(2, TimeUnit.SECONDS))
        assertEquals("kept summary", pipeline.readSummary(function.psiHash))
    }

    fun testPromptIncludesDeterministicSkeletonFacts() {
        val name = uniqueFunctionName("compute")
        val file = configure(
            """
            async def $name(x, y):
                return x + y
            """.trimIndent()
        )
        val model = BlockDetector.detect(file)
        adapter.nextResult = LlmResult.Success("Adds two values.")

        createSynchronousPipeline().process(file, model)

        val prompt = requireNotNull(adapter.lastRequest).prompt
        assertTrue(prompt.contains("Deterministic skeleton:"))
        assertTrue(prompt.contains("name=$name"))
        assertTrue(prompt.contains("params=x,y"))
        assertTrue(prompt.contains("async=true"))
    }

    fun testSamePromptSettingsReuseCachedSummary() {
        val name = uniqueFunctionName("greet")
        val file = configure(
            """
            def $name(name):
                return f"Hello, {name}!"
            """.trimIndent()
        )
        val model = BlockDetector.detect(file)
        val pipeline = createSynchronousPipeline()
        adapter.nextResult = LlmResult.Success("Greets the supplied name.")

        pipeline.process(file, model)
        pipeline.process(file, model)

        assertEquals("Second process should read the prompt-aware cache", 1, adapter.callCount)
    }

    fun testChangingPromptSettingsRequestsNewSummaryAndChangingBackHitsOriginalCache() {
        val name = uniqueFunctionName("chart")
        val file = configure(
            """
            def $name(prices):
                return prices
            """.trimIndent()
        )
        val model = BlockDetector.detect(file)
        val function = functionNamed(model, name)
        val pipeline = createSynchronousPipeline()

        adapter.nextResult = LlmResult.Success("Shows the share prices.")
        pipeline.process(file, model)
        assertEquals("Shows the share prices.", pipeline.readSummary(function.psiHash))

        projectSettings().update(
            domainDescription = "Generates equity research reports as matplotlib charts.",
            explainStyle = ExplainStyle.PLAIN,
            translateLinesWithLlm = false
        )
        adapter.nextResult = LlmResult.Success("Builds the chart for equity reports.")
        pipeline.process(file, model)
        assertEquals(2, adapter.callCount)
        assertEquals("Builds the chart for equity reports.", pipeline.readSummary(function.psiHash))

        projectSettings().update("", ExplainStyle.TECHNICAL, false)
        adapter.nextResult = LlmResult.Success("Returns `prices` from `chart`.")
        pipeline.process(file, model)
        assertEquals(3, adapter.callCount)

        resetProjectSettings()
        pipeline.process(file, model)
        assertEquals("Original prompt signature should still hit its old cache entry", 3, adapter.callCount)
        assertEquals("Shows the share prices.", pipeline.readSummary(function.psiHash))
    }

    fun testPlainPromptUsesAudienceBlockAndSoftGrounding() {
        val file = configure(uniqueFunctionCode("plain_prompt"))
        val model = BlockDetector.detect(file)

        createSynchronousPipeline().process(file, model)

        val request = requireNotNull(adapter.lastRequest)
        assertTrue(request.prompt.contains("The reader is a professional who does not know Python or programming."))
        assertTrue(request.prompt.contains("Only state what the provided facts support."))
        assertFalse(request.prompt.contains("skeleton as the source of truth"))
        assertFalse(request.prompt.contains("Project context:"))
        assertTrue(request.systemPrompt.contains("non-programming professionals"))
    }

    fun testDomainContextAppearsOnlyWhenConfigured() {
        val file = configure(uniqueFunctionCode("domain_prompt"))
        val model = BlockDetector.detect(file)

        createSynchronousPipeline().process(file, model)
        assertFalse(requireNotNull(adapter.lastRequest).prompt.contains("Project context:"))

        projectSettings().update(
            domainDescription = "Generates equity research reports as matplotlib charts.",
            explainStyle = ExplainStyle.PLAIN,
            translateLinesWithLlm = false
        )
        adapter.nextResult = LlmResult.Success("Summarized with domain.")
        createSynchronousPipeline().process(file, model)

        assertTrue(
            requireNotNull(adapter.lastRequest).prompt.contains(
                "Project context: Generates equity research reports as matplotlib charts."
            )
        )
    }

    fun testAnalogiesPromptAddsAnalogyInstruction() {
        projectSettings().update("", ExplainStyle.ANALOGIES, false)
        val file = configure(uniqueFunctionCode("analogy_prompt"))
        val model = BlockDetector.detect(file)

        createSynchronousPipeline().process(file, model)

        val prompt = requireNotNull(adapter.lastRequest).prompt
        assertTrue(prompt.contains("The reader is a professional who does not know Python or programming."))
        assertTrue(prompt.contains("If a concept has no everyday name, add a short analogy in parentheses."))
    }

    fun testTechnicalPromptOmitsAudienceBlock() {
        projectSettings().update("", ExplainStyle.TECHNICAL, false)
        val file = configure(uniqueFunctionCode("technical_prompt"))
        val model = BlockDetector.detect(file)

        createSynchronousPipeline().process(file, model)

        val request = requireNotNull(adapter.lastRequest)
        assertFalse(request.prompt.contains("The reader is a professional who does not know Python or programming."))
        assertTrue(request.prompt.contains("Programming terms and backticked identifiers are allowed."))
        assertTrue(request.systemPrompt.contains("for developers"))
    }

    fun testRegenerateClearsEntryAndIssuesFreshRequest() {
        val name = uniqueFunctionName("process")
        val file = configure(
            """
            def $name(data):
                return data
            """.trimIndent()
        )
        val model = BlockDetector.detect(file)
        val function = functionNamed(model, name)
        val pipeline = createSynchronousPipeline()
        pipeline.writeSummary(function.psiHash, "old summary")

        pipeline.clearSummary(function.psiHash)
        assertNull(pipeline.readSummary(function.psiHash))

        adapter.nextResult = LlmResult.Success("fresh summary")
        pipeline.process(file, model)

        assertEquals(1, adapter.callCount)
        assertEquals("fresh summary", pipeline.readSummary(function.psiHash))
    }

    fun testAdapterCallHappensOutsideReadAction() {
        val name = uniqueFunctionName("f")
        val file = configure(
            """
            def $name():
                pass
            """.trimIndent()
        )
        val model = BlockDetector.detect(file)

        createSynchronousPipeline().process(file, model)

        assertEquals(1, adapter.snapshotActiveValues.size)
        assertFalse("Adapter must not run during the PSI snapshot read action", adapter.snapshotActiveValues.single())
    }

    fun testAdapterFailureMarksSummariesUnavailableUntilSuccessfulBatch() {
        val name = uniqueFunctionName("f")
        val file = configure(
            """
            def $name():
                pass
            """.trimIndent()
        )
        val model = BlockDetector.detect(file)
        val function = functionNamed(model, name)
        adapter.nextResult = LlmResult.NetworkError

        createSynchronousPipeline().process(file, model)

        val status = IntentSummaryStatusService.getInstance(project)
        assertEquals("PyGloss: summaries unavailable", status.text())
        assertTrue(status.tooltip().contains("cannot reach the LLM provider"))
        assertTrue(status.isSummaryUnavailable(function.psiHash))

        createEmptySnapshotPipeline().process(file, model)

        assertEquals("PyGloss: summaries unavailable", status.text())
        assertTrue(status.isSummaryUnavailable(function.psiHash))

        adapter.nextResult = LlmResult.Success("Summarized.")
        createSynchronousPipeline().process(file, model)

        assertEquals("PyGloss", status.text())
        assertFalse(status.isSummaryUnavailable(function.psiHash))
    }

    fun testProviderWideFailureStopsTheCurrentBatch() {
        val first = uniqueFunctionName("first")
        val second = uniqueFunctionName("second")
        val file = configure(
            """
            def $first():
                pass

            def $second():
                pass
            """.trimIndent()
        )
        adapter.nextResult = LlmResult.NetworkError

        createSynchronousPipeline().process(file, BlockDetector.detect(file))

        assertEquals("A provider-wide failure should not be repeated for every block", 1, adapter.callCount)
    }

    private fun configure(code: String): PyFile {
        return myFixture.configureByText("test.py", code) as PyFile
    }

    private fun uniqueFunctionCode(prefix: String): String {
        return "def ${uniqueFunctionName(prefix)}():\n    return 1\n"
    }

    private fun uniqueFunctionName(prefix: String): String {
        return "${prefix}_${System.nanoTime()}"
    }

    private fun resetProjectSettings() {
        projectSettings().update("", ExplainStyle.PLAIN, false)
    }

    private fun projectSettings(): PyGlossProjectSettings {
        return PyGlossProjectSettings.getInstance(project)
    }

    private fun createSynchronousPipeline(): IntentSummaryPipeline {
        return object : IntentSummaryPipeline(project, adapter, cacheService, observer = statusObserver()) {
            override fun scheduleSnapshot(
                file: PyFile,
                model: EnglishModel,
                requestedHashes: Set<String>,
                batchGeneration: Long,
                modificationStamp: Long,
                onComplete: () -> Unit,
            ) {
                try {
                    adapter.snapshotActive = true
                    val snapshots = ReadAction.compute<List<BlockSnapshot>, RuntimeException> {
                        collectSnapshots(collectTargets(file, model, requestedHashes))
                    }
                    adapter.snapshotActive = false
                    processSnapshots(file, snapshots, batchGeneration, modificationStamp)
                } finally {
                    adapter.snapshotActive = false
                    onComplete()
                }
            }

            override fun refresh(file: PyFile) = Unit
        }
    }

    private fun createThreadedPipeline(llmAdapter: LlmAdapter): IntentSummaryPipeline {
        return object : IntentSummaryPipeline(project, llmAdapter, cacheService) {
            override fun refresh(file: PyFile) = Unit
        }
    }

    private fun createEmptySnapshotPipeline(): IntentSummaryPipeline {
        return object : IntentSummaryPipeline(project, adapter, cacheService, observer = statusObserver()) {
            override fun scheduleSnapshot(
                file: PyFile,
                model: EnglishModel,
                requestedHashes: Set<String>,
                batchGeneration: Long,
                modificationStamp: Long,
                onComplete: () -> Unit,
            ) {
                try {
                    processSnapshots(file, emptyList(), batchGeneration, modificationStamp)
                } finally {
                    onComplete()
                }
            }

            override fun refresh(file: PyFile) = Unit
        }
    }

    private fun statusObserver(): SummaryPipelineObserver {
        val status = IntentSummaryStatusService.getInstance(project)
        return object : SummaryPipelineObserver {
            override fun summarySucceeded() = status.summarySucceeded()

            override fun summaryFailed(error: LlmResult, requestedHashes: Set<String>) {
                status.summaryFailed(error, requestedHashes)
            }
        }
    }

    private fun functionNamed(model: EnglishModel, name: String): EnglishBlock {
        return flatten(model).first { block ->
            block.kind == BlockKind.FUNCTION && block.skeleton.contains("name=$name")
        }
    }

    private fun flatten(model: EnglishModel): List<EnglishBlock> {
        val result = mutableListOf<EnglishBlock>()
        fun collect(blocks: List<EnglishBlock>) {
            for (block in blocks) {
                result.add(block)
                collect(block.children)
            }
        }
        collect(model.blocks)
        return result
    }

    /** Recording adapter used by deterministic pipeline tests. */
    private class RecordingAdapter : LlmAdapter {
        var nextResult: LlmResult = LlmResult.Success("summary")
        var callCount: Int = 0
            private set
        var lastRequest: LlmRequest? = null
            private set
        var snapshotActive: Boolean = false
        val snapshotActiveValues = mutableListOf<Boolean>()

        override fun summarize(request: LlmRequest): LlmResult {
            callCount++
            lastRequest = request
            snapshotActiveValues.add(snapshotActive)
            return nextResult
        }
    }
}
