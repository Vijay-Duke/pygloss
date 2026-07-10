package dev.pygloss.llm

import com.intellij.openapi.project.Project
import dev.pygloss.engine.PsiHash
import dev.pygloss.settings.PyGlossProjectSettings

internal const val LINE_PROMPT_VERSION = 1

internal fun lineCacheKey(project: Project, statementText: String): String {
    val input = "${statementText}|v$LINE_PROMPT_VERSION|${promptSignature(project)}"
    return "line:" + PsiHash.sha256Hex(input)
}

internal object LineTranslationPrompt {
    fun request(project: Project, statements: List<String>): LlmRequest {
        val settings = PyGlossProjectSettings.getInstance(project)
        return LlmRequest(
            prompt = buildPrompt(statements, settings),
            systemPrompt = IntentSummaryPrompt.buildSystemPrompt(settings),
            maxTokens = 2048,
        )
    }

    fun buildPrompt(statements: List<String>, settings: PyGlossProjectSettings): String {
        return buildString {
            appendLine("Line Translation prompt v$LINE_PROMPT_VERSION.")
            appendDomainContext(settings.domainDescription)
            appendAudience(settings.explainStyle)
            appendStyle(settings.explainStyle)
            appendLine("Translate each numbered Python statement into one short plain-English instruction for someone who does not know programming.")
            appendLine("Keep quoted text and numbers exactly as written.")
            appendLine("Reply with the same numbers, one line each, nothing else.")
            appendLine()
            statements.forEachIndexed { index, statement ->
                appendLine("${index + 1}. $statement")
            }
        }
    }

    fun parseResponse(response: String): Map<Int, String> {
        val pattern = Regex("""^(\d+)[.:)]\s*(.+)$""")
        return response.lineSequence()
            .mapNotNull { line ->
                val match = pattern.find(line.trim()) ?: return@mapNotNull null
                match.groupValues[1].toIntOrNull()?.let { it to match.groupValues[2].trim() }
            }
            .toMap()
    }
}
