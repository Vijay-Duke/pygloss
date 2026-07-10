package dev.pygloss.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Supported LLM provider types for the Intent Summary path.
 */
enum class ProviderType {
    /** Covers Ollama, OpenAI, Groq, Together, LM Studio, vLLM, etc. */
    OPENAI_COMPAT,
    /** Anthropic Claude API. */
    ANTHROPIC,
}

/**
 * Persistable non-secret state for PyGloss settings.
 */
data class PyGlossState(
    var provider: ProviderType = ProviderType.OPENAI_COMPAT,
    var baseUrl: String = "http://localhost:11434/v1",
    var model: String = "gpt-oss:20b",
)

/**
 * Application-level settings service holding provider configuration.
 */
@Service(Service.Level.APP)
@State(
    name = "dev.pygloss.settings.PyGlossSettings",
    storages = [Storage("pyGlossSettings.xml")]
)
class PyGlossSettings : PersistentStateComponent<PyGlossState> {

    private var myState = PyGlossState()

    override fun getState(): PyGlossState = myState

    override fun loadState(state: PyGlossState) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    /** Current provider type. */
    val provider: ProviderType get() = myState.provider

    /** Current base URL for the LLM endpoint. */
    val baseUrl: String get() = myState.baseUrl

    /** Current model name. */
    val model: String get() = myState.model

    /** Replace all non-secret provider fields at once. */
    fun update(provider: ProviderType, baseUrl: String, model: String) {
        myState.provider = provider
        myState.baseUrl = baseUrl
        myState.model = model
    }

    companion object {
        /** Convenience accessor for the application-level instance. */
        @JvmStatic
        fun getInstance(): PyGlossSettings =
            ApplicationManager.getApplication().getService(PyGlossSettings::class.java)
    }
}
