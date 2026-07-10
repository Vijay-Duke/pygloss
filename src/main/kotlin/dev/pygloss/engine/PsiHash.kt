package dev.pygloss.engine

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import dev.pygloss.model.EnglishBlock
import java.security.MessageDigest

/** Computes normalized semantic PSI hashes for English blocks (KTD5). */
object PsiHash {

    /**
     * Compute a deterministic SHA-256 hash for the given block.
     * Includes kind, skeleton facts, concept markers, and child block hashes.
     */
    fun computeHash(block: EnglishBlock): String {
        return hash(block)
    }

    /** Compute a normalized semantic hash for a detected block and its PSI source. */
    fun hash(block: EnglishBlock, element: PsiElement? = null): String {
        val normalized = buildString {
            append(block.kind.name)
            append("|")
            append(block.skeleton)
            append("|")
            append(block.concepts.sortedBy { it.name }.joinToString(",") { it.name })
            append("|shape:")
            append(element?.let { normalizedPsiShape(it) }.orEmpty())
            append("|children:")
            append(
                block.children.joinToString(",") {
                    "${it.kind.name}:${it.skeleton}:${it.concepts.sortedBy { concept -> concept.name }.joinToString("+")}:${it.psiHash}"
                }
            )
        }
        return sha256Hex(normalized)
    }

    /** Normalize PSI text by removing comments/whitespace and replacing literal values. */
    fun normalizedPsiShape(element: PsiElement): String {
        val tokens = mutableListOf<String>()
        fun visit(node: PsiElement) {
            when (node) {
                is PsiWhiteSpace, is PsiComment -> return
            }
            val firstChild = node.firstChild
            if (firstChild == null) {
                normalizeLeaf(node.text)?.let(tokens::add)
                return
            }
            var child: PsiElement? = firstChild
            while (child != null) {
                visit(child)
                child = child.nextSibling
            }
        }
        visit(element)
        return tokens.joinToString(" ")
    }

    /** SHA-256 hex digest of a string. */
    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun normalizeLeaf(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return when {
            STRING_LITERAL.matches(trimmed) -> "<literal>"
            NUMBER_LITERAL.matches(trimmed) -> "<literal>"
            trimmed in PYTHON_CONSTANT_LITERALS -> "<literal>"
            else -> trimmed
        }
    }

    private val STRING_LITERAL = Regex(
        """(?is)(?:[rubf]+)?(?:'''[\s\S]*?'''|\"\"\"[\s\S]*?\"\"\"|'(?:\\.|[^'])*'|"(?:\\.|[^"])*")"""
    )
    private val NUMBER_LITERAL = Regex("""(?i)(?:0x[0-9a-f_]+|0b[01_]+|0o[0-7_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:e[+-]?\d[\d_]*)?j?)""")
    private val PYTHON_CONSTANT_LITERALS = setOf("True", "False", "None", "Ellipsis")
}
