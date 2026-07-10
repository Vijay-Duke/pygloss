package dev.pygloss.render

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.FoldingGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.psi.PyAssertStatement
import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyAugAssignmentStatement
import com.jetbrains.python.psi.PyBinaryExpression
import com.jetbrains.python.psi.PyBreakStatement
import com.jetbrains.python.psi.PyComprehensionElement
import com.jetbrains.python.psi.PyContinueStatement
import com.jetbrains.python.psi.PyExpressionStatement
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyForStatement
import com.jetbrains.python.psi.PyFromImportStatement
import com.jetbrains.python.psi.PyIfStatement
import com.jetbrains.python.psi.PyImportStatement
import com.jetbrains.python.psi.PyPassStatement
import com.jetbrains.python.psi.PyRaiseStatement
import com.jetbrains.python.psi.PyReturnStatement
import com.jetbrains.python.psi.PyStatement
import com.jetbrains.python.psi.PyStatementList
import com.jetbrains.python.psi.PyStatementPart
import com.jetbrains.python.psi.PyTryExceptStatement
import com.jetbrains.python.psi.PyWhileStatement
import com.jetbrains.python.psi.PyWithStatement
import dev.pygloss.cache.VerbosityLevel
import dev.pygloss.engine.BlockDetector
import dev.pygloss.engine.withPsiReadAccess
import dev.pygloss.application.LineTranslationRequester
import dev.pygloss.application.LineTranslationService
import dev.pygloss.model.BlockKind
import dev.pygloss.translation.EnglishPhrases
import java.util.TreeMap
import dev.pygloss.model.EnglishBlock
import dev.pygloss.settings.PyGlossProjectSettings

/** Synchronous folding builder for tiny deterministic Python idioms. */
class PyGlossFoldingBuilder(
    private val presetProvider: () -> FoldingPreset = ::readActivePreset,
    private val lineTranslationProvider: (com.intellij.openapi.project.Project) -> LineTranslationRequester =
        { LineTranslationService.getInstance(it) },
) : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val result = withPsiReadAccess { buildFoldRegionsInReadAction(root) }
        scheduleMissingLineTranslations(result)
        return result.descriptors
    }

    private fun buildFoldRegionsInReadAction(root: PsiElement): FoldingBuildResult {
        val file = root as? PyFile ?: return FoldingBuildResult(emptyArray(), null, emptyList())
        val preset = presetProvider()
        if (!preset.showsEnglishFolds) return FoldingBuildResult(emptyArray(), file, emptyList())

        val model = BlockDetector.detect(file)
        val comprehensionsByRange = PsiTreeUtil.collectElementsOfType(file, PyComprehensionElement::class.java)
            .associateBy { it.textRange }
        val ifStatementsByRange = PsiTreeUtil.collectElementsOfType(file, PyIfStatement::class.java)
            .associateBy { it.textRange }
        val missingLineTranslations = mutableListOf<String>()
        val candidates = buildList {
            addAll(comprehensionCandidates(file, model.blocks, comprehensionsByRange))
            addAll(booleanExpressionCandidates(file))
            if (!preset.showsStatementFolds) {
                addAll(obviousReturnCandidates(file, model.blocks, preset, ifStatementsByRange))
            }
            if (preset.showsStatementFolds) {
                addAll(statementCandidates(file, preset, missingLineTranslations))
            }
        }

        val descriptors = nonOverlapping(candidates)
            .map { candidate ->
                FoldingDescriptor(
                    candidate.element.node,
                    candidate.range,
                    FoldingGroup.newGroup(candidate.kind.groupPrefix + candidate.range.startOffset),
                    candidate.placeholder,
                    candidate.collapsedByDefault,
                    emptySet()
                )
            }
            .toTypedArray()
        return FoldingBuildResult(descriptors, file, missingLineTranslations.distinct())
    }

    override fun getPlaceholderText(node: ASTNode): String {
        return withPsiReadAccess { placeholderFor(node.psi) ?: "python idiom" }
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean {
        val preset = presetProvider()
        return if (preset.showsStatementFolds && node.psi.isReaderStatementFoldAnchor()) {
            preset.collapsesStatementFolds
        } else {
            preset.collapsesIdiomFolds
        }
    }

    private fun comprehensionCandidates(
        file: PyFile,
        blocks: List<EnglishBlock>,
        byRange: Map<TextRange, PyComprehensionElement>,
    ): List<FoldCandidate> {
        return flatten(blocks)
            .filter { it.kind == BlockKind.COMPREHENSION }
            .mapNotNull { block ->
                val element = comprehensionForRange(file, block.textRange, byRange) ?: return@mapNotNull null
                FoldCandidate(
                    element = element,
                    range = element.textRange,
                    placeholder = comprehensionPlaceholder(element),
                    kind = FoldKind.IDIOM,
                    collapsedByDefault = presetProvider().collapsesIdiomFolds
                )
            }
    }

    private fun booleanExpressionCandidates(file: PyFile): List<FoldCandidate> {
        return PsiTreeUtil.collectElementsOfType(file, PyBinaryExpression::class.java)
            .filter { it.isBooleanExpression() }
            .map { element ->
                FoldCandidate(
                    element = element,
                    range = element.textRange,
                    placeholder = booleanPlaceholder(element),
                    kind = FoldKind.IDIOM,
                    collapsedByDefault = presetProvider().collapsesIdiomFolds
                )
            }
    }

    private fun obviousReturnCandidates(
        file: PyFile,
        blocks: List<EnglishBlock>,
        preset: FoldingPreset,
        byRange: Map<TextRange, PyIfStatement>,
    ): List<FoldCandidate> {
        val ifBlocks = flatten(blocks).filter { it.kind == BlockKind.IF }
        return ifBlocks.mapNotNull { block ->
            val element = ifForRange(file, block.textRange, byRange) ?: return@mapNotNull null
            if (!element.isSingleReturnGuard()) return@mapNotNull null
            FoldCandidate(
                element = element,
                range = element.textRange,
                placeholder = singleReturnGuardPlaceholder(element),
                kind = FoldKind.IDIOM,
                collapsedByDefault = preset.collapsesIdiomFolds
            )
        }
    }

    private fun statementCandidates(
        file: PyFile,
        preset: FoldingPreset,
        missingLineTranslations: MutableList<String>
    ): List<FoldCandidate> {
        return simpleStatementCandidates(file, preset, missingLineTranslations) + compoundHeaderCandidates(file, preset)
    }

    private fun simpleStatementCandidates(
        file: PyFile,
        preset: FoldingPreset,
        missingLineTranslations: MutableList<String>
    ): List<FoldCandidate> {
        return PsiTreeUtil.collectElementsOfType(file, PyStatement::class.java)
            .filter { it.isFoldableSimpleStatement() }
            .mapNotNull { statement ->
                statementCandidate(statement, statement.textRange, preset, preferPsi = true, missingLineTranslations)
            }
    }

    private fun compoundHeaderCandidates(file: PyFile, preset: FoldingPreset): List<FoldCandidate> {
        val candidates = mutableListOf<FoldCandidate>()
        PsiTreeUtil.collectElementsOfType(file, PyIfStatement::class.java).forEach { statement ->
            candidates.addHeader(statement.ifPart, preset)
            statement.elifParts.forEach { candidates.addHeader(it, preset) }
            statement.elsePart?.let { candidates.addHeader(it, preset) }
        }
        PsiTreeUtil.collectElementsOfType(file, PyForStatement::class.java).forEach {
            candidates.addHeader(it.forPart, preset)
        }
        PsiTreeUtil.collectElementsOfType(file, PyWhileStatement::class.java).forEach {
            candidates.addHeader(it.whilePart, preset)
        }
        PsiTreeUtil.collectElementsOfType(file, PyWithStatement::class.java).forEach {
            statementCandidate(it, headerRange(it, it.statementList), preset)?.let(candidates::add)
        }
        PsiTreeUtil.collectElementsOfType(file, PyTryExceptStatement::class.java).forEach { statement ->
            candidates.addHeader(statement.tryPart, preset)
            statement.exceptParts.forEach { candidates.addHeader(it, preset) }
            statement.elsePart?.let { candidates.addHeader(it, preset) }
            statement.finallyPart?.let { candidates.addHeader(it, preset) }
        }
        return candidates
    }

    private fun MutableList<FoldCandidate>.addHeader(part: PyStatementPart, preset: FoldingPreset) {
        statementCandidate(part, headerRange(part), preset)?.let(::add)
    }

    private fun statementCandidate(
        element: PsiElement,
        range: TextRange?,
        preset: FoldingPreset,
        preferPsi: Boolean = false,
        missingLineTranslations: MutableList<String>? = null
    ): FoldCandidate? {
        val textRange = range ?: return null
        val source = flattenedSource(element, textRange)
        val cachedLineTranslation = if (preferPsi) readLineTranslation(element, source) else null
        val translated = if (preferPsi) {
            cachedLineTranslation
                ?: PsiStatementEnglish.describe(element, MAX_STATEMENT_PLACEHOLDER_CHARS)
                ?: EnglishViewProjector.translateLine(source)
        } else {
            EnglishViewProjector.translateLine(source)
        }.replace(Regex("""\s+"""), " ").trim()
        if (translated.isBlank() || translated == source) return null
        if (preferPsi && shouldTranslateLinesWithLlm(element) && cachedLineTranslation == null) {
            missingLineTranslations?.add(source)
        }
        return FoldCandidate(
            element = element,
            range = textRange,
            placeholder = EnglishViewProjector.truncateAtWordBoundary(translated, MAX_STATEMENT_PLACEHOLDER_CHARS),
            kind = FoldKind.STATEMENT,
            collapsedByDefault = preset.collapsesStatementFolds
        )
    }

    private fun flattenedSource(element: PsiElement, textRange: TextRange): String {
        return element.containingFile.text.substring(textRange.startOffset, textRange.endOffset)
            .replace(Regex("""\s+"""), " ")
            .trim()
            .let { if (element.isReaderHeaderAnchor() && !it.endsWith(":")) "$it:" else it }
    }

    private fun readLineTranslation(element: PsiElement, source: String): String? {
        if (!shouldTranslateLinesWithLlm(element)) return null
        return lineTranslationProvider(element.project).readTranslation(source)
    }

    private fun shouldTranslateLinesWithLlm(element: PsiElement): Boolean {
        return !element.isReaderHeaderAnchor() &&
            PyGlossProjectSettings.getInstance(element.project).translateLinesWithLlm
    }

    private fun scheduleMissingLineTranslations(result: FoldingBuildResult) {
        val file = result.file ?: return
        val statements = result.missingLineTranslations
        if (statements.isEmpty()) return
        ApplicationManager.getApplication().invokeLater {
            if (!file.project.isDisposed && file.isValid) {
                lineTranslationProvider(file.project).requestMissing(file, statements)
            }
        }
    }

    private fun headerRange(part: PyStatementPart): TextRange? {
        return headerRange(part, part.statementList)
    }

    private fun headerRange(element: PsiElement, statementList: PyStatementList): TextRange? {
        val start = element.textRange.startOffset
        val headerEnd = statementList.textRange.startOffset.coerceAtMost(element.textRange.endOffset)
        val fileText = element.containingFile.text
        val trimmedEnd = fileText.substring(start, headerEnd).trimEnd().length + start
        if (trimmedEnd <= start) return null
        return TextRange(start, trimmedEnd)
    }

    private fun comprehensionForRange(
        file: PyFile,
        range: TextRange,
        byRange: Map<TextRange, PyComprehensionElement>,
    ): PyComprehensionElement? {
        return byRange[range] ?: file.findElementAt(range.startOffset)?.let {
                PsiTreeUtil.getParentOfType(it, PyComprehensionElement::class.java, false)
            }
    }

    private fun ifForRange(
        file: PyFile,
        range: TextRange,
        byRange: Map<TextRange, PyIfStatement>,
    ): PyIfStatement? {
        return byRange[range] ?: file.findElementAt(range.startOffset)?.let {
                PsiTreeUtil.getParentOfType(it, PyIfStatement::class.java, false)
            }
    }

    private fun placeholderFor(element: PsiElement?): String? {
        return when (element) {
            is PyComprehensionElement -> comprehensionPlaceholder(element)
            is PyBinaryExpression -> if (element.isBooleanExpression()) booleanPlaceholder(element) else null
            is PyIfStatement -> if (element.isSingleReturnGuard()) singleReturnGuardPlaceholder(element) else null
            else -> null
        }
    }

    private fun comprehensionPlaceholder(element: PyComprehensionElement): String {
        val translated = EnglishViewProjector.translateComprehension(element.text)
        val fallback = if (Regex("""\bif\b""").containsMatchIn(element.text)) {
            "build collection by looping and filtering"
        } else "build collection by looping"
        return translated?.shortPlaceholder() ?: fallback
    }

    private fun booleanPlaceholder(element: PyBinaryExpression): String {
        val translated = EnglishViewProjector.translateCondition(element.text)
        return translated.takeUnless { it == element.text }?.shortPlaceholder() ?: "boolean condition"
    }

    private fun singleReturnGuardPlaceholder(element: PyIfStatement): String {
        val condition = element.ifPart.condition?.text?.let(EnglishViewProjector::translateCondition)
            ?: return "return when condition matches"
        val statement = element.ifPart.statementList.statements.singleOrNull() as? PyReturnStatement
            ?: return "return when condition matches"
        val value = statement.expression?.text?.let(EnglishViewProjector::translateExpression).orEmpty()
        val returnText = EnglishPhrases.returned(value.takeIf(String::isNotBlank))
        return "if $condition: $returnText".shortPlaceholder()
    }

    private fun PyBinaryExpression.isBooleanExpression(): Boolean {
        return hasBooleanOperator() ||
            PsiTreeUtil.findChildrenOfType(this, PyBinaryExpression::class.java).any {
                it !== this && it.hasBooleanOperator()
            }
    }

    private fun PyBinaryExpression.hasBooleanOperator(): Boolean {
        return getOperator() == PyTokenTypes.AND_KEYWORD || getOperator() == PyTokenTypes.OR_KEYWORD
    }

    private fun PyIfStatement.isSingleReturnGuard(): Boolean {
        if (elifParts.isNotEmpty() || elsePart != null) return false
        val statements = ifPart.statementList.statements
        return statements.size == 1 && statements.single() is PyReturnStatement
    }

    private fun PyStatement.isFoldableSimpleStatement(): Boolean {
        return when (this) {
            is PyAssignmentStatement,
            is PyAugAssignmentStatement,
            is PyExpressionStatement,
            is PyReturnStatement,
            is PyRaiseStatement,
            is PyAssertStatement,
            is PyPassStatement,
            is PyBreakStatement,
            is PyContinueStatement,
            is PyImportStatement,
            is PyFromImportStatement -> true
            else -> false
        }
    }

    private fun PsiElement.isReaderHeaderAnchor(): Boolean {
        return this is PyStatementPart || this is PyWithStatement
    }

    private fun PsiElement?.isReaderStatementFoldAnchor(): Boolean {
        return this is PyStatement || this is PyStatementPart
    }

    private fun flatten(blocks: List<EnglishBlock>): List<EnglishBlock> {
        val result = mutableListOf<EnglishBlock>()
        fun collect(items: List<EnglishBlock>) {
            items.forEach { block ->
                result.add(block)
                collect(block.children)
            }
        }
        collect(blocks)
        return result
    }

    private fun nonOverlapping(candidates: List<FoldCandidate>): List<FoldCandidate> {
        val selectedByStart = TreeMap<Int, FoldCandidate>()
        candidates.sortedWith(compareByDescending<FoldCandidate> { it.range.length }.thenBy { it.range.startOffset })
            .forEach { candidate ->
                val previous = selectedByStart.floorEntry(candidate.range.startOffset)?.value
                val next = selectedByStart.ceilingEntry(candidate.range.startOffset)?.value
                if (previous?.range?.intersects(candidate.range) != true &&
                    next?.range?.intersects(candidate.range) != true
                ) {
                    selectedByStart[candidate.range.startOffset] = candidate
                }
            }
        return selectedByStart.values.toList()
    }

    private data class FoldCandidate(
        val element: PsiElement,
        val range: TextRange,
        val placeholder: String,
        val kind: FoldKind,
        val collapsedByDefault: Boolean
    )

    private data class FoldingBuildResult(
        val descriptors: Array<FoldingDescriptor>,
        val file: PyFile?,
        val missingLineTranslations: List<String>
    )

    private enum class FoldKind(val groupPrefix: String) {
        IDIOM(PY_GLOSS_IDIOM_FOLDING_GROUP_PREFIX),
        STATEMENT(PY_GLOSS_STATEMENT_FOLDING_GROUP_PREFIX)
    }

    private fun String.shortPlaceholder(): String {
        return EnglishViewProjector.truncateAtWordBoundary(replace(Regex("""\s+"""), " "), MAX_PLACEHOLDER_CHARS)
    }

    private companion object {
        private const val MAX_PLACEHOLDER_CHARS = 80
        private const val MAX_STATEMENT_PLACEHOLDER_CHARS = 140
    }

}

/** Verbosity preset behavior used by deterministic folding. */
enum class FoldingPreset(
    /** Whether English fold placeholders are shown for this preset. */
    val showsEnglishFolds: Boolean,
    /** Whether statement-level English folds are produced. */
    val showsStatementFolds: Boolean,
    /** Whether tiny idiom fold regions start collapsed for this preset. */
    val collapsesIdiomFolds: Boolean,
    /** Whether statement fold regions start collapsed for this preset. */
    val collapsesStatementFolds: Boolean
) {
    /** Source-only preset with English render surfaces hidden. */
    CODE(showsEnglishFolds = false, showsStatementFolds = false, collapsesIdiomFolds = false, collapsesStatementFolds = false),
    /** Light hint preset that shows idiom folds but keeps source expanded. */
    HINTS(showsEnglishFolds = true, showsStatementFolds = false, collapsesIdiomFolds = false, collapsesStatementFolds = false),
    /** Outline-oriented preset that starts tiny idiom folds collapsed. */
    OUTLINE(showsEnglishFolds = true, showsStatementFolds = false, collapsesIdiomFolds = true, collapsesStatementFolds = false),
    /** Reader preset folds statements into English in the editor. */
    READER(showsEnglishFolds = true, showsStatementFolds = true, collapsesIdiomFolds = false, collapsesStatementFolds = true)
}

internal const val PY_GLOSS_IDIOM_FOLDING_GROUP_PREFIX = "dev.pygloss.idiom@"

internal const val PY_GLOSS_STATEMENT_FOLDING_GROUP_PREFIX = "dev.pygloss.statement@"

internal fun foldingPresetFor(preset: VerbosityLevel): FoldingPreset {
    return when (preset) {
        VerbosityLevel.CODE -> FoldingPreset.CODE
        VerbosityLevel.HINTS -> FoldingPreset.HINTS
        VerbosityLevel.OUTLINE -> FoldingPreset.OUTLINE
        VerbosityLevel.READER -> FoldingPreset.READER
    }
}

private fun readActivePreset(): FoldingPreset = foldingPresetFor(EditorPreferences.preset)
