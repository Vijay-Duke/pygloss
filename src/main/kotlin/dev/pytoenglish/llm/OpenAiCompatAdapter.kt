package dev.pytoenglish.llm

import com.google.gson.stream.JsonToken
import org.jetbrains.io.JsonReaderEx
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * OpenAI-compatible LLM adapter.
 * Covers Ollama, OpenAI, Groq, Together, OpenRouter, LM Studio, vLLM.
 */
class OpenAiCompatAdapter(
    private val settings: SettingsSnapshot,
    private val httpClient: HttpClient,
    private val timeout: Duration = Duration.ofSeconds(20),
    private val maxAttempts: Int = 2,
) : LlmAdapter {

    override fun summarize(request: LlmRequest): LlmResult {
        val httpRequest = HttpRequest(
            method = "POST",
            url = settings.baseUrl.trimEnd('/') + "/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer ${settings.apiKey}",
                "Content-Type" to "application/json",
            ),
            body = buildRequestBody(request),
        )
        val response = when (val outcome = HttpRunner.executeWithRetry(httpClient, httpRequest, timeout, maxAttempts)) {
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
        val messages = buildString {
            append("[")
            append("""{"role":"system","content":${JsonSupport.quote(request.systemPrompt)}}""")
            append(",")
            append("""{"role":"user","content":${JsonSupport.quote(request.prompt)}}""")
            append("]")
        }
        return """{"model":${JsonSupport.quote(settings.model)},"max_tokens":${request.maxTokens},"messages":$messages}"""
    }

    private fun parseSuccess(body: String): LlmResult {
        val text = JsonSupport.openAiContent(body)
        return if (text == null) {
            LlmResult.ParseError
        } else {
            LlmResult.Success(text)
        }
    }
}

internal sealed interface HttpOutcome {
    data class Response(val response: HttpResponse) : HttpOutcome
    data object Network : HttpOutcome
    data object Timeout : HttpOutcome
}

internal object HttpRunner {
    private const val NETWORK_RETRY_BACKOFF_MILLIS = 300L

    fun executeWithRetry(
        httpClient: HttpClient,
        request: HttpRequest,
        timeout: Duration,
        maxAttempts: Int,
    ): HttpOutcome {
        val attempts = maxAttempts.coerceAtLeast(1)
        repeat(attempts) { attempt ->
            when (val outcome = executeOnce(httpClient, request, timeout)) {
                is HttpOutcome.Response -> return outcome
                HttpOutcome.Timeout -> return HttpOutcome.Timeout
                HttpOutcome.Network -> {
                    if (attempt == attempts - 1) return HttpOutcome.Network
                    try {
                        Thread.sleep(NETWORK_RETRY_BACKOFF_MILLIS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return HttpOutcome.Network
                    }
                }
            }
        }
        return HttpOutcome.Network
    }

    private fun executeOnce(httpClient: HttpClient, request: HttpRequest, timeout: Duration): HttpOutcome {
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<HttpResponse> { httpClient.execute(request) }
        return try {
            HttpOutcome.Response(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS))
        } catch (_: TimeoutException) {
            future.cancel(true)
            HttpOutcome.Timeout
        } catch (e: ExecutionException) {
            when (e.cause) {
                is SocketTimeoutException -> HttpOutcome.Timeout
                is ConnectException, is IOException -> HttpOutcome.Network
                else -> HttpOutcome.Network
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            HttpOutcome.Network
        } finally {
            executor.shutdownNow()
        }
    }
}

internal object JsonSupport {
    fun quote(value: String): String {
        val escaped = buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
        return "\"$escaped\""
    }

    fun openAiContent(body: String): String? = readJson(body) { reader ->
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "choices" -> {
                    reader.beginArray()
                    if (!reader.hasNext()) return@readJson null
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "message" -> return@readJson messageContent(reader)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        null
    }

    fun anthropicContent(body: String): String? = readJson(body) { reader ->
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "content" -> return@readJson anthropicTextContent(reader)
                else -> reader.skipValue()
            }
        }
        null
    }

    private fun messageContent(reader: JsonReaderEx): String? {
        var content: String? = null
        var reasoning: String? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "content" -> content = reader.nextNullableJsonString()
                "reasoning" -> reasoning = reader.nextNullableJsonString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        return content.takeUnlessNullOrBlank() ?: reasoning.takeUnlessNullOrBlank()
    }

    private fun anthropicTextContent(reader: JsonReaderEx): String? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return null
        }

        var fallbackText: String? = null
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }

            val block = anthropicContentBlock(reader)
            if (block.type == "text" && block.text.isNotNullOrBlank()) {
                return block.text
            }
            if (fallbackText == null && block.text.isNotNullOrBlank()) {
                fallbackText = block.text
            }
        }
        reader.endArray()

        return fallbackText
    }

    private fun anthropicContentBlock(reader: JsonReaderEx): AnthropicContentBlock {
        var type: String? = null
        var text: String? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "type" -> type = reader.nextNullableJsonString()
                "text" -> text = reader.nextNullableJsonString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        return AnthropicContentBlock(type, text)
    }

    private data class AnthropicContentBlock(
        val type: String?,
        val text: String?,
    )

    private fun JsonReaderEx.nextNullableJsonString(): String? {
        return when (peek()) {
            JsonToken.NULL -> {
                nextNull()
                null
            }
            JsonToken.STRING -> nextString()
            else -> {
                skipValue()
                null
            }
        }
    }

    private fun String?.takeUnlessNullOrBlank(): String? {
        return takeIf { it.isNotNullOrBlank() }
    }

    private fun String?.isNotNullOrBlank(): Boolean {
        return this != null && isNotBlank()
    }

    private fun <T> readJson(body: String, block: (JsonReaderEx) -> T?): T? {
        return try {
            JsonReaderEx(body).use { reader -> block(reader) }
        } catch (_: Exception) {
            null
        }
    }
}
