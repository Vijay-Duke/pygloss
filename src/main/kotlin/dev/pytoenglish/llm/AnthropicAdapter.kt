package dev.pytoenglish.llm

import java.time.Duration

/**
 * Anthropic Claude LLM adapter.
 */
class AnthropicAdapter(
    private val settings: SettingsSnapshot,
    private val httpClient: HttpClient,
    private val timeout: Duration = Duration.ofSeconds(20),
    private val maxAttempts: Int = 2,
) : LlmAdapter {

    override fun summarize(request: LlmRequest): LlmResult {
        val response = when (val outcome = HttpRunner.executeWithRetry(httpClient, buildHttpRequest(request), timeout, maxAttempts)) {
            is HttpOutcome.Response -> outcome.response
            HttpOutcome.Network -> return LlmResult.NetworkError
            HttpOutcome.Timeout -> return LlmResult.Timeout
        }

        return when (response.statusCode) {
            200 -> parseSuccess(response.body)
            401 -> LlmResult.AuthError
            429 -> LlmResult.RateLimited
            else -> LlmResult.NetworkError
        }
    }

    private fun buildRequestBody(request: LlmRequest): String {
        val messages = """[{"role":"user","content":${JsonSupport.quote(request.prompt)}}]"""
        return """{"model":${JsonSupport.quote(settings.model)},"max_tokens":${request.maxTokens},"system":${JsonSupport.quote(request.systemPrompt)},"messages":$messages}"""
    }

    private fun parseSuccess(body: String): LlmResult {
        val text = JsonSupport.anthropicContent(body)
        return if (text == null) {
            LlmResult.ParseError
        } else {
            LlmResult.Success(text)
        }
    }

    private fun buildHttpRequest(request: LlmRequest): HttpRequest = HttpRequest(
        method = "POST",
        url = settings.baseUrl.trimEnd('/') + "/v1/messages",
        headers = mapOf(
            "x-api-key" to settings.apiKey,
            "anthropic-version" to "2023-06-01",
            "Content-Type" to "application/json",
        ),
        body = buildRequestBody(request),
    )
}
