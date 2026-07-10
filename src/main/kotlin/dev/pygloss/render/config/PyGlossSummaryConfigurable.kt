package dev.pygloss.render.config

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.pygloss.render.PyGlossRefresher
import dev.pygloss.settings.ExplainStyle
import dev.pygloss.settings.PyGlossProjectSettings
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/** Project-level settings page for Intent Summary behavior. */
class PyGlossSummaryConfigurable(private val project: Project) : Configurable {

    private val domainField = JBTextField()
    private val styleCombo = ComboBox(ExplainStyle.entries.toTypedArray()).apply {
        renderer = labelRenderer(::styleLabel)
    }
    private val translateLinesCheckBox = JBCheckBox("Translate code lines with AI (Reader view)")
    private var mainPanel: JPanel? = null

    override fun getDisplayName(): String = "Summaries"

    override fun createComponent(): JComponent {
        if (mainPanel == null) {
            configureTooltips()
            val formPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent(JBLabel("What is this project about?"), domainField, true)
                .addComponent(help("Included in every AI summary request so explanations use your domain's language instead of code terms."))
                .addLabeledComponent(JBLabel("Explanation style:"), styleCombo, true)
                .addComponent(translateLinesCheckBox)
                .addComponent(help("Experimental. Sends code lines to your configured LLM and uses fluent English in Reader folds. Uses more tokens; results are cached."))
                .panel

            mainPanel = JPanel(BorderLayout()).apply {
                add(formPanel, BorderLayout.NORTH)
            }
        }
        reset()
        return mainPanel!!
    }

    override fun isModified(): Boolean {
        val settings = PyGlossProjectSettings.getInstance(project)
        return domainField.text.trim() != settings.domainDescription ||
            selectedStyle() != settings.explainStyle ||
            translateLinesCheckBox.isSelected != settings.translateLinesWithLlm
    }

    override fun apply() {
        val changed = isModified()
        PyGlossProjectSettings.getInstance(project).update(
            domainDescription = domainField.text,
            explainStyle = selectedStyle(),
            translateLinesWithLlm = translateLinesCheckBox.isSelected
        )
        if (changed) {
            PyGlossRefresher.refreshAllProjects()
        }
    }

    override fun reset() {
        val settings = PyGlossProjectSettings.getInstance(project)
        domainField.text = settings.domainDescription
        styleCombo.selectedItem = settings.explainStyle
        translateLinesCheckBox.isSelected = settings.translateLinesWithLlm
    }

    override fun disposeUIResources() {
        mainPanel = null
    }

    private fun configureTooltips() {
        styleCombo.toolTipText = tooltipFor(selectedStyle())
        styleCombo.addActionListener { styleCombo.toolTipText = tooltipFor(selectedStyle()) }
        translateLinesCheckBox.toolTipText =
            "When Reader view is active, cached AI translations replace deterministic line placeholders as they arrive."
    }

    private fun selectedStyle(): ExplainStyle =
        styleCombo.selectedItem as? ExplainStyle ?: ExplainStyle.PLAIN

    private fun help(text: String): JBLabel {
        return JBLabel("<html>$text</html>").apply { foreground = JBColor.GRAY }
    }

    private fun styleLabel(style: ExplainStyle): String = when (style) {
        ExplainStyle.PLAIN -> "Plain language"
        ExplainStyle.ANALOGIES -> "Plain language with analogies"
        ExplainStyle.TECHNICAL -> "Technical"
    }

    private fun tooltipFor(style: ExplainStyle): String = when (style) {
        ExplainStyle.PLAIN -> "No programming jargon; things are named by their role"
        ExplainStyle.ANALOGIES -> "Adds short everyday analogies for unfamiliar concepts"
        ExplainStyle.TECHNICAL -> "Developer-style summaries (the old behavior)"
    }

    private fun <T> labelRenderer(label: (T) -> String): ListCellRenderer<T> {
        return ListCellRenderer { list: JList<out T>, value: T?, index: Int, selected: Boolean, focused: Boolean ->
            JBLabel(value?.let(label) ?: "").apply {
                toolTipText = (value as? ExplainStyle)?.let(::tooltipFor)
                if (selected) {
                    background = list.selectionBackground
                    foreground = list.selectionForeground
                    isOpaque = true
                }
            } as Component
        }
    }
}
