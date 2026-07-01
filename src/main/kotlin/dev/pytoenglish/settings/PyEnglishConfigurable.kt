package dev.pytoenglish.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.pytoenglish.llm.AnthropicAdapter
import dev.pytoenglish.llm.JdkHttpClient
import dev.pytoenglish.llm.LlmAdapter
import dev.pytoenglish.llm.LlmRequest
import dev.pytoenglish.llm.LlmResult
import dev.pytoenglish.llm.OpenAiCompatAdapter
import dev.pytoenglish.llm.SettingsSnapshot
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings UI for py-to-english provider configuration (R8).
 */
class PyEnglishConfigurable : Configurable {

    private val providerCombo = ComboBox(ProviderType.entries.toTypedArray())
    private val baseUrlField = JBTextField()
    private val modelField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val testButton = JButton("Test connection")

    private var mainPanel: JPanel? = null
    private var loadedApiKey: String = SecretStore.EMPTY_API_KEY

    override fun getDisplayName(): String = "py-to-english"

    override fun createComponent(): JComponent {
        if (mainPanel == null) {
            testButton.addActionListener { testConnection() }
            val formPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent(
                    JBLabel("Provider:"),
                    providerCombo,
                    true,
                )
                .addLabeledComponent(
                    JBLabel("Base URL:"),
                    baseUrlField,
                    true,
                )
                .addLabeledComponent(
                    JBLabel("Model:"),
                    modelField,
                    true,
                )
                .addLabeledComponent(
                    JBLabel("API key:"),
                    apiKeyField,
                    true,
                )
                .addComponent(testButton)
                .panel

            mainPanel = JPanel(BorderLayout()).apply {
                add(formPanel, BorderLayout.NORTH)
            }
        }
        reset()
        return mainPanel!!
    }

    override fun isModified(): Boolean {
        val settings = PyEnglishSettings.getInstance()
        return providerCombo.selectedItem != settings.provider ||
            baseUrlField.text != settings.baseUrl ||
            modelField.text != settings.model ||
            String(apiKeyField.password) != loadedApiKey
    }

    override fun apply() {
        val settings = PyEnglishSettings.getInstance()
        val secretStore = SecretStore.getInstance()
        val apiKey = String(apiKeyField.password).trim()

        settings.update(
            provider = selectedProvider(),
            baseUrl = baseUrlField.text.trim(),
            model = modelField.text.trim(),
        )
        loadedApiKey = apiKey
        secretStore.setApiKeyAsync(apiKey)
    }

    override fun reset() {
        val settings = PyEnglishSettings.getInstance()

        providerCombo.selectedItem = settings.provider
        baseUrlField.text = settings.baseUrl
        modelField.text = settings.model
        SecretStore.getInstance().getApiKeyAsync { key ->
            ApplicationManager.getApplication().invokeLater {
                loadedApiKey = key
                apiKeyField.text = key
            }
        }
    }

    override fun disposeUIResources() {
        mainPanel = null
    }

    private fun selectedProvider(): ProviderType =
        providerCombo.selectedItem as? ProviderType ?: ProviderType.OPENAI_COMPAT

    private fun testConnection() {
        val snapshot = SettingsSnapshot(
            baseUrl = baseUrlField.text.trim(),
            model = modelField.text.trim(),
            apiKey = String(apiKeyField.password).trim(),
        )
        val adapter = buildAdapter(selectedProvider(), snapshot)
        testButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = adapter.summarize(LlmRequest(prompt = "Reply with OK."))
            ApplicationManager.getApplication().invokeLater {
                testButton.isEnabled = true
                when (result) {
                    is LlmResult.Success -> Messages.showInfoMessage("Connection succeeded.", "py-to-english")
                    LlmResult.AuthError -> Messages.showErrorDialog("Authentication failed.", "py-to-english")
                    LlmResult.NetworkError -> Messages.showErrorDialog("Network error while testing connection.", "py-to-english")
                    LlmResult.ParseError -> Messages.showErrorDialog("Provider response could not be parsed.", "py-to-english")
                    LlmResult.RateLimited -> Messages.showErrorDialog("Provider rate limit reached.", "py-to-english")
                    LlmResult.Timeout -> Messages.showErrorDialog("Connection test timed out.", "py-to-english")
                }
            }
        }
    }

    private fun buildAdapter(provider: ProviderType, snapshot: SettingsSnapshot): LlmAdapter {
        val httpClient = JdkHttpClient()
        return when (provider) {
            ProviderType.OPENAI_COMPAT -> OpenAiCompatAdapter(snapshot, httpClient)
            ProviderType.ANTHROPIC -> AnthropicAdapter(snapshot, httpClient)
        }
    }
}
