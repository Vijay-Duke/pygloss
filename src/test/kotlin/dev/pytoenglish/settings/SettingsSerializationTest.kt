package dev.pytoenglish.settings

import com.intellij.util.xmlb.XmlSerializer
import org.jdom.output.XMLOutputter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Tests for [PyEnglishSettings] state serialization.
 * Verifies non-secret fields round-trip and the API key is never persisted.
 */
class SettingsSerializationTest {

    @Test
    fun `fresh install defaults to OPENAI_COMPAT provider`() {
        val settings = PyEnglishSettings()
        val state = settings.state
        assertNotNull("State should not be null", state)
        assertEquals(ProviderType.OPENAI_COMPAT, state!!.provider)
    }

    @Test
    fun `fresh install defaults to Ollama base URL`() {
        val settings = PyEnglishSettings()
        val state = settings.state
        assertEquals("http://localhost:11434/v1", state!!.baseUrl)
    }

    @Test
    fun `fresh install defaults to gpt-oss model`() {
        val settings = PyEnglishSettings()
        val state = settings.state
        assertEquals("gpt-oss:20b", state!!.model)
    }

    @Test
    fun `fresh install has empty API key value`() {
        assertEquals("", SecretStore.EMPTY_API_KEY)
    }

    @Test
    fun `getState returns non-null state object`() {
        val settings = PyEnglishSettings()
        val state = settings.state
        assertNotNull("getState must return non-null for serialization", state)
    }

    @Test
    fun `round-trip preserves provider`() {
        val settings = PyEnglishSettings()
        settings.loadState(PyEnglishState(provider = ProviderType.ANTHROPIC))
        val roundTripped = settings.state
        assertEquals(ProviderType.ANTHROPIC, roundTripped!!.provider)
    }

    @Test
    fun `round-trip preserves baseUrl`() {
        val settings = PyEnglishSettings()
        settings.loadState(PyEnglishState(baseUrl = "https://custom.api/v1"))
        assertEquals("https://custom.api/v1", settings.state!!.baseUrl)
    }

    @Test
    fun `round-trip preserves model`() {
        val settings = PyEnglishSettings()
        settings.loadState(PyEnglishState(model = "gpt-4o"))
        assertEquals("gpt-4o", settings.state!!.model)
    }

    @Test
    fun `API key is not present in serialized state`() {
        val state = PyEnglishState(
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
        val original = PyEnglishState(
            provider = ProviderType.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            model = "claude-sonnet-4-20250514",
        )
        val xml = XmlSerializer.serialize(original)
        val deserialized = XmlSerializer.deserialize(xml, PyEnglishState::class.java)

        assertEquals(original.provider, deserialized.provider)
        assertEquals(original.baseUrl, deserialized.baseUrl)
        assertEquals(original.model, deserialized.model)
    }

    @Test
    fun `loadState with empty values resolves to defaults`() {
        val settings = PyEnglishSettings()
        settings.loadState(PyEnglishState())
        assertEquals(ProviderType.OPENAI_COMPAT, settings.provider)
        assertEquals("http://localhost:11434/v1", settings.baseUrl)
        assertEquals("gpt-oss:20b", settings.model)
    }

    @Test
    fun `settings object exposes provider getter`() {
        val settings = PyEnglishSettings()
        settings.loadState(PyEnglishState(provider = ProviderType.ANTHROPIC))
        assertEquals(ProviderType.ANTHROPIC, settings.provider)
    }

    @Test
    fun `settings object exposes baseUrl getter`() {
        val settings = PyEnglishSettings()
        settings.loadState(PyEnglishState(baseUrl = "https://example.com"))
        assertEquals("https://example.com", settings.baseUrl)
    }

    @Test
    fun `settings object exposes model getter`() {
        val settings = PyEnglishSettings()
        settings.loadState(PyEnglishState(model = "my-model"))
        assertEquals("my-model", settings.model)
    }
}
