package dev.pygloss.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests for the OpenAI-compatible LLM adapter.
 * All assertions use a mocked [HttpClient] — no real network calls.
 */
class OpenAiCompatAdapterTest {

    private val defaultSettings = SettingsSnapshot(
        baseUrl = "http://localhost:11434/v1",
        model = "gpt-oss:20b",
        apiKey = "test-key-abc",
    )

    // ---- request building ----

    @Test
    fun `builds correct request path and authorization header`() {
        var captured: HttpRequest? = null
        val mock = HttpClient { req ->
            captured = req
            HttpResponse(200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }
        val adapter = OpenAiCompatAdapter(defaultSettings, mock)
        adapter.summarize(LlmRequest(prompt = "hello"))

        assertEquals("POST", captured!!.method)
        assertEquals("http://localhost:11434/v1/chat/completions", captured!!.url)
        assertEquals("Bearer test-key-abc", captured!!.headers["Authorization"])
    }

    @Test
    fun `sends model and messages array in body`() {
        var captured: HttpRequest? = null
        val mock = HttpClient { req ->
            captured = req
            HttpResponse(200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }
        val adapter = OpenAiCompatAdapter(defaultSettings, mock)
        adapter.summarize(LlmRequest(prompt = "explain this", systemPrompt = "sys"))

        val body = captured!!.body
        assertTrue("Body contains model", body.contains(""""model":"gpt-oss:20b""""))
        assertTrue("Body contains user message", body.contains(""""role":"user""""))
        assertTrue("Body contains system message", body.contains(""""role":"system""""))
        assertTrue("Body contains prompt text", body.contains("explain this"))
    }

    @Test
    fun `max_tokens is present in request body`() {
        var captured: HttpRequest? = null
        val mock = HttpClient { req ->
            captured = req
            HttpResponse(200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }
        val adapter = OpenAiCompatAdapter(defaultSettings, mock)
        adapter.summarize(LlmRequest(prompt = "test", maxTokens = 8))

        assertTrue("Body contains max_tokens", captured!!.body.contains(""""max_tokens":8"""))
    }

    @Test
    fun `escapes all JSON control characters in request body`() {
        var captured: HttpRequest? = null
        val mock = HttpClient { req ->
            captured = req
            HttpResponse(200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }
        val adapter = OpenAiCompatAdapter(defaultSettings, mock)

        adapter.summarize(LlmRequest(prompt = "line${'\b'}${'\u000C'}${'\u0001'}end"))

        val body = captured!!.body
        assertTrue("Body escapes backspace", body.contains("\\b"))
        assertTrue("Body escapes form feed", body.contains("\\f"))
        assertTrue("Body escapes remaining controls as unicode", body.contains("\\u0001"))
        assertTrue("Body must not contain raw control characters", body.none { it.code < 0x20 })
    }

    // ---- successful response parsing ----

    @Test
    fun `parses choices0_message_content from 200 response`() {
        val mock = HttpClient {
            HttpResponse(200, """{"choices":[{"message":{"content":"The answer is 42."}}]}""")
        }
        val adapter = OpenAiCompatAdapter(defaultSettings, mock)
        val result = adapter.summarize(LlmRequest(prompt = "what is it"))

        assertTrue("Result is Success", result is LlmResult.Success)
        assertEquals("The answer is 42.", (result as LlmResult.Success).text)
    }

    @Test
    fun `falls back to reasoning when message content is null`() {
        val mock = HttpClient {
            HttpResponse(200, """{"choices":[{"message":{"content":null,"reasoning":"Reasoning-only answer."}}]}""")
        }
        val adapter = OpenAiCompatAdapter(defaultSettings, mock)
        val result = adapter.summarize(LlmRequest(prompt = "what is it"))

        assertTrue("Result is Success", result is LlmResult.Success)
        assertEquals("Reasoning-only answer.", (result as LlmResult.Success).text)
    }

    @Test
    fun `keeps message content when reasoning is also present`() {
        val mock = HttpClient {
            HttpResponse(200, """{"choices":[{"message":{"content":"Visible answer.","reasoning":"Hidden reasoning."}}]}""")
        }
        val adapter = OpenAiCompatAdapter(defaultSettings, mock)
        val result = adapter.summarize(LlmRequest(prompt = "what is it"))

        assertTrue("Result is Success", result is LlmResult.Success)
        assertEquals("Visible answer.", (result as LlmResult.Success).text)
    }

    // ---- error paths ----

    @Test
    fun `401 response returns AuthError`() {
        val mock = HttpClient { HttpResponse(401, """{"error":"unauthorized"}""") }
        val adapter = OpenAiCompatAdapter(defaultSettings, mock)
        val result = adapter.summarize(LlmRequest(prompt = "test"))
        assertEquals(LlmResult.AuthError, result)
    }

    @Test
    fun `connection failure returns NetworkError`() {
        val mock = HttpClient { throw java.net.ConnectException("Connection refused") }
        val adapter = OpenAiCompatAdapter(defaultSettings, mock)
        val result = adapter.summarize(LlmRequest(prompt = "test"))
        assertEquals(LlmResult.NetworkError, result)
    }

    @Test
    fun `malformed JSON response returns ParseError`() {
        val mock = HttpClient { HttpResponse(200, "NOT JSON AT ALL") }
        val adapter = OpenAiCompatAdapter(defaultSettings, mock)
        val result = adapter.summarize(LlmRequest(prompt = "test"))
        assertEquals(LlmResult.ParseError, result)
    }

    @Test
    fun `200 response without content or reasoning returns ParseError`() {
        val mock = HttpClient { HttpResponse(200, """{"error":{"message":"temporary overload"}}""") }
        val adapter = OpenAiCompatAdapter(defaultSettings, mock)
        val result = adapter.summarize(LlmRequest(prompt = "test"))
        assertEquals(LlmResult.ParseError, result)
    }

    @Test
    fun `429 response returns RateLimited`() {
        var callCount = 0
        val mock = HttpClient {
            callCount += 1
            HttpResponse(429, """{"error":"rate limited"}""")
        }
        val adapter = OpenAiCompatAdapter(defaultSettings, mock)
        val result = adapter.summarize(LlmRequest(prompt = "test"))
        assertEquals(LlmResult.RateLimited, result)
        assertEquals(2, callCount)
    }

    @Test
    fun `transient server response is retried before success`() {
        var callCount = 0
        val mock = HttpClient {
            callCount += 1
            if (callCount == 1) {
                HttpResponse(503, """{"error":"busy"}""")
            } else {
                HttpResponse(200, """{"choices":[{"message":{"content":"ok after retry"}}]}""")
            }
        }
        val adapter = OpenAiCompatAdapter(defaultSettings, mock)

        val result = adapter.summarize(LlmRequest(prompt = "test"))

        assertTrue(result is LlmResult.Success)
        assertEquals("ok after retry", (result as LlmResult.Success).text)
        assertEquals(2, callCount)
    }

    @Test
    fun `slow response returns Timeout`() {
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val mock = HttpClient {
            requestStarted.countDown()
            releaseResponse.await(2, TimeUnit.SECONDS)
            HttpResponse(200, """{"choices":[{"message":{"content":"late"}}]}""")
        }
        val adapter = OpenAiCompatAdapter(defaultSettings, mock, timeout = Duration.ofMillis(10))
        try {
            val result = adapter.summarize(LlmRequest(prompt = "test"))
            assertTrue("HTTP request should start", requestStarted.await(1, TimeUnit.SECONDS))
            assertEquals(LlmResult.Timeout, result)
        } finally {
            releaseResponse.countDown()
        }
    }

    @Test
    fun `JDK request timeout returns Timeout`() {
        val mock = HttpClient { throw HttpTimeoutException("request timed out") }
        val adapter = OpenAiCompatAdapter(defaultSettings, mock)

        val result = adapter.summarize(LlmRequest(prompt = "test"))

        assertEquals(LlmResult.Timeout, result)
    }

    @Test
    fun `JDK client accepts separate connect and request timeouts`() {
        JdkHttpClient(
            connectTimeout = Duration.ofSeconds(3),
            requestTimeout = Duration.ofSeconds(60),
        )
    }

    // ---- edge cases ----

    @Test
    fun `base URL trailing slash is normalized`() {
        var captured: HttpRequest? = null
        val mock = HttpClient { req ->
            captured = req
            HttpResponse(200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }
        val settings = defaultSettings.copy(baseUrl = "http://localhost:11434/v1/")
        val adapter = OpenAiCompatAdapter(settings, mock)
        adapter.summarize(LlmRequest(prompt = "test"))

        assertEquals("http://localhost:11434/v1/chat/completions", captured!!.url)
    }

    @Test
    fun `empty choices array returns ParseError`() {
        val mock = HttpClient { HttpResponse(200, """{"choices":[]}""") }
        val adapter = OpenAiCompatAdapter(defaultSettings, mock)
        val result = adapter.summarize(LlmRequest(prompt = "test"))
        assertEquals(LlmResult.ParseError, result)
    }
}
