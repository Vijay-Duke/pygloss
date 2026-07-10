package dev.pygloss.cache

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.util.PsiModificationTracker
import dev.pygloss.model.EnglishBlock
import dev.pygloss.model.EnglishModel
import java.util.LinkedHashMap

/** Project-level cache for deterministic models, LLM metadata, and stale block state. */
@Service(Service.Level.PROJECT)
class ModelCacheService(private val project: Project) : Disposable {

    /** Maximum number of models retained in memory before LRU eviction. */
    var maxCacheSize: Int = DEFAULT_MAX_CACHE_SIZE
        set(value) {
            field = value.coerceAtLeast(1)
            synchronized(lock) {
                trimToMaxSize()
            }
        }

    /** Current Intent Summary prompt version used for LLM-backed cache keys. */
    var promptVersion: Int = 1
        private set

    /** Current plugin version used for LLM-backed cache keys. */
    var pluginVersion: Int = 1
        private set

    private val lock = Any()
    private val models = object : LinkedHashMap<CacheKey, EnglishModel>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, EnglishModel>?): Boolean {
            return size > maxCacheSize
        }
    }
    private val latestModelByFileId = mutableMapOf<String, EnglishModel>()
    private val staleBlockIds = mutableSetOf<String>()
    private val llmBodyKeysByBlockHash = mutableMapOf<String, String>()
    private val pendingPsiChanges = mutableMapOf<String, Long>()
    private val diskStore = DiskStore.pluginDir()

    @Volatile
    private var lastPsiModificationCount: Long = currentPsiModificationCount()

    init {
        registerPsiListener()
    }

    /** Build a cache key, ignoring prompt/plugin versions for deterministic profiles. */
    fun keyFor(
        normalizedPsiHash: String,
        profile: Profile,
        level: VerbosityLevel
    ): CacheKey {
        return CacheKey(
            normalizedPsiHash = normalizedPsiHash,
            profile = profile,
            level = level,
            promptVersion = if (profile == Profile.INTENT_SUMMARY) promptVersion else 0,
            pluginVersion = if (profile == Profile.INTENT_SUMMARY) pluginVersion else 0
        )
    }

    /** Return a cached model for [key], or build, store, and return it exactly once. */
    fun getOrBuild(key: CacheKey, build: () -> EnglishModel): EnglishModel {
        synchronized(lock) {
            models[key]?.let { return it }
            val model = build()
            putLocked(key, model)
            return model
        }
    }

    /** Cache a model under [key]. */
    fun put(key: CacheKey, model: EnglishModel) {
        synchronized(lock) {
            putLocked(key, model)
        }
    }

    /** Return a cached model for [key], or null on miss. */
    fun get(key: CacheKey): EnglishModel? {
        synchronized(lock) {
            return models[key]
        }
    }

    /** Return true when a model is cached for [key]. */
    fun containsKey(key: CacheKey): Boolean {
        synchronized(lock) {
            return models.containsKey(key)
        }
    }

    /** Return true after a cached block has been invalidated and before it is regenerated. */
    fun isStale(blockId: String): Boolean {
        synchronized(lock) {
            return blockId in staleBlockIds
        }
    }

    /** Mark a single block stale, used by future regenerate actions and tests. */
    fun markBlockStale(blockId: String) {
        synchronized(lock) {
            staleBlockIds.add(blockId)
        }
    }

    /** Mark a block fresh after regeneration. */
    fun markBlockFresh(blockId: String) {
        synchronized(lock) {
            staleBlockIds.remove(blockId)
        }
    }

    /** Mark changed or removed blocks stale and drop only their LLM body mapping. */
    fun markChangedBlocksStale(previous: EnglishModel, current: EnglishModel) {
        val currentById = current.flattenBlocks().associateBy { it.stableId }
        synchronized(lock) {
            for (oldBlock in previous.flattenBlocks()) {
                val newBlock = currentById[oldBlock.stableId]
                if (newBlock == null || newBlock.psiHash != oldBlock.psiHash) {
                    staleBlockIds.add(oldBlock.stableId)
                    removeLlmBodyMappingLocked(oldBlock.psiHash)
                }
            }
        }
    }

    /** Store an LLM response body outside the project tree, keyed by block hash. */
    fun writeLlmBody(blockHash: String, body: String) {
        synchronized(lock) {
            val diskKey = llmBodyDiskKey(blockHash)
            diskStore.write(diskKey, body)
            llmBodyKeysByBlockHash[blockHash] = diskKey
        }
    }

    /** Read a previously stored LLM response body. */
    fun readLlmBody(blockHash: String): String? {
        synchronized(lock) {
            val diskKey = llmBodyKeysByBlockHash.getOrPut(blockHash) { llmBodyDiskKey(blockHash) }
            return diskStore.read(diskKey)
        }
    }

    /** Remove all cached models and stale state. */
    fun clearCache() {
        synchronized(lock) {
            models.clear()
            latestModelByFileId.clear()
            staleBlockIds.clear()
            llmBodyKeysByBlockHash.values.forEach(diskStore::delete)
            llmBodyKeysByBlockHash.clear()
            pendingPsiChanges.clear()
        }
    }

    /** Bump the prompt version and invalidate only LLM-backed entries. */
    fun setPromptVersion(version: Int) {
        synchronized(lock) {
            promptVersion = version
            evictLlmEntriesLocked()
        }
    }

    /** Bump the plugin version and invalidate only LLM-backed entries. */
    fun setPluginVersion(version: Int) {
        synchronized(lock) {
            pluginVersion = version
            evictLlmEntriesLocked()
        }
    }

    /** Number of models retained in memory. */
    fun cacheSize(): Int {
        synchronized(lock) {
            return models.size
        }
    }

    /** Snapshot of current cache keys for tests and diagnostics. */
    fun keysSnapshot(): Set<CacheKey> {
        synchronized(lock) {
            return models.keys.toSet()
        }
    }

    /** Process debounced PSI invalidations and update the observed modification count. */
    fun processInvalidation() {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val readyFileIds = pendingPsiChanges
                .filterValues { changedAt -> now - changedAt >= DEBOUNCE_MS }
                .keys
                .toList()
            for (fileId in readyFileIds) {
                latestModelByFileId[fileId]?.flattenBlocks()?.forEach { block ->
                    staleBlockIds.add(block.stableId)
                }
                pendingPsiChanges.remove(fileId)
            }
            lastPsiModificationCount = currentPsiModificationCount()
        }
    }

    /** Test hook mirroring the PSI listener path without editing disk. */
    fun simulatePsiChange(fileId: String) {
        synchronized(lock) {
            markFileDirtyLocked(fileId, changedAt = 0L)
        }
    }

    override fun dispose() {
        clearCache()
    }

    private fun putLocked(key: CacheKey, model: EnglishModel) {
        latestModelByFileId[model.fileId]?.let { previous ->
            if (previous !== model) {
                markChangedBlocksStale(previous, model)
            }
        }
        models[key] = model
        latestModelByFileId[model.fileId] = model
        // Do NOT clear stale flags here: a re-detect+put after a semantic edit must
        // leave changed blocks stale until their summary is regenerated. Stale is
        // cleared per-block by markBlockFresh() on regeneration (IntentSummaryPipeline).
        trimToMaxSize()
    }

    private fun registerPsiListener() {
        try {
            PsiManager.getInstance(project).addPsiTreeChangeListener(
                object : PsiTreeChangeAdapter() {
                    override fun childrenChanged(event: PsiTreeChangeEvent) {
                        markEventDirty(event)
                    }

                    override fun childAdded(event: PsiTreeChangeEvent) {
                        markEventDirty(event)
                    }

                    override fun childRemoved(event: PsiTreeChangeEvent) {
                        markEventDirty(event)
                    }

                    override fun childReplaced(event: PsiTreeChangeEvent) {
                        markEventDirty(event)
                    }

                    override fun childMoved(event: PsiTreeChangeEvent) {
                        markEventDirty(event)
                    }

                    override fun propertyChanged(event: PsiTreeChangeEvent) {
                        markEventDirty(event)
                    }
                },
                this
            )
        } catch (_: RuntimeException) {
            // Some unit-test fixtures initialize services before PSI events are available.
        }
    }

    private fun markEventDirty(event: PsiTreeChangeEvent) {
        val fileId = event.file?.virtualFile?.path ?: event.file?.name ?: return
        synchronized(lock) {
            markFileDirtyLocked(fileId, System.currentTimeMillis())
        }
    }

    private fun markFileDirtyLocked(fileId: String, changedAt: Long) {
        pendingPsiChanges[fileId] = changedAt
        latestModelByFileId[fileId]?.flattenBlocks()?.forEach { block ->
            staleBlockIds.add(block.stableId)
        }
    }

    private fun evictLlmEntriesLocked() {
        models.keys.filter { it.isLlmProfile }.toList().forEach(models::remove)
        llmBodyKeysByBlockHash.values.forEach(diskStore::delete)
        llmBodyKeysByBlockHash.clear()
    }

    private fun removeLlmBodyMappingLocked(blockHash: String) {
        val diskKey = llmBodyKeysByBlockHash.remove(blockHash) ?: llmBodyDiskKey(blockHash)
        diskStore.delete(diskKey)
    }

    private fun llmBodyDiskKey(blockHash: String): String {
        return "prompt-$promptVersion-plugin-$pluginVersion-$blockHash"
    }

    private fun trimToMaxSize() {
        while (models.size > maxCacheSize) {
            val eldest = models.keys.firstOrNull() ?: return
            models.remove(eldest)
        }
    }

    private fun currentPsiModificationCount(): Long {
        return try {
            PsiModificationTracker.getInstance(project).modificationCount
        } catch (_: RuntimeException) {
            0L
        }
    }

    private fun EnglishModel.flattenBlocks(): List<EnglishBlock> {
        val result = mutableListOf<EnglishBlock>()
        fun collect(blocks: List<EnglishBlock>) {
            for (block in blocks) {
                result.add(block)
                collect(block.children)
            }
        }
        collect(blocks)
        return result
    }

    companion object {
        private const val DEFAULT_MAX_CACHE_SIZE = 100
        private const val DEBOUNCE_MS = 300L

        /** Return the project cache service. */
        @JvmStatic
        fun getInstance(project: Project): ModelCacheService =
            project.service<ModelCacheService>()
    }
}
