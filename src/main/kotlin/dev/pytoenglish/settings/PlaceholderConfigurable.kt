package dev.pytoenglish.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class PlaceholderConfigurable : Configurable {

    override fun getDisplayName(): String = "py-to-english"

    override fun createComponent(): JComponent {
        return JPanel().apply {
            add(JLabel("py-to-english settings (placeholder - no configuration yet)"))
        }
    }

    override fun isModified(): Boolean = false

    override fun apply() { /* no-op */ }
}
