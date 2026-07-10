package dev.pygloss.render

import com.intellij.psi.PsiElement
import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyAugAssignmentStatement
import com.jetbrains.python.psi.PyBinaryExpression
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyDictLiteralExpression
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyExpressionStatement
import com.jetbrains.python.psi.PyKeywordArgument
import com.jetbrains.python.psi.PyListLiteralExpression
import com.jetbrains.python.psi.PyNumericLiteralExpression
import com.jetbrains.python.psi.PyParenthesizedExpression
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyReturnStatement
import com.jetbrains.python.psi.PySequenceExpression
import com.jetbrains.python.psi.PySetLiteralExpression
import com.jetbrains.python.psi.PyStringLiteralExpression
import com.jetbrains.python.psi.PyTupleExpression
import dev.pygloss.translation.EnglishPhrases

/** PSI-backed English for simple Python statements used by Reader folds. */
object PsiStatementEnglish {
    private const val DEFAULT_BUDGET = 140
    private const val MAX_DEPTH = 3

    fun describe(statement: PsiElement): String? = describe(statement, DEFAULT_BUDGET)

    fun describe(statement: PsiElement, maxChars: Int): String? {
        return when (statement) {
            is PyExpressionStatement -> {
                val call = statement.expression as? PyCallExpression ?: return null
                describeCallStatement(call, maxChars)
            }
            is PyAssignmentStatement -> describeAssignment(statement)
            is PyAugAssignmentStatement -> describeAugAssignment(statement)
            is PyReturnStatement -> describeReturn(statement)
            else -> null
        }?.normalized()
    }

    fun describeExpression(expr: PyExpression): String = describeExpression(expr, 0, true)

    private fun describeAssignment(statement: PyAssignmentStatement): String? {
        val value = statement.assignedValue ?: return null
        val targets = statement.targets.takeIf { it.isNotEmpty() } ?: return null
        return EnglishPhrases.assignment(targets.map(::describeTarget), describeExpression(value))
    }

    private fun describeAugAssignment(statement: PyAugAssignmentStatement): String? {
        val target = statement.target
        val value = statement.getValue() ?: return null
        val targetText = describeTarget(target)
        val valueText = describeExpression(value)
        val operator = statement.operation?.text ?: return null
        return EnglishPhrases.augmentedAssignment(targetText, operator, valueText)
    }

    private fun describeReturn(statement: PyReturnStatement): String {
        return EnglishPhrases.returned(statement.expression?.let(::describeExpression))
    }

    private fun describeCallStatement(call: PyCallExpression, maxChars: Int): String {
        val target = describeCallTarget(call)
        val arguments = describeArguments(call)
        return budgetedCall(target, arguments, maxChars)
    }

    private fun describeExpression(expr: PyExpression, depth: Int, summarizeCollections: Boolean): String {
        if (depth >= MAX_DEPTH) return cleaned(expr.text)
        return when (expr) {
            is PyCallExpression -> describeCallExpression(expr, depth)
            is PyStringLiteralExpression -> expr.text
            is PyNumericLiteralExpression,
            is PyReferenceExpression -> cleaned(expr.text)
            is PyListLiteralExpression -> describeSequence("list", expr, depth, summarizeCollections)
            is PyTupleExpression -> describeSequence("tuple", expr, depth, summarizeCollections)
            is PyParenthesizedExpression -> expr.containedExpression?.let {
                describeExpression(it, depth, summarizeCollections)
            } ?: cleaned(expr.text)
            is PySetLiteralExpression -> describeSequence("set", expr, depth, summarizeCollections)
            is PyDictLiteralExpression -> describeDict(expr, depth, summarizeCollections)
            is PyBinaryExpression -> describeBinary(expr, depth)
            else -> cleaned(expr.text)
        }
    }

    private fun describeCallExpression(call: PyCallExpression, depth: Int): String {
        val arguments = describeArguments(call, depth + 1)
        val inside = buildExpressionArgumentText(arguments)
        return if (inside.isBlank()) describeCallTarget(call) else "${describeCallTarget(call)}($inside)"
    }

    private fun describeCallTarget(call: PyCallExpression): String {
        val callee = call.callee
        if (callee is PyReferenceExpression) {
            val name = callee.referencedName?.words() ?: cleaned(callee.text)
            val receiver = callee.qualifier?.let { describeExpression(it, 1, true) }
            if (!receiver.isNullOrBlank()) return EnglishPhrases.attribute(receiver, name)
            return name
        }
        return cleaned(callee?.text.orEmpty()).ifBlank { cleaned(call.text.substringBefore("(")) }
    }

    private fun describeArguments(call: PyCallExpression, depth: Int = 0): DescribedArguments {
        val positionals = mutableListOf<String>()
        val options = mutableListOf<OptionArgument>()
        call.arguments.forEach { argument ->
            if (argument is PyKeywordArgument) {
                val value = argument.valueExpression?.let { describeExpression(it, depth + 1, true) }
                    ?: cleaned(argument.text.substringAfter("=", ""))
                options.add(OptionArgument(argument.keyword ?: argument.name.orEmpty(), value))
            } else {
                positionals.add(describeExpression(argument, depth + 1, true))
            }
        }
        return DescribedArguments(positionals, options.filter { it.name.isNotBlank() })
    }

    private fun budgetedCall(target: String, arguments: DescribedArguments, maxChars: Int): String {
        val phrase = EnglishPhrases.CallPhrase(
            target,
            arguments.positionals,
            arguments.options.map { EnglishPhrases.CallOption(it.name, it.value) },
        )
        val full = EnglishPhrases.call(phrase)
        if (full.length <= maxChars) return full

        val prefix = EnglishPhrases.call(phrase.copy(options = emptyList()))
        if (arguments.options.isEmpty()) return prefix

        for (keep in arguments.options.size - 1 downTo 1) {
            val hidden = arguments.options.size - keep
            val rendered = arguments.options.take(keep).joinToString(", ") { it.render() }
            val collapsed = "$prefix — options: $rendered, +$hidden more"
            if (collapsed.length <= maxChars) return collapsed
        }
        val collapsed = "$prefix — options: ${arguments.options.first().render()}, +${arguments.options.size - 1} more"
        return EnglishViewProjector.truncateAtWordBoundary(collapsed, maxChars)
    }

    private fun buildExpressionArgumentText(arguments: DescribedArguments): String {
        val parts = arguments.positionals.toMutableList()
        parts.addAll(arguments.options.map { it.render() })
        return parts.joinToString(", ")
    }

    private fun describeSequence(
        kind: String,
        expression: PySequenceExpression,
        depth: Int,
        summarizeCollections: Boolean
    ): String {
        val elements = expression.elements.toList()
        if (elements.size > 2 && summarizeCollections) {
            val first = describeExpression(elements.first(), depth + 1, false)
            return "a $kind of ${elements.size} entries: $first, +${elements.size - 1} more"
        }
        return elements.joinToString(", ", prefixFor(kind), suffixFor(kind)) {
            describeExpression(it, depth + 1, false)
        }
    }

    private fun describeDict(
        expression: PyDictLiteralExpression,
        depth: Int,
        summarizeCollections: Boolean
    ): String {
        val elements = expression.elements.toList()
        if (elements.size > 2 && summarizeCollections) {
            val first = describeExpression(elements.first(), depth + 1, false)
            return "a dict of ${elements.size} entries: $first, +${elements.size - 1} more"
        }
        return elements.joinToString(", ", "{", "}") { describeExpression(it, depth + 1, false) }
    }

    private fun describeBinary(expr: PyBinaryExpression, depth: Int): String {
        val left = expr.leftExpression ?: return cleaned(expr.text)
        val right = expr.rightExpression ?: return cleaned(expr.text)
        val operator = expr.psiOperator?.text ?: expr.operator?.toString() ?: return cleaned(expr.text)
        return EnglishPhrases.binary(
            describeExpression(left, depth + 1, true),
            operator,
            describeExpression(right, depth + 1, true),
        )
    }

    private fun describeTarget(expr: PyExpression): String {
        return when (expr) {
            is PyTupleExpression -> expr.elements.joinToString(", ") { describeTarget(it) }
            else -> cleaned(expr.text)
        }
    }

    private fun prefixFor(kind: String): String = when (kind) {
        "list" -> "["
        "tuple" -> "("
        "set" -> "{"
        else -> ""
    }

    private fun suffixFor(kind: String): String = when (kind) {
        "list" -> "]"
        "tuple" -> ")"
        "set" -> "}"
        else -> ""
    }

    private fun cleaned(text: String): String = EnglishViewProjector.translateExpression(text)

    private fun String.words(): String = split("_").filter { it.isNotBlank() }.joinToString(" ")

    private fun String.normalized(): String = replace(Regex("""\s+"""), " ").trim()

    private data class DescribedArguments(
        val positionals: List<String>,
        val options: List<OptionArgument>
    )

    private data class OptionArgument(val name: String, val value: String) {
        fun render(): String = "$name $value"
    }
}
