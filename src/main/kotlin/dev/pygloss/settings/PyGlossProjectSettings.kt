package dev.pygloss.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/** User-selectable voice for LLM-backed Intent Summary explanations. */
enum class ExplainStyle {
    PLAIN,
    ANALOGIES,
    TECHNICAL,
}

/** Persistable project-level summary settings. */
data class PyGlossProjectState(
    var domainDescription: String = "",
    var explainStyle: ExplainStyle = ExplainStyle.PLAIN,
    var translateLinesWithLlm: Boolean = false,
)

/** Project-level settings that tune summary prompts and future line translation. */
@Service(Service.Level.PROJECT)
@State(
    name = "dev.pygloss.settings.PyGlossProjectSettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class PyGlossProjectSettings : PersistentStateComponent<PyGlossProjectState> {

    private var myState = PyGlossProjectState()

    override fun getState(): PyGlossProjectState = myState

    override fun loadState(state: PyGlossProjectState) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    val domainDescription: String get() = myState.domainDescription

    val explainStyle: ExplainStyle get() = myState.explainStyle

    val translateLinesWithLlm: Boolean get() = myState.translateLinesWithLlm

    fun update(domainDescription: String, explainStyle: ExplainStyle, translateLinesWithLlm: Boolean) {
        myState.domainDescription = domainDescription.trim()
        myState.explainStyle = explainStyle
        myState.translateLinesWithLlm = translateLinesWithLlm
    }

    companion object {
        /** Return the project-level summary settings. */
        fun getInstance(project: Project): PyGlossProjectSettings = project.service()
    }
}
