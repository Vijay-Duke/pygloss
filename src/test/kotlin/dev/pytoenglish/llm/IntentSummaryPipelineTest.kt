package dev.pytoenglish.llm

import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.psi.PyFile
import dev.pytoenglish.cache.ModelCacheService
import dev.pytoenglish.engine.BlockDetector
import dev.pytoenglish.engine.EnglishModelService
import dev.pytoenglish.model.BlockKind
import dev.pytoenglish.model.EnglishBlock
import dev.pytoenglish.model.EnglishModel
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
        val file = configure(
            """
            def greet(name):
                return f"Hello, {name}!"
            """.trimIndent()
        )
        val model = BlockDetector.detect(file)
        val function = flatten(model).first { it.kind == BlockKind.FUNCTION }
        adapter.nextResult = LlmResult.Success("Greets the supplied name.")

        val pipeline = createSynchronousPipeline()
        pipeline.process(file, model)

        assertEquals("Greets the supplied name.", cacheService.readLlmBody(function.psiHash))
        val enriched = EnglishModelService.getInstance(project).withCachedSummaries(model)
        val enrichedFunction = flatten(enriched).first { it.stableId == function.stableId }
        assertEquals("Greets the supplied name.", enrichedFunction.summary)
    }

    fun testOnlyChangedBlocksAreReRequestedAfterSemanticEdit() {
        val originalFile = configure(
            """
            def greet(name):
                return "hello"

            def farewell(name):
                return "bye"
            """.trimIndent()
        )
        val original = BlockDetector.detect(originalFile)
        val originalGreet = functionNamed(original, "greet")
        val originalFarewell = functionNamed(original, "farewell")
        val pipeline = createSynchronousPipeline()
        adapter.nextResult = LlmResult.Success("cached")
        pipeline.process(originalFile, original)
        assertEquals(2, adapter.callCount)

        val editedFile = configure(
            """
            def greet(first, last):
                return "hello"

            def farewell(name):
                return "bye"
            """.trimIndent()
        )
        val edited = BlockDetector.detect(editedFile)
        val editedGreet = functionNamed(edited, "greet")
        val editedFarewell = functionNamed(edited, "farewell")

        assertFalse(originalGreet.psiHash == editedGreet.psiHash)
        assertEquals(originalFarewell.psiHash, editedFarewell.psiHash)

        adapter.nextResult = LlmResult.Success("fresh greet")
        pipeline.process(editedFile, edited)

        assertEquals("Only the changed function should be requested again", 3, adapter.callCount)
        assertEquals("fresh greet", pipeline.readSummary(editedGreet.psiHash))
        assertEquals("cached", pipeline.readSummary(editedFarewell.psiHash))
    }

    fun testCancellationOnEditPreventsStaleSummaryWrite() {
        val file = configure(
            """
            def slow(value):
                return value
            """.trimIndent()
        )
        val model = BlockDetector.detect(file)
        val function = functionNamed(model, "slow")
        val enteredAdapter = CountDownLatch(1)
        val releaseAdapter = CountDownLatch(1)
        val slowAdapter = object : LlmAdapter {
            override fun summarize(request: LlmRequest): LlmResult {
                enteredAdapter.countDown()
                releaseAdapter.await(2, TimeUnit.SECONDS)
                return LlmResult.Success("stale summary")
            }
        }
        val pipeline = createThreadedPipeline(slowAdapter)

        pipeline.process(file, model)
        assertTrue("Adapter should start", enteredAdapter.await(2, TimeUnit.SECONDS))

        myFixture.type("\n")
        pipeline.cancelProcessing()
        releaseAdapter.countDown()

        Thread.sleep(100)
        assertNull("Superseded request must not write stale text", pipeline.readSummary(function.psiHash))
    }

    fun testPromptIncludesDeterministicSkeletonFacts() {
        val file = configure(
            """
            async def compute(x, y):
                return x + y
            """.trimIndent()
        )
        val model = BlockDetector.detect(file)
        adapter.nextResult = LlmResult.Success("Adds two values.")

        createSynchronousPipeline().process(file, model)

        val prompt = requireNotNull(adapter.lastRequest).prompt
        assertTrue(prompt.contains("Deterministic skeleton:"))
        assertTrue(prompt.contains("name=compute"))
        assertTrue(prompt.contains("params=x,y"))
        assertTrue(prompt.contains("async=true"))
    }

    fun testRegenerateClearsEntryAndIssuesFreshRequest() {
        val file = configure(
            """
            def process(data):
                return data
            """.trimIndent()
        )
        val model = BlockDetector.detect(file)
        val function = functionNamed(model, "process")
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
        val file = configure(
            """
            def f():
                pass
            """.trimIndent()
        )
        val model = BlockDetector.detect(file)

        createSynchronousPipeline().process(file, model)

        assertEquals(1, adapter.snapshotActiveValues.size)
        assertFalse("Adapter must not run during the PSI snapshot read action", adapter.snapshotActiveValues.single())
    }

    private fun configure(code: String): PyFile {
        return myFixture.configureByText("test.py", code) as PyFile
    }

    private fun createSynchronousPipeline(): IntentSummaryPipeline {
        return object : IntentSummaryPipeline(project, adapter, cacheService) {
            override fun scheduleSnapshot(
                file: PyFile,
                model: EnglishModel,
                requestedHashes: Set<String>,
                batchGeneration: Long,
                modificationCount: Long,
            ) {
                adapter.snapshotActive = true
                val snapshots = ReadAction.compute<List<BlockSnapshot>, RuntimeException> {
                    collectSnapshots(collectTargets(file, model, requestedHashes))
                }
                adapter.snapshotActive = false
                processSnapshots(file, snapshots, batchGeneration, modificationCount)
            }

            override fun refresh(file: PyFile) = Unit
        }
    }

    private fun createThreadedPipeline(llmAdapter: LlmAdapter): IntentSummaryPipeline {
        return object : IntentSummaryPipeline(project, llmAdapter, cacheService) {
            override fun refresh(file: PyFile) = Unit
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
