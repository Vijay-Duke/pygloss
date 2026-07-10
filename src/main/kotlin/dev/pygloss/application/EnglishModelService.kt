package dev.pygloss.application

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.jetbrains.python.psi.PyFile
import dev.pygloss.cache.CacheKey
import dev.pygloss.cache.ModelCacheService
import dev.pygloss.cache.Profile
import dev.pygloss.cache.VerbosityLevel
import dev.pygloss.engine.BlockDetector
import dev.pygloss.engine.PsiHash
import dev.pygloss.engine.withPsiReadAccess
import dev.pygloss.llm.AnthropicAdapter
import dev.pygloss.llm.IntentSummaryPipeline
import dev.pygloss.llm.JdkHttpClient
import dev.pygloss.llm.LlmAdapter
import dev.pygloss.llm.LlmRequest
import dev.pygloss.llm.LlmResult
import dev.pygloss.llm.OpenAiCompatAdapter
import dev.pygloss.llm.SettingsSnapshot
import dev.pygloss.llm.SummaryPipelineObserver
import dev.pygloss.llm.readCachedSummary
import dev.pygloss.model.EnglishBlock
import dev.pygloss.model.EnglishModel
import dev.pygloss.settings.ProviderType
import dev.pygloss.settings.PyGlossSettings
import dev.pygloss.settings.SecretStore
import dev.pygloss.settings.isLoopbackBaseUrl
import java.time.Duration

/** Project-level entry point that builds and caches English models for renderers. */
@Service(Service.Level.PROJECT)
class EnglishModelService(private val project: Project) {

    private val cacheService: ModelCacheService get() = ModelCacheService.getInstance(project)
    private val lookupLock = Any()
    private val lastFileLookups = mutableMapOf<FileLookupKey, FileLookupValue>()
    private val scheduledSummaryKeys = mutableSetOf<CacheKey>()
    private val failedSummaryRetryAfter = mutableMapOf<CacheKey, Long>()
    private var activePipeline: IntentSummaryPipeline? = null

    /** Return the English model for [file], using cached data whenever the semantic key matches. */
    fun getModel(
        file: PyFile,
        profile: Profile = Profile.POLYGLOT_LENS,
        level: VerbosityLevel = VerbosityLevel.HINTS
    ): EnglishModel {
        return withPsiReadAccess { getModelInReadAction(file, profile, level) }
    }

    private fun getModelInReadAction(
        file: PyFile,
        profile: Profile,
        level: VerbosityLevel
    ): EnglishModel {
        cacheService.processInvalidation()

        val fileId = file.virtualFile?.path ?: file.name
        val lookupKey = FileLookupKey(fileId, profile, level)
        val modificationStamp = file.modificationStamp
        synchronized(lookupLock) { lastFileLookups[lookupKey] }?.let { previous ->
            if (previous.modificationStamp == modificationStamp) {
                cacheService.get(previous.cacheKey)?.let { cachedModel ->
                    return projectSummaries(file, cachedModel, profile, previous.cacheKey)
                }
            }
        }

        val currentModel = BlockDetector.detect(file)
        val normalizedHash = modelHash(currentModel)
        val cacheKey = cacheService.keyFor(normalizedHash, profile, level)
        synchronized(lookupLock) {
            lastFileLookups[lookupKey] = FileLookupValue(modificationStamp, cacheKey)
        }

        return projectSummaries(file, cacheService.getOrBuild(cacheKey) { currentModel }, profile, cacheKey)
    }

    /** Build a fresh deterministic model without consulting the cache. */
    fun buildFresh(file: PyFile): EnglishModel {
        return withPsiReadAccess { BlockDetector.detect(file) }
    }

    /** Enrich the model with LLM summaries. Kicks off async pipeline if [adapter] is provided. */
    fun enrich(model: EnglishModel, file: PyFile, adapter: LlmAdapter? = null): EnglishModel {
        if (adapter == null) return model
        val pipeline = pipelineFor(adapter)
        pipeline.process(file, model)
        return pipeline.withCachedSummaries(model)
    }

    /** Enrich [model] with cached summaries without issuing adapter calls. */
    fun withCachedSummaries(model: EnglishModel): EnglishModel {
        return activePipeline?.withCachedSummaries(model) ?: populateCachedSummaries(model)
    }

    /** Clear a block summary and request a fresh summary through [adapter]. */
    fun regenerate(
        file: PyFile,
        model: EnglishModel,
        block: EnglishBlock,
        adapter: LlmAdapter,
        onComplete: () -> Unit = {}
    ) {
        val pipeline = pipelineFor(adapter)
        pipeline.clearSummary(block.psiHash)
        cacheService.markBlockStale(block.stableId)
        pipeline.process(file, model, onComplete)
    }

    /** Regenerate the deepest Intent Summary block containing [offset]. */
    fun regenerateAtOffset(file: PyFile, offset: Int, onComplete: () -> Unit = {}) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        createConfiguredAdapterAsync { adapter ->
            ReadAction
                .nonBlocking<Pair<EnglishModel, EnglishBlock?>?> {
                    val model = getModel(file, Profile.INTENT_SUMMARY, VerbosityLevel.HINTS)
                    model to findBlockAtOffset(model.blocks, offset)
                }
                .expireWith(project)
                .submit(AppExecutorUtil.getAppExecutorService())
                .onSuccess { result ->
                    val (model, block) = result ?: run {
                        onComplete()
                        return@onSuccess
                    }
                    if (block == null) {
                        onComplete()
                    } else {
                        regenerate(file, model, block, adapter, onComplete)
                    }
                }
                .onError { onComplete() }
        }
    }

    /** Build an adapter from persisted settings and the API key loaded off-EDT. */
    fun createConfiguredAdapter(apiKey: String): LlmAdapter {
        val settings = PyGlossSettings.getInstance()
        if (apiKey.isBlank() && !isLoopbackBaseUrl(settings.baseUrl)) {
            return object : LlmAdapter {
                override fun summarize(request: LlmRequest): LlmResult = LlmResult.AuthError
            }
        }
        val snapshot = SettingsSnapshot(
            baseUrl = settings.baseUrl,
            model = settings.model,
            apiKey = apiKey,
        )
        val httpClient = JdkHttpClient(requestTimeout = SUMMARY_REQUEST_TIMEOUT)
        return when (settings.provider) {
            ProviderType.OPENAI_COMPAT -> OpenAiCompatAdapter(snapshot, httpClient, SUMMARY_REQUEST_TIMEOUT)
            ProviderType.ANTHROPIC -> AnthropicAdapter(snapshot, httpClient, SUMMARY_REQUEST_TIMEOUT)
        }
    }

    /** Load the configured API key on a pooled thread, then pass a configured adapter to [callback]. */
    fun createConfiguredAdapterAsync(callback: (LlmAdapter) -> Unit) {
        SecretStore.getInstance().getApiKeyAsync { apiKey ->
            callback(createConfiguredAdapter(apiKey))
        }
    }

    private fun pipelineFor(adapter: LlmAdapter, onFailure: () -> Unit = {}): IntentSummaryPipeline {
        val publisher = project.messageBus.syncPublisher(ReaderUiEvents.TOPIC)
        val observer = object : SummaryPipelineObserver {
            override fun summarySucceeded() = publisher.summarySucceeded(project)

            override fun summaryFailed(error: LlmResult, requestedHashes: Set<String>) {
                onFailure()
                publisher.summaryFailed(project, error, requestedHashes)
            }

            override fun refresh(file: PyFile) = publisher.refreshFile(project, file)
        }
        return IntentSummaryPipeline(project, adapter, cacheService, observer = observer)
            .also { activePipeline = it }
    }

    private fun populateCachedSummaries(model: EnglishModel): EnglishModel {
        var changed = false
        fun populate(block: EnglishBlock): EnglishBlock {
            val children = block.children.map(::populate)
            val summary = block.summary ?: readCachedSummary(project, cacheService, block.psiHash)
            if (summary != block.summary || children != block.children) changed = true
            return block.copy(summary = summary, children = children)
        }
        val blocks = model.blocks.map(::populate)
        return if (changed) model.copy(blocks = blocks) else model
    }

    private fun projectSummaries(file: PyFile, model: EnglishModel, profile: Profile, cacheKey: CacheKey): EnglishModel {
        if (profile != Profile.INTENT_SUMMARY) return model
        val enrichedModel = withCachedSummaries(model)
        scheduleIntentSummaries(file, enrichedModel, cacheKey)
        return enrichedModel
    }

    private fun scheduleIntentSummaries(file: PyFile, model: EnglishModel, cacheKey: CacheKey) {
        if (!hasMissingSummary(model)) return
        val now = System.currentTimeMillis()
        synchronized(lookupLock) {
            failedSummaryRetryAfter[cacheKey]?.let { retryAfter ->
                if (now < retryAfter) return
                failedSummaryRetryAfter.remove(cacheKey)
            }
            if (!scheduledSummaryKeys.add(cacheKey)) return
        }
        val publisher = project.messageBus.syncPublisher(ReaderUiEvents.TOPIC)
        publisher.summaryStarted(project)
        createConfiguredAdapterAsync { adapter ->
            pipelineFor(adapter, onFailure = {
                synchronized(lookupLock) {
                    failedSummaryRetryAfter[cacheKey] = System.currentTimeMillis() + SUMMARY_FAILURE_COOLDOWN_MILLIS
                }
            }).process(file, model) {
                synchronized(lookupLock) {
                    scheduledSummaryKeys.remove(cacheKey)
                }
                publisher.summaryFinished(project)
            }
        }
    }

    private fun hasMissingSummary(model: EnglishModel): Boolean {
        fun hasMissing(blocks: List<EnglishBlock>): Boolean {
            return blocks.any { block ->
                block.summary == null || hasMissing(block.children)
            }
        }
        return hasMissing(model.blocks)
    }

    private fun findBlockAtOffset(blocks: List<EnglishBlock>, offset: Int): EnglishBlock? {
        for (block in blocks) {
            if (block.textRange.contains(offset)) {
                return findBlockAtOffset(block.children, offset) ?: block
            }
        }
        return null
    }

    private fun modelHash(model: EnglishModel): String {
        val blockHashes = buildList {
            fun collect(blocks: List<dev.pygloss.model.EnglishBlock>) {
                for (block in blocks) {
                    add("${block.stableId}:${block.psiHash}")
                    collect(block.children)
                }
            }
            collect(model.blocks)
        }
        return PsiHash.sha256Hex(blockHashes.joinToString("|"))
    }

    private data class FileLookupKey(
        val fileId: String,
        val profile: Profile,
        val level: VerbosityLevel
    )

    private data class FileLookupValue(
        val modificationStamp: Long,
        val cacheKey: dev.pygloss.cache.CacheKey
    )

    companion object {
        private val SUMMARY_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)
        private const val SUMMARY_FAILURE_COOLDOWN_MILLIS = 60_000L

        /** Return the project model service. */
        @JvmStatic
        fun getInstance(project: Project): EnglishModelService =
            project.service<EnglishModelService>()
    }
}
