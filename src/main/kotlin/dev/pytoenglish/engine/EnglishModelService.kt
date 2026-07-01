package dev.pytoenglish.engine

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiModificationTracker
import com.jetbrains.python.psi.PyFile
import dev.pytoenglish.cache.ModelCacheService
import dev.pytoenglish.cache.Profile
import dev.pytoenglish.cache.VerbosityLevel
import dev.pytoenglish.model.EnglishModel

/** Project-level entry point that builds and caches English models for renderers. */
@Service(Service.Level.PROJECT)
class EnglishModelService(private val project: Project) {

    private val cacheService: ModelCacheService get() = ModelCacheService.getInstance(project)
    private val lastFileLookups = mutableMapOf<FileLookupKey, FileLookupValue>()

    /** Return the English model for [file], using cached data whenever the semantic key matches. */
    fun getModel(
        file: PyFile,
        profile: Profile = Profile.POLYGLOT_LENS,
        level: VerbosityLevel = VerbosityLevel.HINTS
    ): EnglishModel {
        cacheService.processInvalidation()

        val fileId = file.virtualFile?.path ?: file.name
        val lookupKey = FileLookupKey(fileId, profile, level)
        val modificationCount = currentPsiModificationCount()
        lastFileLookups[lookupKey]?.let { previous ->
            if (previous.modificationCount == modificationCount) {
                cacheService.get(previous.cacheKey)?.let { return it }
            }
        }

        val currentModel = BlockDetector.detect(file)
        val normalizedHash = modelHash(currentModel)
        val cacheKey = cacheService.keyFor(normalizedHash, profile, level)
        lastFileLookups[lookupKey] = FileLookupValue(modificationCount, cacheKey)

        return cacheService.getOrBuild(cacheKey) { currentModel }
    }

    /** Build a fresh deterministic model without consulting the cache. */
    fun buildFresh(file: PyFile): EnglishModel {
        return BlockDetector.detect(file)
    }

    /** U8 hook for asynchronous LLM enrichment; intentionally not wired in U4. */
    fun enrich(model: EnglishModel, file: PyFile): EnglishModel {
        // TODO(U8): summarize blocks through the configured LLM adapter and cache by block hash.
        return model
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
