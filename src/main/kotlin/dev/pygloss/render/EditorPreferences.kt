package dev.pygloss.render

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import dev.pygloss.cache.Profile
import dev.pygloss.cache.VerbosityLevel
import dev.pygloss.engine.ConceptTable

/** Persisted preferences shared by PyGloss's in-editor rendering surfaces. */
object EditorPreferences {

    /** Supported Polyglot Lens target languages. */
    val targetLanguages: List<String> = ConceptTable.supportedLanguages

    // Keep the original keys so existing installations retain their selected view.
    private const val PROFILE_KEY = "dev.pygloss.outline.profile"
    private const val TARGET_LANGUAGE_KEY = "dev.pygloss.outline.targetLanguage"
    private const val PRESET_KEY = "dev.pygloss.outline.preset"
    /** Persisted reader profile. */
    var profile: Profile
        get() = enumValueOrDefault(PROFILE_KEY, Profile.POLYGLOT_LENS)
        set(value) = update(profile = value)

    /** Persisted target language. */
    var targetLanguage: String
        get() = properties().getValue(TARGET_LANGUAGE_KEY, "JS").takeIf { it in targetLanguages } ?: "JS"
        set(value) = update(targetLanguage = value)

    /** Persisted verbosity preset. */
    var preset: VerbosityLevel
        get() = enumValueOrDefault(PRESET_KEY, VerbosityLevel.HINTS)
        set(value) = update(preset = value)

    /** Persist editor preferences as one change and refresh open rendering surfaces. */
    fun update(
        profile: Profile = this.profile,
        targetLanguage: String = this.targetLanguage,
        preset: VerbosityLevel = this.preset,
    ) {
        val normalizedTargetLanguage = targetLanguage.takeIf { it in targetLanguages } ?: "JS"
        val currentProfile = this.profile
        val currentTargetLanguage = this.targetLanguage
        val currentPreset = this.preset
        val presetChanged = preset != currentPreset
        val changed = profile != currentProfile ||
            normalizedTargetLanguage != currentTargetLanguage ||
            presetChanged
        if (!changed) return

        val properties = properties()
        properties.setValue(PROFILE_KEY, profile.name)
        properties.setValue(TARGET_LANGUAGE_KEY, normalizedTargetLanguage)
        properties.setValue(PRESET_KEY, preset.name)
        PyGlossRefresher.refreshAllProjects(refreshFoldRegions = presetChanged)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(key: String, default: T): T {
        val raw = properties().getValue(key, default.name)
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    }

    private fun properties(): PropertiesComponent {
        return ApplicationManager.getApplication().getService(PropertiesComponent::class.java)
    }
}
