package dev.pygloss.render

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pygloss.cache.Profile
import dev.pygloss.cache.VerbosityLevel

class EditorPreferencesTest : BasePlatformTestCase() {

    fun testExistingOutlinePreferenceKeysRemainCompatible() {
        val properties = ApplicationManager.getApplication().getService(PropertiesComponent::class.java)
        val previousValues = LEGACY_KEYS.associateWith(properties::getValue)

        try {
            properties.setValue(PROFILE_KEY, Profile.INTENT_SUMMARY.name)
            properties.setValue(TARGET_LANGUAGE_KEY, "Go")
            properties.setValue(PRESET_KEY, VerbosityLevel.READER.name)

            assertEquals(Profile.INTENT_SUMMARY, EditorPreferences.profile)
            assertEquals("Go", EditorPreferences.targetLanguage)
            assertEquals(VerbosityLevel.READER, EditorPreferences.preset)

            val overlaySettings = readOverlaySettings()
            assertEquals(Profile.INTENT_SUMMARY, overlaySettings.profile)
            assertEquals("Go", overlaySettings.targetLanguage)
            assertEquals(VerbosityLevel.READER, overlaySettings.preset)
        } finally {
            previousValues.forEach { (key, value) ->
                if (value == null) properties.unsetValue(key) else properties.setValue(key, value)
            }
        }
    }

    private companion object {
        const val PROFILE_KEY = "dev.pygloss.outline.profile"
        const val TARGET_LANGUAGE_KEY = "dev.pygloss.outline.targetLanguage"
        const val PRESET_KEY = "dev.pygloss.outline.preset"
        val LEGACY_KEYS = listOf(PROFILE_KEY, TARGET_LANGUAGE_KEY, PRESET_KEY)
    }
}
