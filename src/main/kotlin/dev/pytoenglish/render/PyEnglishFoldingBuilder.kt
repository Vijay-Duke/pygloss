package dev.pytoenglish.render

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyBinaryExpression
import com.jetbrains.python.psi.PyComprehensionElement
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyIfStatement
import com.jetbrains.python.psi.PyReturnStatement
import dev.pytoenglish.engine.BlockDetector
import dev.pytoenglish.model.BlockKind
import dev.pytoenglish.model.EnglishBlock

/** Synchronous folding builder for tiny deterministic Python idioms. */
class PyEnglishFoldingBuilder(
    private val presetProvider: () -> FoldingPreset = ::readActivePreset
) : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val file = root as? PyFile ?: return emptyArray()
        val preset = presetProvider()
        if (!preset.showsEnglishFolds) return emptyArray()

        val model = BlockDetector.detect(file)
        val candidates = buildList {
            addAll(comprehensionCandidates(file, model.blocks))
            addAll(booleanExpressionCandidates(file))
            addAll(obviousReturnCandidates(file, model.blocks))
        }

        return nonOverlapping(candidates)
            .map { candidate ->
                FoldingDescriptor(candidate.element.node, candidate.range, null, candidate.placeholder)
            }
            .toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String {
        return placeholderFor(node.psi) ?: "python idiom"
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean {
        return presetProvider().collapsesByDefault
    }

    private fun comprehensionCandidates(file: PyFile, blocks: List<EnglishBlock>): List<FoldCandidate> {
        return flatten(blocks)
            .filter { it.kind == BlockKind.COMPREHENSION }
            .mapNotNull { block ->
                val element = comprehensionForRange(file, block.textRange) ?: return@mapNotNull null
                FoldCandidate(
                    element = element,
                    range = element.textRange,
                    placeholder = comprehensionPlaceholder(element)
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
                    placeholder = "boolean condition"
                )
            }
    }

    private fun obviousReturnCandidates(file: PyFile, blocks: List<EnglishBlock>): List<FoldCandidate> {
        val ifBlocks = flatten(blocks).filter { it.kind == BlockKind.IF }
        return ifBlocks.mapNotNull { block ->
            val element = ifForRange(file, block.textRange) ?: return@mapNotNull null
            if (!element.isSingleReturnGuard()) return@mapNotNull null
            FoldCandidate(
                element = element,
                range = element.textRange,
                placeholder = "return when condition matches"
            )
        }
    }

    private fun comprehensionForRange(file: PyFile, range: TextRange): PyComprehensionElement? {
        return PsiTreeUtil.collectElementsOfType(file, PyComprehensionElement::class.java)
            .firstOrNull { it.textRange == range }
            ?: file.findElementAt(range.startOffset)?.let {
                PsiTreeUtil.getParentOfType(it, PyComprehensionElement::class.java, false)
            }
    }

    private fun ifForRange(file: PyFile, range: TextRange): PyIfStatement? {
        return PsiTreeUtil.collectElementsOfType(file, PyIfStatement::class.java)
            .firstOrNull { it.textRange == range }
            ?: file.findElementAt(range.startOffset)?.let {
                PsiTreeUtil.getParentOfType(it, PyIfStatement::class.java, false)
            }
    }

    private fun placeholderFor(element: PsiElement?): String? {
        return when (element) {
            is PyComprehensionElement -> comprehensionPlaceholder(element)
            is PyBinaryExpression -> if (element.isBooleanExpression()) "boolean condition" else null
            is PyIfStatement -> if (element.isSingleReturnGuard()) "return when condition matches" else null
            else -> null
        }
    }

    private fun comprehensionPlaceholder(element: PyComprehensionElement): String {
        return if (Regex("""\bif\b""").containsMatchIn(element.text)) {
            "build collection by looping and filtering"
        } else {
            "build collection by looping"
        }
    }

    private fun PyBinaryExpression.isBooleanExpression(): Boolean {
        return Regex("""\b(and|or)\b""").containsMatchIn(text)
    }

    private fun PyIfStatement.isSingleReturnGuard(): Boolean {
        if (elifParts.isNotEmpty() || elsePart != null) return false
        val statements = ifPart.statementList.statements
        return statements.size == 1 && statements.single() is PyReturnStatement
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
        val selected = mutableListOf<FoldCandidate>()
        candidates.sortedWith(compareByDescending<FoldCandidate> { it.range.length }.thenBy { it.range.startOffset })
            .forEach { candidate ->
                if (selected.none { it.range.intersects(candidate.range) }) {
                    selected.add(candidate)
                }
            }
        return selected.sortedBy { it.range.startOffset }
    }

    private data class FoldCandidate(
        val element: PsiElement,
        val range: TextRange,
        val placeholder: String
    )
}

/** Verbosity preset behavior used by deterministic folding. */
enum class FoldingPreset(
    /** Whether English fold placeholders are shown for this preset. */
    val showsEnglishFolds: Boolean,
    /** Whether fold regions start collapsed for this preset. */
    val collapsesByDefault: Boolean
) {
    /** Source-only preset with English render surfaces hidden. */
    CODE(showsEnglishFolds = false, collapsesByDefault = false),
    /** Light hint preset that shows idiom folds but keeps source expanded. */
    HINTS(showsEnglishFolds = true, collapsesByDefault = false),
    /** Outline-oriented preset that starts tiny idiom folds collapsed. */
    OUTLINE(showsEnglishFolds = true, collapsesByDefault = true),
    /** Reader preset that starts tiny idiom folds collapsed. */
    READER(showsEnglishFolds = true, collapsesByDefault = true)
}

private fun readActivePreset(): FoldingPreset {
    return FoldingPreset.HINTS
}
