package dev.pytoenglish.render

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.jetbrains.python.psi.PyFile
import dev.pytoenglish.cache.ModelCacheService
import dev.pytoenglish.cache.Profile
import dev.pytoenglish.cache.VerbosityLevel
import dev.pytoenglish.engine.ConceptTable
import dev.pytoenglish.engine.EnglishModelService
import dev.pytoenglish.model.BlockKind
import dev.pytoenglish.model.Concept
import dev.pytoenglish.model.EnglishBlock
import dev.pytoenglish.model.EnglishModel
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import javax.swing.event.TreeSelectionEvent
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicLong

/** Tool window panel that projects the current Python file's English outline. */
class OutlineToolWindow(private val project: Project) : Disposable {

    private val modelService: EnglishModelService get() = EnglishModelService.getInstance(project)
    private val cacheService: ModelCacheService get() = ModelCacheService.getInstance(project)
    private val profileCombo = JComboBox(Profile.entries.toTypedArray())
    private val languageCombo = JComboBox(OutlinePreferences.targetLanguages.toTypedArray())
    private val presetCombo = JComboBox(VerbosityLevel.entries.toTypedArray())
    private val refreshButton = JButton("Refresh")
    private val regenerateButton = JButton("Regenerate")
    private val tree = Tree(DefaultMutableTreeNode("No Python file"))
    private val detailArea = JTextArea()
    private val nodeByBlockId = mutableMapOf<String, DefaultMutableTreeNode>()
    private val listenedEditors = mutableSetOf<Editor>()
    private var currentModel: EnglishModel? = null
    private var currentEditor: Editor? = null
    private var ignoreSelection = false
    private var disposed = false
    private val refreshGeneration = AtomicLong()

    /** Root Swing component shown inside the tool window. */
    val component: JComponent = JPanel(BorderLayout()).apply {
        add(toolbar(), BorderLayout.NORTH)
        add(contentPane(), BorderLayout.CENTER)
    }

    init {
        loadPreferences()
        configureComboRenderers()
        configureTree()
        configureDetailArea()
        bindControls()
        refresh()
    }

    override fun dispose() {
        disposed = true
        currentEditor = null
        listenedEditors.clear()
        nodeByBlockId.clear()
    }

    private fun toolbar(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JBLabel("Profile"))
            add(profileCombo)
            add(JBLabel("Target"))
            add(languageCombo)
            add(JBLabel("Preset"))
            add(presetCombo)
            add(refreshButton)
            regenerateButton.isEnabled = false
            regenerateButton.toolTipText = "Intent Summary regeneration is provided by the summary pipeline."
            add(regenerateButton)
        }
    }

    private fun contentPane(): JComponent {
        val splitPane = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JBScrollPane(tree),
            JBScrollPane(detailArea)
        )
        splitPane.resizeWeight = 0.72
        splitPane.border = BorderFactory.createEmptyBorder()
        return splitPane
    }

    private fun configureComboRenderers() {
        profileCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = when (value) {
                    Profile.POLYGLOT_LENS -> "Polyglot"
                    Profile.INTENT_SUMMARY -> "Intent Summary"
                    else -> value?.toString().orEmpty()
                }
                return component
            }
        }
        presetCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = when (value) {
                    VerbosityLevel.CODE -> "Code"
                    VerbosityLevel.HINTS -> "Hints"
                    VerbosityLevel.OUTLINE -> "Outline"
                    VerbosityLevel.READER -> "Reader"
                    else -> value?.toString().orEmpty()
                }
                return component
            }
        }
    }

    private fun configureTree() {
        tree.isRootVisible = false
        tree.addTreeSelectionListener(TreeSelectionListener { event ->
            if (!ignoreSelection) {
                onTreeSelection(event)
            }
            updateDetail(event.path)
        })
    }

    private fun configureDetailArea() {
        detailArea.isEditable = false
        detailArea.lineWrap = true
        detailArea.wrapStyleWord = true
        detailArea.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        detailArea.text = "Select an outline entry."
    }

    private fun bindControls() {
        profileCombo.addActionListener {
            OutlinePreferences.profile = profileCombo.selectedItem as Profile
            updateControlState()
            refresh()
        }
        languageCombo.addActionListener {
            OutlinePreferences.targetLanguage = languageCombo.selectedItem as String
            refresh()
        }
        presetCombo.addActionListener {
            OutlinePreferences.preset = presetCombo.selectedItem as VerbosityLevel
            refresh()
        }
        refreshButton.addActionListener { refresh() }
        regenerateButton.addActionListener { refresh() }
    }

    private fun loadPreferences() {
        profileCombo.selectedItem = OutlinePreferences.profile
        languageCombo.selectedItem = OutlinePreferences.targetLanguage
        presetCombo.selectedItem = OutlinePreferences.preset
    }

    private fun refresh() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val generation = refreshGeneration.incrementAndGet()
        currentEditor = editor
        if (editor == null) {
            currentModel = null
            renderNodes(emptyList())
            detailArea.text = "Open a Python file to view its outline."
            updateControlState()
            return
        }

        val settings = outlineSettings()
        ReadAction
            .nonBlocking<Pair<PyFile, EnglishModel>?> {
                val pyFile = editor.pythonFile() ?: return@nonBlocking null
                pyFile to modelService.getModel(pyFile, settings.profile, settings.preset)
            }
            .expireWith(this)
            .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
                if (disposed || generation != refreshGeneration.get()) return@finishOnUiThread
                if (result == null) {
                    currentModel = null
                    renderNodes(emptyList())
                    detailArea.text = "Open a Python file to view its outline."
                    updateControlState()
                    return@finishOnUiThread
                }
                val (pyFile, model) = result
                updateOutline(editor, pyFile, model, settings)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun updateOutline(editor: Editor, pyFile: PyFile, model: EnglishModel, settings: OutlineSettings) {
        if (pyFile.project.isDisposed) return
        currentModel = model
        renderNodes(OutlineModelBuilder.build(model, settings, cacheService::isStale))
        syncCaretSelection(editor.caretModel.currentCaret)
        installCaretListener(editor)
        updateControlState()
    }

    private fun outlineSettings(): OutlineSettings {
        return OutlineSettings(
            profile = profileCombo.selectedItem as Profile,
            targetLanguage = languageCombo.selectedItem as String,
            preset = presetCombo.selectedItem as VerbosityLevel
        )
    }

    private fun renderNodes(nodes: List<OutlineNode>) {
        nodeByBlockId.clear()
        val root = DefaultMutableTreeNode("py-to-english")
        nodes.forEach { root.add(treeNode(it)) }
        tree.model = DefaultTreeModel(root)
        for (row in 0 until tree.rowCount) {
            tree.expandRow(row)
        }
    }

    private fun treeNode(node: OutlineNode): DefaultMutableTreeNode {
        val treeNode = DefaultMutableTreeNode(node)
        nodeByBlockId[node.stableId] = treeNode
        node.children.forEach { treeNode.add(treeNode(it)) }
        return treeNode
    }

    private fun onTreeSelection(event: TreeSelectionEvent) {
        val node = event.path?.lastPathComponent as? DefaultMutableTreeNode ?: return
        val outlineNode = node.userObject as? OutlineNode ?: return
        val editor = currentEditor ?: return
        val virtualFile = editor.virtualFile() ?: return
        OpenFileDescriptor(project, virtualFile, outlineNode.range.startOffset).navigate(true)
        editor.selectionModel.setSelection(outlineNode.range.startOffset, outlineNode.range.endOffset)
        editor.caretModel.moveToOffset(outlineNode.range.startOffset)
    }

    private fun updateDetail(path: TreePath?) {
        val node = path?.lastPathComponent as? DefaultMutableTreeNode
        val outlineNode = node?.userObject as? OutlineNode
        detailArea.text = outlineNode?.detailText ?: "Select an outline entry."
        detailArea.caretPosition = 0
    }

    private fun syncCaretSelection(caret: Caret) {
        val model = currentModel ?: return
        val blockId = OutlineModelBuilder.containingBlockId(model, caret.offset, outlineSettings().preset) ?: return
        val node = nodeByBlockId[blockId] ?: return
        val path = TreePath(node.path)
        ignoreSelection = true
        try {
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        } finally {
            ignoreSelection = false
        }
        updateDetail(path)
    }

    private fun installCaretListener(editor: Editor) {
        if (!listenedEditors.add(editor)) return
        editor.caretModel.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    if (disposed) return
                    if (editor !== currentEditor) return
                    val caret = event.caret
                    SwingUtilities.invokeLater {
                        if (!disposed) syncCaretSelection(caret)
                    }
                }
            },
            this
        )
    }

    private fun updateControlState() {
        regenerateButton.isEnabled = profileCombo.selectedItem == Profile.INTENT_SUMMARY
    }

    private fun Editor.pythonFile(): PyFile? {
        val psiFile = PsiDocumentManager.getInstance(this@OutlineToolWindow.project).getPsiFile(document)
        return psiFile as? PyFile
    }

    private fun Editor.virtualFile() = FileEditorManager.getInstance(this@OutlineToolWindow.project).selectedFiles.firstOrNull()
}

/** User-visible settings that control outline projection. */
data class OutlineSettings(
    /** Active reader profile. */
    val profile: Profile,
    /** Target language for Polyglot Lens callouts. */
    val targetLanguage: String,
    /** Verbosity preset for outline shape and detail. */
    val preset: VerbosityLevel
)

/** A tree node rendered by the outline tool window. */
data class OutlineNode(
    /** Stable block identifier from the English model. */
    val stableId: String,
    /** Block kind. */
    val kind: BlockKind,
    /** Source text range for navigation and caret sync. */
    val range: TextRange,
    /** Compact label for the tree row. */
    val label: String,
    /** Projection text displayed in the detail pane. */
    val detailText: String,
    /** Whether the associated LLM summary is stale. */
    val stale: Boolean,
    /** Child outline nodes. */
    val children: List<OutlineNode>
) {
    override fun toString(): String = label
}

/** Pure outline model builder used by the UI and headless tests. */
object OutlineModelBuilder {

    /** Build visible outline nodes from [model] according to [settings]. */
    fun build(
        model: EnglishModel,
        settings: OutlineSettings,
        isStale: (String) -> Boolean = { false }
    ): List<OutlineNode> {
        return model.blocks.mapNotNull { toNode(it, settings, depth = 0, isStale) }
    }

    /** Return a depth-first list of outline nodes. */
    fun flatten(nodes: List<OutlineNode>): List<OutlineNode> {
        val result = mutableListOf<OutlineNode>()
        fun collect(items: List<OutlineNode>) {
            items.forEach { node ->
                result.add(node)
                collect(node.children)
            }
        }
        collect(nodes)
        return result
    }

    /** Resolve [offset] to the deepest visible containing block ID. */
    fun containingBlockId(model: EnglishModel, offset: Int, preset: VerbosityLevel): String? {
        return deepestVisibleBlock(model.blocks, offset, preset, depth = 0)?.stableId
    }

    private fun toNode(
        block: EnglishBlock,
        settings: OutlineSettings,
        depth: Int,
        isStale: (String) -> Boolean
    ): OutlineNode? {
        if (!isVisible(block, settings.preset, depth)) return null
        val children = block.children.mapNotNull { toNode(it, settings, depth + 1, isStale) }
        val stale = isStale(block.stableId)
        return OutlineNode(
            stableId = block.stableId,
            kind = block.kind,
            range = block.textRange,
            label = labelFor(block),
            detailText = detailFor(block, settings, stale),
            stale = stale,
            children = children
        )
    }

    private fun deepestVisibleBlock(
        blocks: List<EnglishBlock>,
        offset: Int,
        preset: VerbosityLevel,
        depth: Int
    ): EnglishBlock? {
        return blocks
            .filter { it.textRange.containsOffset(offset) && isVisible(it, preset, depth) }
            .map { block ->
                deepestVisibleBlock(block.children, offset, preset, depth + 1) ?: block
            }
            .maxByOrNull { it.textRange.startOffset }
    }

    private fun isVisible(block: EnglishBlock, preset: VerbosityLevel, depth: Int): Boolean {
        return when (preset) {
            VerbosityLevel.CODE -> depth == 0 && block.kind in setOf(BlockKind.CLASS, BlockKind.FUNCTION)
            VerbosityLevel.HINTS -> block.kind != BlockKind.COMPREHENSION
            VerbosityLevel.OUTLINE -> block.kind != BlockKind.COMPREHENSION || block.concepts.isNotEmpty()
            VerbosityLevel.READER -> true
        }
    }

    private fun labelFor(block: EnglishBlock): String {
        val name = block.skeleton.substringAfter("name=", "").substringBefore("|").takeIf { it.isNotBlank() }
        return if (name == null) {
            block.kind.name.lowercase()
        } else {
            "${block.kind.name.lowercase()} $name"
        }
    }

    private fun detailFor(block: EnglishBlock, settings: OutlineSettings, stale: Boolean): String {
        if (settings.preset == VerbosityLevel.CODE) return ""
        val header = "${block.kind.name.lowercase()}: ${block.skeleton}"
        val body = when (settings.profile) {
            Profile.POLYGLOT_LENS -> analogyText(block, settings.targetLanguage)
            Profile.INTENT_SUMMARY -> summaryText(block, stale)
        }
        return listOf(header, body).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun analogyText(block: EnglishBlock, language: String): String {
        if (block.concepts.isEmpty()) return "No analogy callouts for this block."
        return block.concepts
            .sortedBy(Concept::name)
            .mapNotNull { concept ->
                ConceptTable.lookup(concept, language)?.let { entry ->
                    "${concept.name.lowercase()}: Closest analogy: ${entry.closestAnalogy} " +
                        "(${entry.confidenceTier.name.lowercase()} confidence). Caveat: ${entry.caveat}"
                }
            }
            .joinToString("\n")
            .ifBlank { "No analogy callouts for this block." }
    }

    private fun summaryText(block: EnglishBlock, stale: Boolean): String {
        val badge = if (stale) "[stale] " else ""
        val summary = block.summary ?: "Summary not generated yet."
        return "$badge$summary"
    }
}

/** Persisted outline preferences used by toolbar controls and preset actions. */
object OutlinePreferences {

    /** Supported Polyglot Lens target languages. */
    val targetLanguages: List<String> = listOf("JS", "Java", "Go", "C#")

    private const val PROFILE_KEY = "dev.pytoenglish.outline.profile"
    private const val TARGET_LANGUAGE_KEY = "dev.pytoenglish.outline.targetLanguage"
    private const val PRESET_KEY = "dev.pytoenglish.outline.preset"

    /** Persisted reader profile. */
    var profile: Profile
        get() = enumValueOrDefault(PROFILE_KEY, Profile.POLYGLOT_LENS)
        set(value) = properties().setValue(PROFILE_KEY, value.name)

    /** Persisted target language. */
    var targetLanguage: String
        get() = properties().getValue(TARGET_LANGUAGE_KEY, "JS").takeIf { it in targetLanguages } ?: "JS"
        set(value) = properties().setValue(TARGET_LANGUAGE_KEY, value.takeIf { it in targetLanguages } ?: "JS")

    /** Persisted verbosity preset. */
    var preset: VerbosityLevel
        get() = enumValueOrDefault(PRESET_KEY, VerbosityLevel.HINTS)
        set(value) = properties().setValue(PRESET_KEY, value.name)

    private inline fun <reified T : Enum<T>> enumValueOrDefault(key: String, default: T): T {
        val raw = properties().getValue(key, default.name)
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    }

    private fun properties(): PropertiesComponent {
        return ApplicationManager.getApplication().getService(PropertiesComponent::class.java)
    }
}
