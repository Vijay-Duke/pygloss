package dev.pytoenglish.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.pytoenglish.cache.Profile
import dev.pytoenglish.cache.VerbosityLevel
import dev.pytoenglish.llm.AnthropicAdapter
import dev.pytoenglish.llm.JdkHttpClient
import dev.pytoenglish.llm.LlmAdapter
import dev.pytoenglish.llm.LlmRequest
import dev.pytoenglish.llm.LlmResult
import dev.pytoenglish.llm.OpenAiCompatAdapter
import dev.pytoenglish.llm.SettingsSnapshot
import dev.pytoenglish.render.OutlinePreferences
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.time.Duration
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Settings UI for py-to-english: provider configuration (R8) plus the reader
 * profile / target language / verbosity preset defaults (R7) mirrored from the
 * outline tool window (both share [OutlinePreferences]).
 */
class PyEnglishConfigurable : Configurable {

    private val providerCombo = ComboBox(ProviderType.entries.toTypedArray())
    private val baseUrlField = JBTextField()
    private val modelField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val testButton = JButton("Test connection")
    private val resultLabel = JBLabel().apply { isVisible = false }

    private val profileCombo = ComboBox(Profile.entries.toTypedArray()).apply { renderer = labelRenderer(::profileLabel) }
    private val languageCombo = ComboBox(OutlinePreferences.targetLanguages.toTypedArray())
    private val presetCombo = ComboBox(VerbosityLevel.entries.toTypedArray()).apply { renderer = labelRenderer(::presetLabel) }

    private var mainPanel: JPanel? = null
    private var loadedApiKey: String = SecretStore.EMPTY_API_KEY

    override fun getDisplayName(): String = "py-to-english"

    override fun createComponent(): JComponent {
        if (mainPanel == null) {
            testButton.addActionListener { testConnection() }
            val testRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(testButton)
                add(JBLabel("  "))
                add(resultLabel)
            }
            val formPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent(JBLabel("Provider:"), providerCombo, true)
                .addLabeledComponent(JBLabel("Base URL:"), baseUrlField, true)
                .addLabeledComponent(JBLabel("Model:"), modelField, true)
                .addLabeledComponent(JBLabel("API key:"), apiKeyField, true)
                .addComponent(testRow)
                .addSeparator()
                .addLabeledComponent(JBLabel("Reader profile:"), profileCombo, true)
                .addLabeledComponent(JBLabel("Target language:"), languageCombo, true)
                .addLabeledComponent(JBLabel("Verbosity preset:"), presetCombo, true)
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
            String(apiKeyField.password) != loadedApiKey ||
            profileCombo.selectedItem != OutlinePreferences.profile ||
            languageCombo.selectedItem != OutlinePreferences.targetLanguage ||
            presetCombo.selectedItem != OutlinePreferences.preset
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

        OutlinePreferences.profile = profileCombo.selectedItem as Profile
        OutlinePreferences.targetLanguage = languageCombo.selectedItem as String
        OutlinePreferences.preset = presetCombo.selectedItem as VerbosityLevel
    }

    override fun reset() {
        val settings = PyEnglishSettings.getInstance()

        providerCombo.selectedItem = settings.provider
        baseUrlField.text = settings.baseUrl
        modelField.text = settings.model
        profileCombo.selectedItem = OutlinePreferences.profile
        languageCombo.selectedItem = OutlinePreferences.targetLanguage
        presetCombo.selectedItem = OutlinePreferences.preset
        resultLabel.isVisible = false
        SecretStore.getInstance().getApiKeyAsync { key ->
            invokeInSettingsDialog {
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
        showResult(null, "Testing…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = adapter.summarize(LlmRequest(prompt = "Reply with OK.", maxTokens = 8))
            invokeInSettingsDialog {
                testButton.isEnabled = true
                when (result) {
                    is LlmResult.Success -> showResult(true, "Connection succeeded")
                    LlmResult.AuthError -> showResult(false, "Authentication failed (check API key)")
                    LlmResult.NetworkError -> showResult(false, "Network error (check Base URL)")
                    LlmResult.ParseError -> showResult(false, "Unexpected provider response")
                    LlmResult.RateLimited -> showResult(false, "Rate limit reached")
                    LlmResult.Timeout -> showResult(false, "Connection timed out")
                }
            }
        }
    }

    private fun invokeInSettingsDialog(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(
            Runnable { action() },
            ModalityState.any()
        )
    }

    /** Render the inline result marker: green check on success, red cross on failure, plain text while testing. */
    private fun showResult(ok: Boolean?, message: String) {
        resultLabel.icon = when (ok) {
            true -> AllIcons.General.InspectionsOK
            false -> AllIcons.General.Error
            null -> null
        }
        resultLabel.foreground = when (ok) {
            true -> JBColor(0x2E7D32, 0x4CAF50)
            false -> JBColor(0xC62828, 0xEF5350)
            null -> JBColor.foreground()
        }
        resultLabel.text = message
        resultLabel.isVisible = true
    }

    private fun buildAdapter(provider: ProviderType, snapshot: SettingsSnapshot): LlmAdapter {
        val httpClient = JdkHttpClient(TEST_CONNECTION_TIMEOUT)
        return when (provider) {
            ProviderType.OPENAI_COMPAT -> OpenAiCompatAdapter(snapshot, httpClient, TEST_CONNECTION_TIMEOUT, maxAttempts = 1)
            ProviderType.ANTHROPIC -> AnthropicAdapter(snapshot, httpClient, TEST_CONNECTION_TIMEOUT, maxAttempts = 1)
        }
    }

    private fun profileLabel(p: Profile): String = when (p) {
        Profile.POLYGLOT_LENS -> "Polyglot Lens (no LLM)"
        Profile.INTENT_SUMMARY -> "Intent Summary (LLM)"
    }

    private fun presetLabel(v: VerbosityLevel): String = when (v) {
        VerbosityLevel.CODE -> "Code"
        VerbosityLevel.HINTS -> "Hints"
        VerbosityLevel.OUTLINE -> "Outline"
        VerbosityLevel.READER -> "Reader"
    }

    private fun <T> labelRenderer(label: (T) -> String): ListCellRenderer<T> =
        ListCellRenderer { list: JList<out T>, value: T?, index: Int, selected: Boolean, focused: Boolean ->
            JBLabel(value?.let(label) ?: "").apply {
                if (selected) {
                    background = list.selectionBackground
                    foreground = list.selectionForeground
                    isOpaque = true
                }
            } as Component
        }

    private companion object {
        val TEST_CONNECTION_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
