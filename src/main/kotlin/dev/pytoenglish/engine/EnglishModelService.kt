package dev.pytoenglish.engine

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiModificationTracker
import com.jetbrains.python.psi.PyFile
import dev.pytoenglish.cache.ModelCacheService
import dev.pytoenglish.cache.Profile
import dev.pytoenglish.cache.VerbosityLevel
import dev.pytoenglish.llm.AnthropicAdapter
import dev.pytoenglish.llm.IntentSummaryPipeline
import dev.pytoenglish.llm.JdkHttpClient
import dev.pytoenglish.llm.LlmAdapter
import dev.pytoenglish.llm.OpenAiCompatAdapter
import dev.pytoenglish.llm.SettingsSnapshot
import dev.pytoenglish.model.EnglishBlock
import dev.pytoenglish.model.EnglishModel
import dev.pytoenglish.settings.ProviderType
import dev.pytoenglish.settings.PyEnglishSettings
import dev.pytoenglish.settings.SecretStore

/** Project-level entry point that builds and caches English models for renderers. */
@Service(Service.Level.PROJECT)
class EnglishModelService(private val project: Project) {

    private val cacheService: ModelCacheService get() = ModelCacheService.getInstance(project)
    private val lookupLock = Any()
    private val lastFileLookups = mutableMapOf<FileLookupKey, FileLookupValue>()
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
        val modificationCount = currentPsiModificationCount()
        synchronized(lookupLock) { lastFileLookups[lookupKey] }?.let { previous ->
            if (previous.modificationCount == modificationCount) {
                cacheService.get(previous.cacheKey)?.let { return projectSummaries(it, profile) }
            }
        }

        val currentModel = BlockDetector.detect(file)
        val normalizedHash = modelHash(currentModel)
        val cacheKey = cacheService.keyFor(normalizedHash, profile, level)
        synchronized(lookupLock) {
            lastFileLookups[lookupKey] = FileLookupValue(modificationCount, cacheKey)
        }

        return projectSummaries(cacheService.getOrBuild(cacheKey) { currentModel }, profile)
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
    fun regenerate(file: PyFile, model: EnglishModel, block: EnglishBlock, adapter: LlmAdapter) {
        val pipeline = pipelineFor(adapter)
        pipeline.clearSummary(block.psiHash)
        cacheService.markBlockStale(block.stableId)
        pipeline.process(file, model)
    }

    /** Build an adapter from persisted settings and the API key loaded off-EDT. */
    fun createConfiguredAdapter(apiKey: String): LlmAdapter {
        val settings = PyEnglishSettings.getInstance()
        val snapshot = SettingsSnapshot(
            baseUrl = settings.baseUrl,
            model = settings.model,
            apiKey = apiKey,
        )
        return when (settings.provider) {
            ProviderType.OPENAI_COMPAT -> OpenAiCompatAdapter(snapshot, JdkHttpClient())
            ProviderType.ANTHROPIC -> AnthropicAdapter(snapshot, JdkHttpClient())
        }
    }

    /** Load the configured API key on a pooled thread, then pass a configured adapter to [callback]. */
    fun createConfiguredAdapterAsync(callback: (LlmAdapter) -> Unit) {
        SecretStore.getInstance().getApiKeyAsync { apiKey ->
            callback(createConfiguredAdapter(apiKey))
        }
    }

    private fun pipelineFor(adapter: LlmAdapter): IntentSummaryPipeline {
        activePipeline?.cancelProcessing()
        return IntentSummaryPipeline(project, adapter, cacheService).also { activePipeline = it }
    }

    private fun populateCachedSummaries(model: EnglishModel): EnglishModel {
        var changed = false
        fun populate(block: EnglishBlock): EnglishBlock {
            val children = block.children.map(::populate)
            val summary = block.summary ?: cacheService.readLlmBody(block.psiHash)
            if (summary != block.summary || children != block.children) changed = true
            return block.copy(summary = summary, children = children)
        }
        val blocks = model.blocks.map(::populate)
        return if (changed) model.copy(blocks = blocks) else model
    }

    private fun projectSummaries(model: EnglishModel, profile: Profile): EnglishModel {
        return if (profile == Profile.INTENT_SUMMARY) withCachedSummaries(model) else model
    }

    private fun modelHash(model: EnglishModel): String {
        val blockHashes = buildList {
            fun collect(blocks: List<dev.pytoenglish.model.EnglishBlock>) {
                for (block in blocks) {
                    add("${block.stableId}:${block.psiHash}")
                    collect(block.children)
                }
            }
            collect(model.blocks)
        }
        return PsiHash.sha256Hex(blockHashes.joinToString("|"))
    }

    private fun currentPsiModificationCount(): Long {
        return try {
            PsiModificationTracker.getInstance(project).modificationCount
        } catch (_: RuntimeException) {
            -1L
        }
    }

    private data class FileLookupKey(
        val fileId: String,
        val profile: Profile,
        val level: VerbosityLevel
    )

    private data class FileLookupValue(
        val modificationCount: Long,
        val cacheKey: dev.pytoenglish.cache.CacheKey
    )

    companion object {
        /** Return the project model service. */
        @JvmStatic
        fun getInstance(project: Project): EnglishModelService =
            project.service<EnglishModelService>()
    }
}
