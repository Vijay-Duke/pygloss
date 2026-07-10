package dev.pygloss.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests for the Anthropic LLM adapter.
 * All assertions use a mocked [HttpClient] — no real network calls.
 */
class AnthropicAdapterTest {

    private val defaultSettings = SettingsSnapshot(
        baseUrl = "https://api.anthropic.com",
        model = "claude-sonnet-4-20250514",
        apiKey = "sk-ant-test-key",
    )

    // ---- request building ----

    @Test
    fun `builds correct request path with x-api-key header`() {
        var captured: HttpRequest? = null
        val mock = HttpClient { req ->
            captured = req
            HttpResponse(200, """{"content":[{"text":"ok"}]}""")
        }
        val adapter = AnthropicAdapter(defaultSettings, mock)
        adapter.summarize(LlmRequest(prompt = "hello"))

        assertEquals("POST", captured!!.method)
        assertEquals("https://api.anthropic.com/v1/messages", captured!!.url)
        assertEquals("sk-ant-test-key", captured!!.headers["x-api-key"])
        assertEquals("2023-06-01", captured!!.headers["anthropic-version"])
    }

    @Test
    fun `uses Xiaomi api-key header for Xiaomi Anthropic-compatible endpoint`() {
        var captured: HttpRequest? = null
        val mock = HttpClient { req ->
            captured = req
            HttpResponse(200, """{"content":[{"text":"ok"}]}""")
        }
        val settings = defaultSettings.copy(baseUrl = "https://api.xiaomimimo.com/anthropic")
        val adapter = AnthropicAdapter(settings, mock)
        adapter.summarize(LlmRequest(prompt = "hello"))

        assertEquals("https://api.xiaomimimo.com/anthropic/v1/messages", captured!!.url)
        assertEquals("sk-ant-test-key", captured!!.headers["api-key"])
        assertEquals(null, captured!!.headers["x-api-key"])
        assertTrue("Body disables Xiaomi thinking", captured!!.body.contains(""""thinking":{"type":"disabled"}"""))
    }

    @Test
    fun `sends system prompt separately from messages`() {
        var captured: HttpRequest? = null
        val mock = HttpClient { req ->
            captured = req
            HttpResponse(200, """{"content":[{"text":"ok"}]}""")
        }
        val adapter = AnthropicAdapter(defaultSettings, mock)
        adapter.summarize(LlmRequest(prompt = "explain", systemPrompt = "You are concise."))

        val body = captured!!.body
        assertTrue("Body contains top-level system", body.contains(""""system":"You are concise.""""))
        assertTrue("Body contains user message", body.contains(""""role":"user""""))
        assertTrue("Body contains text content block", body.contains(""""type":"text""""))
        assertTrue("Body contains prompt text", body.contains("explain"))
        assertTrue("Body disables streaming", body.contains(""""stream":false"""))
    }

    @Test
    fun `max_tokens is present in request body`() {
        var captured: HttpRequest? = null
        val mock = HttpClient { req ->
            captured = req
            HttpResponse(200, """{"content":[{"text":"ok"}]}""")
        }
        val adapter = AnthropicAdapter(defaultSettings, mock)
        adapter.summarize(LlmRequest(prompt = "test", maxTokens = 2048))

        assertTrue("Body contains max_tokens", captured!!.body.contains(""""max_tokens":2048"""))
    }

    @Test
    fun `escapes all JSON control characters in request body`() {
        var captured: HttpRequest? = null
        val mock = HttpClient { req ->
            captured = req
            HttpResponse(200, """{"content":[{"text":"ok"}]}""")
        }
        val adapter = AnthropicAdapter(defaultSettings, mock)

        adapter.summarize(LlmRequest(prompt = "line${'\b'}${'\u000C'}${'\u0001'}end"))

        val body = captured!!.body
        assertTrue("Body escapes backspace", body.contains("\\b"))
        assertTrue("Body escapes form feed", body.contains("\\f"))
        assertTrue("Body escapes remaining controls as unicode", body.contains("\\u0001"))
        assertTrue("Body must not contain raw control characters", body.none { it.code < 0x20 })
    }

    // ---- successful response parsing ----

    @Test
    fun `parses content0_text from 200 response`() {
        val mock = HttpClient {
            HttpResponse(200, """{"content":[{"text":"Here is the summary."}]}""")
        }
        val adapter = AnthropicAdapter(defaultSettings, mock)
        val result = adapter.summarize(LlmRequest(prompt = "summarize"))

        assertTrue("Result is Success", result is LlmResult.Success)
        assertEquals("Here is the summary.", (result as LlmResult.Success).text)
    }

    @Test
    fun `parses first text block after thinking block`() {
        val mock = HttpClient {
            HttpResponse(
                200,
                """{"content":[{"type":"thinking","thinking":"Internal notes."},{"type":"text","text":"Here is the answer."}]}""",
            )
        }
        val adapter = AnthropicAdapter(defaultSettings, mock)
        val result = adapter.summarize(LlmRequest(prompt = "summarize"))

        assertTrue("Result is Success", result is LlmResult.Success)
        assertEquals("Here is the answer.", (result as LlmResult.Success).text)
    }

    // ---- error paths ----

    @Test
    fun `401 response returns AuthError`() {
        val mock = HttpClient { HttpResponse(401, """{"error":{"type":"auth_error"}}""") }
        val adapter = AnthropicAdapter(defaultSettings, mock)
        val result = adapter.summarize(LlmRequest(prompt = "test"))
        assertEquals(LlmResult.AuthError, result)
    }

    @Test
    fun `connection failure returns NetworkError`() {
        val mock = HttpClient { throw java.net.ConnectException("Connection refused") }
        val adapter = AnthropicAdapter(defaultSettings, mock)
        val result = adapter.summarize(LlmRequest(prompt = "test"))
        assertEquals(LlmResult.NetworkError, result)
    }

    @Test
    fun `malformed JSON response returns ParseError`() {
        val mock = HttpClient { HttpResponse(200, "GARBAGE") }
        val adapter = AnthropicAdapter(defaultSettings, mock)
        val result = adapter.summarize(LlmRequest(prompt = "test"))
        assertEquals(LlmResult.ParseError, result)
    }

    @Test
    fun `429 response returns RateLimited`() {
        val mock = HttpClient { HttpResponse(429, """{"error":{"type":"rate_limit_error"}}""") }
        val adapter = AnthropicAdapter(defaultSettings, mock)
        val result = adapter.summarize(LlmRequest(prompt = "test"))
        assertEquals(LlmResult.RateLimited, result)
    }

    @Test
    fun `transient server response is retried before success`() {
        var callCount = 0
        val mock = HttpClient {
            callCount += 1
            if (callCount == 1) {
                HttpResponse(503, """{"error":{"type":"overloaded_error"}}""")
            } else {
                HttpResponse(200, """{"content":[{"type":"text","text":"ok after retry"}]}""")
            }
        }
        val adapter = AnthropicAdapter(defaultSettings, mock)

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
            HttpResponse(200, """{"content":[{"text":"late"}]}""")
        }
        val adapter = AnthropicAdapter(defaultSettings, mock, timeout = Duration.ofMillis(10))
        try {
            val result = adapter.summarize(LlmRequest(prompt = "test"))
            assertTrue("HTTP request should start", requestStarted.await(1, TimeUnit.SECONDS))
            assertEquals(LlmResult.Timeout, result)
        } finally {
            releaseResponse.countDown()
        }
    }

    // ---- edge cases ----

    @Test
    fun `base URL trailing slash is normalized`() {
        var captured: HttpRequest? = null
        val mock = HttpClient { req ->
            captured = req
            HttpResponse(200, """{"content":[{"text":"ok"}]}""")
        }
        val settings = defaultSettings.copy(baseUrl = "https://api.anthropic.com/")
        val adapter = AnthropicAdapter(settings, mock)
        adapter.summarize(LlmRequest(prompt = "test"))

        assertEquals("https://api.anthropic.com/v1/messages", captured!!.url)
    }

    @Test
    fun `empty content array returns ParseError`() {
        val mock = HttpClient { HttpResponse(200, """{"content":[]}""") }
        val adapter = AnthropicAdapter(defaultSettings, mock)
        val result = adapter.summarize(LlmRequest(prompt = "test"))
        assertEquals(LlmResult.ParseError, result)
    }
}
