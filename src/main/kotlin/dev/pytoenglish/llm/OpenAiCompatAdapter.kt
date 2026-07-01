package dev.pytoenglish.llm

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
        return """{"model":${JsonSupport.quote(settings.model)},"messages":$messages}"""
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
    fun executeWithRetry(
        httpClient: HttpClient,
        request: HttpRequest,
        timeout: Duration,
        maxAttempts: Int,
    ): HttpOutcome {
        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            when (val outcome = executeOnce(httpClient, request, timeout)) {
                is HttpOutcome.Response -> return outcome
                HttpOutcome.Timeout -> return HttpOutcome.Timeout
                HttpOutcome.Network -> if (attempt == maxAttempts.coerceAtLeast(1) - 1) return HttpOutcome.Network
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
                "content" -> {
                    reader.beginArray()
                    if (!reader.hasNext()) return@readJson null
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "text" -> return@readJson reader.nextString()
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

    private fun messageContent(reader: JsonReaderEx): String? {
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "content" -> return reader.nextString()
                else -> reader.skipValue()
            }
        }
        return null
    }

    private fun <T> readJson(body: String, block: (JsonReaderEx) -> T?): T? {
        return try {
            JsonReaderEx(body).use { reader -> block(reader) }
        } catch (_: Exception) {
            null
        }
    }
}
