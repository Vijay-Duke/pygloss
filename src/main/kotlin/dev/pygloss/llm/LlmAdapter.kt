package dev.pygloss.llm

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient as JdkClient
import java.net.http.HttpRequest as JdkRequest
import java.net.http.HttpResponse as JdkResponse
import java.time.Duration

/**
 * Typed result of an LLM call that never throws provider failures to callers.
 */
sealed interface LlmResult {
    /** Successful response with the generated text. */
    data class Success(val text: String) : LlmResult
    /** 401 / invalid API key. */
    data object AuthError : LlmResult
    /** Connection refused / DNS failure / socket error. */
    data object NetworkError : LlmResult
    /** Response body did not match the expected JSON shape. */
    data object ParseError : LlmResult
    /** HTTP 429 — rate-limited by the provider. */
    data object RateLimited : LlmResult
    /** Request exceeded the configured timeout. */
    data object Timeout : LlmResult
}

/**
 * Request envelope for an LLM summarize call.
 */
data class LlmRequest(
    val prompt: String,
    val systemPrompt: String = "You are a helpful code-reading assistant.",
    val maxTokens: Int = 1024,
)

/**
 * Abstraction over raw HTTP so adapters can be tested without a network.
 */
fun interface HttpClient {
    /**
     * Execute an HTTP request and return the raw response.
     * Implementations must throw on connection-level failures.
     */
    fun execute(request: HttpRequest): HttpResponse
}

/**
 * Default HTTP client for real provider calls.
 */
class JdkHttpClient(
    private val connectTimeout: Duration = Duration.ofSeconds(10),
    private val requestTimeout: Duration = Duration.ofSeconds(60),
) : HttpClient {
    private val client: JdkClient = JdkClient.newBuilder()
        .connectTimeout(connectTimeout)
        .build()

    override fun execute(request: HttpRequest): HttpResponse {
        val builder = JdkRequest.newBuilder(URI.create(request.url))
            .timeout(requestTimeout)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val jdkRequest = when (request.method) {
            "POST" -> builder.POST(JdkRequest.BodyPublishers.ofString(request.body)).build()
            else -> throw IOException("Unsupported HTTP method: ${request.method}")
        }
        val response = client.send(jdkRequest, JdkResponse.BodyHandlers.ofString())
        return HttpResponse(response.statusCode(), response.body())
    }
}

/**
 * Minimal HTTP request representation.
 */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

/**
 * Minimal HTTP response representation.
 */
data class HttpResponse(
    val statusCode: Int,
    val body: String,
)

/**
 * Immutable provider settings snapshot passed to adapters.
 */
data class SettingsSnapshot(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
)

/**
 * Unified interface for non-streaming LLM provider adapters.
 */
interface LlmAdapter {
    /** Send a summarize request and return a typed result. */
    fun summarize(request: LlmRequest): LlmResult
}
