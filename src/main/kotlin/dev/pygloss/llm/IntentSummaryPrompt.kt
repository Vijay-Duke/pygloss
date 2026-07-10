package dev.pygloss.llm

import com.intellij.openapi.project.Project
import dev.pygloss.cache.ModelCacheService
import dev.pygloss.engine.PsiHash
import dev.pygloss.settings.ExplainStyle
import dev.pygloss.settings.PyGlossProjectSettings

internal const val INTENT_SUMMARY_PROMPT_VERSION = 2

internal fun promptSignature(project: Project): String {
    val settings = PyGlossProjectSettings.getInstance(project)
    val input = "v$INTENT_SUMMARY_PROMPT_VERSION|${settings.explainStyle.name}|${settings.domainDescription.trim()}"
    return PsiHash.sha256Hex(input)
}

internal fun summaryCacheKey(project: Project, psiHash: String): String {
    return "$psiHash:${promptSignature(project)}"
}

internal fun readCachedSummary(project: Project, cacheService: ModelCacheService, psiHash: String): String? {
    return cacheService.readLlmBody(summaryCacheKey(project, psiHash))
}

internal fun writeCachedSummary(
    project: Project,
    cacheService: ModelCacheService,
    psiHash: String,
    summary: String
) {
    cacheService.writeLlmBody(summaryCacheKey(project, psiHash), summary)
}

internal object IntentSummaryPrompt {

    fun request(project: Project, snapshot: BlockSnapshot): LlmRequest {
        val settings = PyGlossProjectSettings.getInstance(project)
        return LlmRequest(
            prompt = buildPrompt(snapshot, settings),
            systemPrompt = buildSystemPrompt(settings)
        )
    }

    fun buildSystemPrompt(settings: PyGlossProjectSettings): String {
        return when (settings.explainStyle) {
            ExplainStyle.TECHNICAL ->
                "You summarize Python code intent for developers using deterministic PSI facts. Only state what the provided facts support."
            ExplainStyle.PLAIN,
            ExplainStyle.ANALOGIES ->
                "You summarize Python code intent for non-programming professionals in plain language. Only state what the provided facts support."
        }
    }

    fun buildPrompt(snapshot: BlockSnapshot, settings: PyGlossProjectSettings): String {
        return buildString {
            appendLine("Intent Summary prompt v$INTENT_SUMMARY_PROMPT_VERSION.")
            appendDomainContext(settings.domainDescription)
            appendAudience(settings.explainStyle)
            appendStyle(settings.explainStyle)
            appendLine("Grounding:")
            appendLine("Only state what the provided facts support. When unsure of the purpose, describe the action in plain words rather than guessing.")
            appendLine()
            appendLine("Shape:")
            appendLine("Reply with one sentence, at most 28 words, starting with a verb.")
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
}

internal fun StringBuilder.appendDomainContext(domainDescription: String) {
    val domain = domainDescription.trim()
    if (domain.isNotBlank()) {
        appendLine("Project context: $domain")
        appendLine()
    }
}

internal fun StringBuilder.appendAudience(style: ExplainStyle) {
    if (style == ExplainStyle.TECHNICAL) return
    appendLine("Audience:")
    appendLine(AUDIENCE_BLOCK)
    appendLine()
}

internal fun StringBuilder.appendStyle(style: ExplainStyle) {
    appendLine("Style:")
    when (style) {
        ExplainStyle.PLAIN -> appendLine("Use plain language.")
        ExplainStyle.ANALOGIES -> {
            appendLine("Use plain language.")
            appendLine("If a concept has no everyday name, add a short analogy in parentheses.")
        }
        ExplainStyle.TECHNICAL ->
            appendLine("Use developer-style wording. Programming terms and backticked identifiers are allowed.")
    }
    appendLine()
}

internal const val AUDIENCE_BLOCK =
    "The reader is a professional who does not know Python or programming. Never use programming jargon " +
        "(function, method, parameter, list, string, iterate, return). Never use backticked identifiers. " +
        "Refer to things by their role in plain words - 'the chart', 'the share price figures' - not variable names."
