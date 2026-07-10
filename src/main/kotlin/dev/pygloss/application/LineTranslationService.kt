package dev.pygloss.application

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.jetbrains.python.psi.PyFile
import dev.pygloss.cache.ModelCacheService
import dev.pygloss.llm.LineTranslationPrompt
import dev.pygloss.llm.LlmAdapter
import dev.pygloss.llm.LlmResult
import dev.pygloss.llm.lineCacheKey
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

interface LineTranslationRequester {
    fun readTranslation(statementText: String): String?
    fun requestMissing(file: PyFile, statements: List<String>)
}

@Service(Service.Level.PROJECT)
open class LineTranslationService private constructor(
    private val project: Project,
    private val cacheService: ModelCacheService,
    private val executor: Executor,
    private val adapterProvider: ((LlmAdapter) -> Unit) -> Unit,
    private val debounceMillis: Long,
    @Suppress("UNUSED_PARAMETER") testConstructor: Boolean,
) : LineTranslationRequester {

    private val log = Logger.getInstance(LineTranslationService::class.java)
    private val generation = AtomicLong(0)
    private val lock = Any()
    private val generationByFile = mutableMapOf<String, Long>()
    private val pendingByFile = mutableMapOf<String, PendingRequest>()
    private val hotCache = linkedMapOf<String, String>()

    constructor(project: Project) : this(
        project = project,
        cacheService = ModelCacheService.getInstance(project),
        executor = AppExecutorUtil.getAppExecutorService(),
        adapterProvider = { callback: (LlmAdapter) -> Unit ->
            EnglishModelService.getInstance(project).createConfiguredAdapterAsync(callback)
        },
        debounceMillis = DEFAULT_DEBOUNCE_MILLIS,
        testConstructor = false,
    )

    internal constructor(
        project: Project,
        cacheService: ModelCacheService,
        executor: Executor,
        adapterProvider: ((LlmAdapter) -> Unit) -> Unit,
        debounceMillis: Long = 0L,
    ) : this(project, cacheService, executor, adapterProvider, debounceMillis, true)

    override fun readTranslation(statementText: String): String? {
        val key = lineCacheKey(project, statementText)
        return synchronized(lock) { hotCache[key] }
    }

    override fun requestMissing(file: PyFile, statements: List<String>) {
        val candidates = statements.distinct()
        executor.execute { prepareMissingRequest(file, candidates) }
    }

    private fun prepareMissingRequest(file: PyFile, statements: List<String>) {
        var loadedPersisted = false
        val missing = statements.filter { statement ->
            if (readTranslation(statement) != null) return@filter false
            val key = lineCacheKey(project, statement)
            val cached = cacheService.readLlmBody(key)?.takeIf { it.isNotBlank() }
            if (cached == null) {
                true
            } else {
                synchronized(lock) { rememberHotLocked(key, cached) }
                loadedPersisted = true
                false
            }
        }
        if (loadedPersisted && file.isValid) refresh(file)
        if (missing.isEmpty()) return

        val capped = missing.take(MAX_STATEMENTS_PER_FILE_PASS)
        if (missing.size > MAX_STATEMENTS_PER_FILE_PASS) {
            log.warn("Line translation capped at $MAX_STATEMENTS_PER_FILE_PASS statements for ${file.name}")
        }

        val fileId = file.virtualFile?.path ?: file.name
        val batchGeneration = generation.incrementAndGet()
        val modificationStamp = file.modificationStamp
        synchronized(lock) {
            generationByFile[fileId] = batchGeneration
            pendingByFile[fileId] = PendingRequest(file, capped, modificationStamp)
        }
        runAfterDebounce(fileId, batchGeneration)
    }

    private fun runAfterDebounce(fileId: String, batchGeneration: Long) {
        if (debounceMillis > 0) Thread.sleep(debounceMillis)
        val pending = synchronized(lock) {
            if (generationByFile[fileId] != batchGeneration) return
            pendingByFile.remove(fileId) ?: return
        }
        val publisher = project.messageBus.syncPublisher(ReaderUiEvents.TOPIC)
        publisher.summaryStarted(project)
        adapterProvider { adapter ->
            executor.execute {
                try {
                    processPending(fileId, batchGeneration, pending, adapter)
                } finally {
                    publisher.summaryFinished(project)
                }
            }
        }
    }

    private fun processPending(
        fileId: String,
        batchGeneration: Long,
        pending: PendingRequest,
        adapter: LlmAdapter,
    ) {
        var wroteAny = false
        var firstFailure: LlmResult? = null
        for (chunk in pending.statements.chunked(MAX_STATEMENTS_PER_REQUEST)) {
            if (isSuperseded(fileId, batchGeneration, pending)) return
            when (val result = adapter.summarize(LineTranslationPrompt.request(project, chunk))) {
                is LlmResult.Success -> {
                    if (isSuperseded(fileId, batchGeneration, pending)) return
                    if (writeSuccessfulChunk(chunk, result.text)) wroteAny = true
                }
                else -> if (firstFailure == null) {
                    firstFailure = result
                    reportFirstBatchFailure(result, pending.statements)
                }
            }
            if (isSuperseded(fileId, batchGeneration, pending)) return
        }
        if (firstFailure == null && wroteAny) {
            project.messageBus.syncPublisher(ReaderUiEvents.TOPIC).summarySucceeded(project)
        }
        if (wroteAny && !isSuperseded(fileId, batchGeneration, pending)) {
            refresh(pending.file)
        }
    }

    private fun writeSuccessfulChunk(statements: List<String>, response: String): Boolean {
        var wroteAny = false
        val translations = LineTranslationPrompt.parseResponse(response)
        logMissingTranslationsOnce(statements, translations)
        for ((index, statement) in statements.withIndex()) {
            val translated = translations[index + 1]?.trim()?.takeIf { it.isNotBlank() } ?: continue
            writeTranslation(statement, translated)
            wroteAny = true
        }
        return wroteAny
    }

    fun writeTranslation(statementText: String, translation: String) {
        val key = lineCacheKey(project, statementText)
        cacheService.writeLlmBody(key, translation)
        synchronized(lock) { rememberHotLocked(key, translation) }
    }

    protected open fun refresh(file: PyFile) {
        project.messageBus.syncPublisher(ReaderUiEvents.TOPIC).refreshFile(project, file)
    }

    private fun reportFirstBatchFailure(error: LlmResult, statements: List<String>) {
        val keys = statements.mapTo(mutableSetOf()) { lineCacheKey(project, it) }
        project.messageBus.syncPublisher(ReaderUiEvents.TOPIC).summaryFailed(project, error, keys)
    }

    private fun logMissingTranslationsOnce(statements: List<String>, translations: Map<Int, String>) {
        if ((1..statements.size).all { it in translations }) return
        log.warn("Line translation response omitted or could not parse one or more numbered lines.")
    }

    private fun isSuperseded(fileId: String, batchGeneration: Long, pending: PendingRequest): Boolean {
        return synchronized(lock) { generationByFile[fileId] != batchGeneration } ||
            pending.file.modificationStamp != pending.modificationStamp
    }

    private fun rememberHotLocked(key: String, value: String) {
        hotCache[key] = value
        while (hotCache.size > MAX_HOT_CACHE_SIZE) {
            hotCache.remove(hotCache.keys.first())
        }
    }

    private data class PendingRequest(
        val file: PyFile,
        val statements: List<String>,
        val modificationStamp: Long,
    )

    companion object {
        private const val DEFAULT_DEBOUNCE_MILLIS = 150L
        private const val MAX_STATEMENTS_PER_REQUEST = 60
        private const val MAX_STATEMENTS_PER_FILE_PASS = 400
        private const val MAX_HOT_CACHE_SIZE = 1000

        fun getInstance(project: Project): LineTranslationService = project.service()
    }
}
