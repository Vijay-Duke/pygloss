package dev.pygloss.settings

import com.intellij.util.xmlb.XmlSerializer
import org.jdom.output.XMLOutputter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [PyGlossSettings] state serialization.
 * Verifies non-secret fields round-trip and the API key is never persisted.
 */
class SettingsSerializationTest {

    @Test
    fun `provider settings validation accepts local and hosted HTTP endpoints`() {
        assertNull(validateProviderSettings("http://localhost:11434/v1", "gpt-oss:20b"))
        assertNull(validateProviderSettings("http://127.0.0.1:11434/v1", "gpt-oss:20b"))
        assertNull(validateProviderSettings("https://api.anthropic.com", "claude-sonnet"))
    }

    @Test
    fun `provider settings validation rejects unusable endpoints and blank models`() {
        assertEquals("Base URL is required.", validateProviderSettings(" ", "model"))
        assertEquals(
            "Base URL must be a complete http:// or https:// address.",
            validateProviderSettings("localhost:11434/v1", "model")
        )
        assertEquals(
            "Base URL must use http:// or https://.",
            validateProviderSettings("file:///tmp/model", "model")
        )
        assertEquals("Model is required.", validateProviderSettings("http://localhost:11434/v1", " "))
        assertEquals(
            "Remote provider URLs must use https:// to protect source code and API keys.",
            validateProviderSettings("http://api.example.com/v1", "model")
        )
    }

    @Test
    fun `fresh install defaults to OPENAI_COMPAT provider`() {
        val settings = PyGlossSettings()
        val state = settings.state
        assertNotNull("State should not be null", state)
        assertEquals(ProviderType.OPENAI_COMPAT, state!!.provider)
    }

    @Test
    fun `fresh install defaults to Ollama base URL`() {
        val settings = PyGlossSettings()
        val state = settings.state
        assertEquals("http://localhost:11434/v1", state!!.baseUrl)
    }

    @Test
    fun `fresh install defaults to gpt-oss model`() {
        val settings = PyGlossSettings()
        val state = settings.state
        assertEquals("gpt-oss:20b", state!!.model)
    }

    @Test
    fun `fresh install has empty API key value`() {
        assertEquals("", SecretStore.EMPTY_API_KEY)
    }

    @Test
    fun `getState returns non-null state object`() {
        val settings = PyGlossSettings()
        val state = settings.state
        assertNotNull("getState must return non-null for serialization", state)
    }

    @Test
    fun `round-trip preserves provider`() {
        val settings = PyGlossSettings()
        settings.loadState(PyGlossState(provider = ProviderType.ANTHROPIC))
        val roundTripped = settings.state
        assertEquals(ProviderType.ANTHROPIC, roundTripped!!.provider)
    }

    @Test
    fun `round-trip preserves baseUrl`() {
        val settings = PyGlossSettings()
        settings.loadState(PyGlossState(baseUrl = "https://custom.api/v1"))
        assertEquals("https://custom.api/v1", settings.state!!.baseUrl)
    }

    @Test
    fun `round-trip preserves model`() {
        val settings = PyGlossSettings()
        settings.loadState(PyGlossState(model = "gpt-4o"))
        assertEquals("gpt-4o", settings.state!!.model)
    }

    @Test
    fun `API key is not present in serialized state`() {
        val state = PyGlossState(
            provider = ProviderType.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            model = "claude-sonnet-4-20250514",
        )
        val xml = XMLOutputter().outputString(XmlSerializer.serialize(state))

        assertFalse(
            "API key must not leak into serialized state",
            xml.contains("sk-") || xml.contains("api-key") || xml.contains("apiKey")
        )
        assertFalse("State XML must not contain any password field", xml.contains("password", ignoreCase = true))
    }

    @Test
    fun `serialized XML round-trip preserves non-secret fields`() {
        val original = PyGlossState(
            provider = ProviderType.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            model = "claude-sonnet-4-20250514",
        )
        val xml = XmlSerializer.serialize(original)
        val deserialized = XmlSerializer.deserialize(xml, PyGlossState::class.java)

        assertEquals(original.provider, deserialized.provider)
        assertEquals(original.baseUrl, deserialized.baseUrl)
        assertEquals(original.model, deserialized.model)
    }

    @Test
    fun `loadState with empty values resolves to defaults`() {
        val settings = PyGlossSettings()
        settings.loadState(PyGlossState())
        assertEquals(ProviderType.OPENAI_COMPAT, settings.provider)
        assertEquals("http://localhost:11434/v1", settings.baseUrl)
        assertEquals("gpt-oss:20b", settings.model)
    }

    @Test
    fun `settings object exposes provider getter`() {
        val settings = PyGlossSettings()
        settings.loadState(PyGlossState(provider = ProviderType.ANTHROPIC))
        assertEquals(ProviderType.ANTHROPIC, settings.provider)
    }

    @Test
    fun `settings object exposes baseUrl getter`() {
        val settings = PyGlossSettings()
        settings.loadState(PyGlossState(baseUrl = "https://example.com"))
        assertEquals("https://example.com", settings.baseUrl)
    }

    @Test
    fun `settings object exposes model getter`() {
        val settings = PyGlossSettings()
        settings.loadState(PyGlossState(model = "my-model"))
        assertEquals("my-model", settings.model)
    }

    @Test
    fun `project summary settings default to plain empty domain and disabled line translation`() {
        val settings = PyGlossProjectSettings()
        val state = settings.state

        assertEquals("", state!!.domainDescription)
        assertEquals(ExplainStyle.PLAIN, state.explainStyle)
        assertFalse(state.translateLinesWithLlm)
    }

    @Test
    fun `project summary settings round-trip all fields`() {
        val settings = PyGlossProjectSettings()
        settings.loadState(
            PyGlossProjectState(
                domainDescription = "Generates equity research reports as matplotlib charts.",
                explainStyle = ExplainStyle.ANALOGIES,
                translateLinesWithLlm = true
            )
        )

        assertEquals("Generates equity research reports as matplotlib charts.", settings.domainDescription)
        assertEquals(ExplainStyle.ANALOGIES, settings.explainStyle)
        assertEquals(true, settings.translateLinesWithLlm)
    }

    @Test
    fun `project summary update trims domain text`() {
        val settings = PyGlossProjectSettings()
        settings.update("  Builds analyst charts.  ", ExplainStyle.TECHNICAL, true)

        assertEquals("Builds analyst charts.", settings.domainDescription)
        assertEquals(ExplainStyle.TECHNICAL, settings.explainStyle)
        assertEquals(true, settings.translateLinesWithLlm)
    }
}
