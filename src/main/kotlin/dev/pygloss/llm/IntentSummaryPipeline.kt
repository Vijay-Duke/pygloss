package dev.pygloss.llm

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.util.concurrency.AppExecutorUtil
import com.jetbrains.python.psi.PyFile
import dev.pygloss.cache.ModelCacheService
import dev.pygloss.model.EnglishBlock
import dev.pygloss.model.EnglishModel
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** Immutable PSI snapshot for a single block passed to the adapter phase. */
data class BlockSnapshot(
    /** Stable structural block identifier. */
    val stableId: String,
    /** Semantic PSI hash used as the LLM cache key. */
    val psiHash: String,
    /** Python block kind name. */
    val kind: String,
    /** Deterministic skeleton facts generated before the LLM runs. */
    val skeleton: String,
    /** Source range captured with the snapshot for refresh and diagnostics. */
    val textRange: TextRange,
    /** Bounded source text used only as supporting context. */
    val sourceSnippet: String,
)

/** Captured block target with a smart pointer and deterministic metadata. */
data class SummaryTarget(
    /** Deterministic block metadata from the model. */
    val block: EnglishBlock,
    /** Smart PSI pointer for the block anchor. */
    val pointer: SmartPsiElementPointer<*>,
)

/** Application callbacks for status and refresh side effects. */
interface SummaryPipelineObserver {
    fun summarySucceeded() = Unit
    fun summaryFailed(error: LlmResult) = Unit
    fun refresh(file: PyFile) = Unit

    companion object {
        val NONE: SummaryPipelineObserver = object : SummaryPipelineObserver {}
    }
}

/**
 * Intent Summary pipeline for background snapshots, provider calls, and cache writes.
 */
open class IntentSummaryPipeline(
    private val project: Project,
    private val adapter: LlmAdapter,
    private val cacheService: ModelCacheService,
    private val executor: Executor = AppExecutorUtil.getAppExecutorService(),
    private val observer: SummaryPipelineObserver = SummaryPipelineObserver.NONE,
) {
    private val log = Logger.getInstance(IntentSummaryPipeline::class.java)
    private val generation = AtomicLong(0)
    private val invalidatedSummaryKeys = Collections.synchronizedSet(mutableSetOf<String>())

    /** Schedule summaries for model blocks that are not already cached. */
    fun process(file: PyFile, model: EnglishModel, onComplete: () -> Unit = {}) {
        val requestedHashes = collectHashesNeedingSummary(model)
        if (requestedHashes.isEmpty()) {
            onComplete()
            return
        }

        val batchGeneration = generation.incrementAndGet()
        val modificationStamp = file.modificationStamp
        scheduleSnapshot(file, model, requestedHashes, batchGeneration, modificationStamp, onComplete)
    }

    /** Store [summary] for [blockHash] in the project LLM body cache. */
    fun writeSummary(blockHash: String, summary: String) {
        invalidatedSummaryKeys.remove(summaryCacheKey(project, blockHash))
        writeCachedSummary(project, cacheService, blockHash, summary)
    }

    /** Hide an existing cached summary until a fresh request writes a replacement. */
    fun clearSummary(blockHash: String) {
        invalidatedSummaryKeys.add(summaryCacheKey(project, blockHash))
    }

    /** Return the cached summary for [blockHash], respecting local regeneration invalidations. */
    fun readSummary(blockHash: String): String? {
        if (summaryCacheKey(project, blockHash) in invalidatedSummaryKeys) return null
        return readCachedSummary(project, cacheService, blockHash)
    }

    /** Cancel the currently active batch. */
    fun cancelProcessing() {
        generation.incrementAndGet()
    }

    /** Return [model] with any cached summaries filled in recursively. */
    fun withCachedSummaries(model: EnglishModel): EnglishModel {
        var changed = false
        fun enrich(block: EnglishBlock): EnglishBlock {
            val children = block.children.map(::enrich)
            val summary = block.summary ?: readSummary(block.psiHash)
            if (summary != block.summary || children != block.children) changed = true
            return block.copy(summary = summary, children = children)
        }
        val blocks = model.blocks.map(::enrich)
        return if (changed) model.copy(blocks = blocks) else model
    }

    /** Schedule the read-action snapshot. Tests override this for deterministic execution. */
    protected open fun scheduleSnapshot(
        file: PyFile,
        model: EnglishModel,
        requestedHashes: Set<String>,
        batchGeneration: Long,
        modificationStamp: Long,
        onComplete: () -> Unit,
    ) {
        ReadAction
            .nonBlocking<List<BlockSnapshot>> {
                collectSnapshots(collectTargets(file, model, requestedHashes))
            }
            .expireWith(project)
            .submit(executor)
            .onSuccess { snapshots ->
                executor.execute {
                    try {
                        processSnapshots(file, snapshots, batchGeneration, modificationStamp)
                    } finally {
                        onComplete()
                    }
                }
            }
            .onError { error ->
                log.warn("Intent Summary snapshot failed", error)
                onComplete()
            }
    }

    /** Refresh editor renderers after summaries are cached. */
    protected open fun refresh(file: PyFile) {
        observer.refresh(file)
    }

    /** Collect block hashes without cached summaries. */
    protected fun collectHashesNeedingSummary(model: EnglishModel): Set<String> {
        val hashes = mutableSetOf<String>()
        fun visit(blocks: List<EnglishBlock>) {
            for (block in blocks) {
                if (readSummary(block.psiHash) == null) {
                    hashes.add(block.psiHash)
                }
                visit(block.children)
            }
        }
        visit(model.blocks)
        return hashes
    }

    /** Collect PSI-backed targets for [requestedHashes]; must run inside a read action. */
    protected fun collectTargets(
        file: PyFile,
        model: EnglishModel,
        requestedHashes: Set<String>,
    ): List<SummaryTarget> {
        val pointerManager = SmartPointerManager.getInstance(project)
        val targets = mutableListOf<SummaryTarget>()
        fun visit(blocks: List<EnglishBlock>) {
            for (block in blocks) {
                if (block.psiHash in requestedHashes) {
                    val element = file.findElementAt(block.anchorOffset)?.parent ?: file.findElementAt(block.anchorOffset)
                    if (element != null) {
                        targets.add(SummaryTarget(block, pointerManager.createSmartPsiElementPointer(element)))
                    }
                }
                visit(block.children)
            }
        }
        visit(model.blocks)
        return targets
    }

    /** Convert smart-pointer targets into immutable snapshots inside a read action. */
    protected fun collectSnapshots(targets: List<SummaryTarget>): List<BlockSnapshot> {
        return targets.mapNotNull(::snapshotForTarget)
    }

    /** Convert one smart-pointer target into an immutable snapshot. */
    protected fun snapshotForTarget(target: SummaryTarget): BlockSnapshot? {
        val element = target.pointer.element ?: return null
        val block = target.block
        return BlockSnapshot(
            stableId = block.stableId,
            psiHash = block.psiHash,
            kind = block.kind.name,
            skeleton = block.skeleton,
            textRange = block.textRange,
            sourceSnippet = element.text.take(MAX_SOURCE_SNIPPET_CHARS),
        )
    }

    /** Process immutable snapshots outside a read action. */
    protected fun processSnapshots(
        file: PyFile,
        snapshots: List<BlockSnapshot>,
        batchGeneration: Long,
        modificationStamp: Long,
    ) {
        var wroteAnySummary = false
        var firstFailure: LlmResult? = null
        val requestedHashes = snapshots.mapTo(mutableSetOf()) { it.psiHash }
        for (snapshot in snapshots) {
            if (isSuperseded(file, batchGeneration, modificationStamp)) return
            if (readSummary(snapshot.psiHash) != null) continue

            when (val result = adapter.summarize(IntentSummaryPrompt.request(project, snapshot))) {
                is LlmResult.Success -> {
                    if (isSuperseded(file, batchGeneration, modificationStamp)) return
                    writeSummary(snapshot.psiHash, result.text)
                    cacheService.markBlockFresh(snapshot.stableId)
                    wroteAnySummary = true
                }
                else -> {
                    log.warn("Intent Summary adapter failed for ${snapshot.stableId}: $result")
                    if (firstFailure == null) {
                        firstFailure = result
                        reportFirstBatchFailure(result)
                    }
                    break
                }
            }
        }
        if (firstFailure == null && wroteAnySummary && !isSuperseded(file, batchGeneration, modificationStamp)) {
            observer.summarySucceeded()
        }
        if ((wroteAnySummary || firstFailure != null) && !isSuperseded(file, batchGeneration, modificationStamp)) {
            refresh(file)
        }
    }

    private fun reportFirstBatchFailure(error: LlmResult) {
        observer.summaryFailed(error)
    }

    private fun isSuperseded(file: PyFile, batchGeneration: Long, modificationStamp: Long): Boolean {
        return generation.get() != batchGeneration || file.modificationStamp != modificationStamp
    }

    private companion object {
        private const val MAX_SOURCE_SNIPPET_CHARS = 1200
    }
}
