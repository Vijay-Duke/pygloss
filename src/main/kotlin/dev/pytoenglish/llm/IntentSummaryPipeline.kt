package dev.pytoenglish.llm

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.concurrency.AppExecutorUtil
import com.jetbrains.python.psi.PyFile
import dev.pytoenglish.cache.ModelCacheService
import dev.pytoenglish.model.EnglishBlock
import dev.pytoenglish.model.EnglishModel
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

/**
 * Intent Summary pipeline for background snapshot, LLM adapter calls, cache writes, and renderer refresh.
 */
open class IntentSummaryPipeline(
    private val project: Project,
    private val adapter: LlmAdapter,
    private val cacheService: ModelCacheService,
    private val executor: Executor = AppExecutorUtil.getAppExecutorService(),
) {
    private val log = Logger.getInstance(IntentSummaryPipeline::class.java)
    private val generation = AtomicLong(0)
    private val invalidatedHashes = Collections.synchronizedSet(mutableSetOf<String>())

    /** Schedule summaries for model blocks that are not already cached. */
    fun process(file: PyFile, model: EnglishModel) {
        val requestedHashes = collectHashesNeedingSummary(model)
        if (requestedHashes.isEmpty()) return

        val batchGeneration = generation.incrementAndGet()
        val modificationCount = currentPsiModificationCount()
        scheduleSnapshot(file, model, requestedHashes, batchGeneration, modificationCount)
    }

    /** Store [summary] for [blockHash] in the project LLM body cache. */
    fun writeSummary(blockHash: String, summary: String) {
        invalidatedHashes.remove(blockHash)
        cacheService.writeLlmBody(blockHash, summary)
    }

    /** Hide an existing cached summary until a fresh request writes a replacement. */
    fun clearSummary(blockHash: String) {
        invalidatedHashes.add(blockHash)
    }

    /** Return the cached summary for [blockHash], respecting local regeneration invalidations. */
    fun readSummary(blockHash: String): String? {
        if (blockHash in invalidatedHashes) return null
        return cacheService.readLlmBody(blockHash)
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
        modificationCount: Long,
    ) {
        ReadAction
            .nonBlocking<List<BlockSnapshot>> {
                collectSnapshots(collectTargets(file, model, requestedHashes))
            }
            .expireWith(project)
            .submit(executor)
            .onSuccess { snapshots ->
                executor.execute {
                    processSnapshots(file, snapshots, batchGeneration, modificationCount)
                }
            }
            .onError { error ->
                log.warn("Intent Summary snapshot failed", error)
            }
    }

    /** Refresh editor renderers after summaries are cached. */
    protected open fun refresh(file: PyFile) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                DaemonCodeAnalyzer.getInstance(project).restart(file)
            }
        }
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
        modificationCount: Long,
    ) {
        var wroteAnySummary = false
        for (snapshot in snapshots) {
            if (isSuperseded(batchGeneration, modificationCount)) return
            if (readSummary(snapshot.psiHash) != null) continue

            when (val result = adapter.summarize(LlmRequest(prompt = buildPrompt(snapshot), systemPrompt = SYSTEM_PROMPT))) {
                is LlmResult.Success -> {
                    if (isSuperseded(batchGeneration, modificationCount)) return
                    writeSummary(snapshot.psiHash, result.text)
                    cacheService.markBlockFresh(snapshot.stableId)
                    wroteAnySummary = true
                }
                else -> log.warn("Intent Summary adapter failed for ${snapshot.stableId}: $result")
            }
        }
        if (wroteAnySummary && !isSuperseded(batchGeneration, modificationCount)) {
            refresh(file)
        }
    }

    private fun isSuperseded(batchGeneration: Long, modificationCount: Long): Boolean {
        return generation.get() != batchGeneration || currentPsiModificationCount() != modificationCount
    }

    private fun buildPrompt(snapshot: BlockSnapshot): String {
        return buildString {
            appendLine("Intent Summary prompt v$PROMPT_VERSION.")
            appendLine("Summarize the intent of this Python block in one concise sentence.")
            appendLine("Use the deterministic skeleton as the source of truth; source text is secondary context.")
            appendLine()
            appendLine("Deterministic skeleton:")
            appendLine("kind=${snapshot.kind}")
            snapshot.skeleton.split("|").filter { it.isNotBlank() }.forEach { appendLine(it) }
            appendLine("range=${snapshot.textRange.startOffset}:${snapshot.textRange.endOffset}")
            appendLine()
            appendLine("Source context:")
            appendLine(snapshot.sourceSnippet)
        }
    }

    private fun currentPsiModificationCount(): Long {
        return try {
            PsiModificationTracker.getInstance(project).modificationCount
        } catch (_: RuntimeException) {
            -1L
        }
    }

    private companion object {
        private const val PROMPT_VERSION = 1
        private const val MAX_SOURCE_SNIPPET_CHARS = 1200
        private const val SYSTEM_PROMPT =
            "You summarize Python code intent from deterministic PSI skeleton facts. Do not claim behavior absent from the skeleton."
    }
}
