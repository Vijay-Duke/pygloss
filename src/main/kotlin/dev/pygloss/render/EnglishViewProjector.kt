package dev.pygloss.render

import com.intellij.openapi.editor.Document
import dev.pygloss.cache.Profile
import dev.pygloss.cache.VerbosityLevel
import dev.pygloss.model.EnglishModel
import dev.pygloss.translation.EnglishPhrases

/** Builds the read-only English View text shown beside the Python source. */
object EnglishViewProjector {

    /** Return a whole-file English projection with stable source line numbers. */
    fun project(document: Document, model: EnglishModel): String {
        val summariesByLine = U6OverlayProjection
            .blockSummaries(model, InlayOverlaySettings(Profile.INTENT_SUMMARY, "JS", VerbosityLevel.READER))
            .filter { it.isSummary }
            .groupBy { document.getLineNumber(it.offset) }

        val lines = document.text.lines()
        return buildString {
            lines.forEachIndexed { index, line ->
                summariesByLine[index].orEmpty()
                    .filterNot { it.text.startsWith("summary pending:") }
                    .forEach { summary ->
                        appendLine("    ${summary.text}")
                    }
                appendLine("${(index + 1).toString().padStart(4)}  ${translateLine(line)}")
            }
        }.trimEnd()
    }

    /** Return deterministic English for one Python source line. */
    fun translateLine(line: String): String {
        val indent = line.takeWhile(Char::isWhitespace)
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("#")) return trimmed

        functionDefinition(trimmed)?.let { return indent + it }
        classDefinition(trimmed)?.let { return indent + it }
        forLoop(trimmed)?.let { return indent + it }
        ifStatement(trimmed)?.let { return indent + it }
        elseStatement(trimmed)?.let { return indent + it }
        whileStatement(trimmed)?.let { return indent + it }
        tryStatement(trimmed)?.let { return indent + it }
        exceptStatement(trimmed)?.let { return indent + it }
        finallyStatement(trimmed)?.let { return indent + it }
        withStatement(trimmed)?.let { return indent + it }
        returnStatement(trimmed)?.let { return indent + it }
        raiseStatement(trimmed)?.let { return indent + it }
        assertStatement(trimmed)?.let { return indent + it }
        simpleControlStatement(trimmed)?.let { return indent + it }
        importStatement(trimmed)?.let { return indent + it }
        emptyListAssignment(trimmed)?.let { return indent + it }
        appendCall(trimmed)?.let { return indent + it }
        genericCall(trimmed)?.let { return indent + it }
        augmentedAssignment(trimmed)?.let { return indent + it }
        assignment(trimmed)?.let { return indent + it }
        listComprehension(trimmed)?.let { return indent + it }

        return line
    }

    /** Return deterministic English for a Python expression fragment. */
    fun translateExpression(expression: String): String = englishExpr(expression)

    /** Return deterministic English only for comprehensions this projector understands. */
    fun translateComprehension(text: String): String? {
        val trimmed = text.trim()
        return listComprehensionExpression(trimmed)
            ?: dictComprehensionExpression(trimmed)
            ?: setComprehensionExpression(trimmed)
    }

    /** Return deterministic English for a Python condition fragment. */
    fun translateCondition(condition: String): String = englishCondition(condition)

    /** Shorten rendered English without cutting a word in half. */
    fun truncateAtWordBoundary(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val room = (maxChars - 1).coerceAtLeast(0)
        val prefix = text.take(room).trimEnd()
        val boundary = prefix.lastIndexOfAny(charArrayOf(' ', '\t', ',', ';', ':'))
        val shortened = if (boundary > 0) prefix.take(boundary).trimEnd() else prefix
        return "$shortened…"
    }

    private fun functionDefinition(text: String): String? {
        val match = Regex("""^(async\s+)?def\s+([A-Za-z_][\w]*)\((.*?)(?:\)\s*(?:->\s*([^:]+))?:?)?$""")
            .matchEntire(text) ?: return null
        val async = if (match.groupValues[1].isNotBlank()) "async " else ""
        val name = match.groupValues[2]
        val params = cleanParams(match.groupValues[3])
        val taking = if (params.isBlank()) "" else " taking $params"
        return "define ${async}$name$taking:"
    }

    private fun classDefinition(text: String): String? {
        val match = Regex("""^class\s+([A-Za-z_][\w]*)(?:\((.*)\))?:$""").matchEntire(text) ?: return null
        val name = match.groupValues[1]
        val bases = cleanParams(match.groupValues[2])
        return if (bases.isBlank()) {
            "define class $name:"
        } else {
            "define class $name based on $bases:"
        }
    }

    private fun forLoop(text: String): String? {
        val match = Regex("""^for\s+(.+?)\s+in\s+(.+):$""").matchEntire(text) ?: return null
        return "for each ${match.groupValues[1]} in ${englishExpr(match.groupValues[2])}:"
    }

    private fun ifStatement(text: String): String? {
        val match = Regex("""^(if|elif)\s+(.+):$""").matchEntire(text) ?: return null
        val prefix = if (match.groupValues[1] == "elif") "otherwise if" else "if"
        return "$prefix ${englishCondition(match.groupValues[2])}:"
    }

    private fun elseStatement(text: String): String? {
        return if (text == "else:") "otherwise:" else null
    }

    private fun whileStatement(text: String): String? {
        val match = Regex("""^while\s+(.+):$""").matchEntire(text) ?: return null
        return "while ${englishCondition(match.groupValues[1])}:"
    }

    private fun tryStatement(text: String): String? {
        return if (text == "try:") "try the following:" else null
    }

    private fun exceptStatement(text: String): String? {
        val match = Regex("""^except(?:\s+(.+?))?(?:\s+as\s+([A-Za-z_][\w]*))?:$""").matchEntire(text) ?: return null
        val error = match.groupValues[1].takeIf { it.isNotBlank() } ?: "an error"
        val alias = match.groupValues[2].takeIf { it.isNotBlank() }?.let { " (as $it)" }.orEmpty()
        return "if ${englishExpr(error)} happens$alias:"
    }

    private fun finallyStatement(text: String): String? {
        return if (text == "finally:") "finally, always:" else null
    }

    private fun withStatement(text: String): String? {
        val match = Regex("""^with\s+(.+):$""").matchEntire(text) ?: return null
        return "using ${englishExpr(match.groupValues[1])}:"
    }

    private fun returnStatement(text: String): String? {
        val match = Regex("""^return(?:\s+(.+))?$""").matchEntire(text) ?: return null
        val expr = match.groupValues[1].takeIf { it.isNotBlank() }
        return EnglishPhrases.returned(expr?.let(::englishExpr))
    }

    private fun raiseStatement(text: String): String? {
        val match = Regex("""^raise\s+(.+)$""").matchEntire(text) ?: return null
        return "signal ${englishExpr(match.groupValues[1])}"
    }

    private fun assertStatement(text: String): String? {
        val match = Regex("""^assert\s+(.+)$""").matchEntire(text) ?: return null
        return "check that ${englishCondition(match.groupValues[1])}"
    }

    private fun simpleControlStatement(text: String): String? {
        return when (text) {
            "pass" -> "do nothing"
            "break" -> "stop the loop"
            "continue" -> "skip to the next iteration"
            else -> null
        }
    }

    private fun importStatement(text: String): String? {
        Regex("""^import\s+(.+)$""").matchEntire(text)?.let {
            return "use module ${it.groupValues[1]}"
        }
        Regex("""^from\s+(.+?)\s+import\s+(.+)$""").matchEntire(text)?.let {
            return "from module ${it.groupValues[1]} use ${it.groupValues[2]}"
        }
        return null
    }

    private fun emptyListAssignment(text: String): String? {
        val match = Regex("""^([A-Za-z_][\w]*)\s*=\s*\[\]$""").matchEntire(text) ?: return null
        return "start an empty list ${match.groupValues[1]}"
    }

    private fun appendCall(text: String): String? {
        val match = Regex("""^([A-Za-z_][\w]*)\.append\((.+)\)$""").matchEntire(text) ?: return null
        return "add ${englishExpr(match.groupValues[2])} to ${match.groupValues[1]}"
    }

    private fun genericCall(text: String): String? {
        val opening = text.indexOf('(')
        if (opening <= 0 || !text.endsWith(')')) return null
        val rawTarget = text.substring(0, opening).trim()
        if (!rawTarget.matches(Regex("""[A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)*"""))) return null

        val target = rawTarget.split('.').map { it.replace('_', ' ') }
            .reduce(EnglishPhrases::attribute)
        val positionals = mutableListOf<String>()
        val options = mutableListOf<EnglishPhrases.CallOption>()
        splitTopLevelCommas(text.substring(opening + 1, text.lastIndex))
            .filter { it.isNotBlank() }
            .forEach { argument ->
                val option = splitDepthZeroAssignment(argument)
                    ?.takeIf { it.left.matches(Regex("""[A-Za-z_][\w]*""")) }
                if (option == null) {
                    positionals += englishExpr(argument)
                } else {
                    options += EnglishPhrases.CallOption(option.left, englishExpr(option.right))
                }
            }
        return EnglishPhrases.call(EnglishPhrases.CallPhrase(target, positionals, options))
    }

    private fun augmentedAssignment(text: String): String? {
        val split = splitDepthZeroAugmentedAssignment(text) ?: return null
        val target = englishExpr(split.left)
        val operator = split.operator.removeSuffix("=")
        val value = englishExpr(split.right)
        return EnglishPhrases.augmentedAssignment(target, operator, value)
    }

    private fun assignment(text: String): String? {
        val split = splitDepthZeroAssignment(text) ?: return null
        val target = englishExpr(split.left)
        val value = split.right
        listComprehension(text)?.let { return it }
        dictComprehension(text)?.let { return it }
        setComprehension(text)?.let { return it }
        return EnglishPhrases.assignment(target, englishExpr(value))
    }

    private fun listComprehension(text: String): String? {
        val match = Regex("""^([A-Za-z_][\w]*)\s*=\s*\[(.+?)\s+for\s+(.+?)\s+in\s+(.+?)(?:\s+if\s+(.+))?]$""")
            .matchEntire(text) ?: return null
        val target = match.groupValues[1]
        val comprehension = listComprehensionExpression(text.substringAfter("=").trim()) ?: return null
        return EnglishPhrases.assignment(target, comprehension)
    }

    private fun dictComprehension(text: String): String? {
        val match = Regex("""^([A-Za-z_][\w]*)\s*=\s*\{(.+?):\s*(.+?)\s+for\s+(.+?)\s+in\s+(.+?)(?:\s+if\s+(.+))?}$""")
            .matchEntire(text) ?: return null
        val target = match.groupValues[1]
        val comprehension = dictComprehensionExpression(text.substringAfter("=").trim()) ?: return null
        return EnglishPhrases.assignment(target, comprehension)
    }

    private fun setComprehension(text: String): String? {
        val match = Regex("""^([A-Za-z_][\w]*)\s*=\s*\{(.+?)\s+for\s+(.+?)\s+in\s+(.+?)(?:\s+if\s+(.+))?}$""")
            .matchEntire(text) ?: return null
        val target = match.groupValues[1]
        val comprehension = setComprehensionExpression(text.substringAfter("=").trim()) ?: return null
        return EnglishPhrases.assignment(target, comprehension)
    }

    private fun listComprehensionExpression(text: String): String? {
        val match = Regex("""^\[(.+?)\s+for\s+(.+?)\s+in\s+(.+?)(?:\s+if\s+(.+))?]$""")
            .matchEntire(text) ?: return null
        val item = englishExpr(match.groupValues[1])
        val variable = match.groupValues[2]
        val source = englishExpr(match.groupValues[3])
        val condition = match.groupValues[4].takeIf { it.isNotBlank() }?.let(::englishCondition)
        return buildString {
            append("the list of $item, for every $variable in $source")
            if (condition != null) append(" where $condition")
        }
    }

    private fun dictComprehensionExpression(text: String): String? {
        val match = Regex("""^\{(.+?):\s*(.+?)\s+for\s+(.+?)\s+in\s+(.+?)(?:\s+if\s+(.+))?}$""")
            .matchEntire(text) ?: return null
        val key = englishExpr(match.groupValues[1])
        val value = englishExpr(match.groupValues[2])
        val variable = match.groupValues[3]
        val source = englishExpr(match.groupValues[4])
        val condition = match.groupValues[5].takeIf { it.isNotBlank() }?.let(::englishCondition)
        return buildString {
            append("the mapping of $key to $value, for every $variable in $source")
            if (condition != null) append(" where $condition")
        }
    }

    private fun setComprehensionExpression(text: String): String? {
        val match = Regex("""^\{(.+?)\s+for\s+(.+?)\s+in\s+(.+?)(?:\s+if\s+(.+))?}$""")
            .matchEntire(text) ?: return null
        val item = englishExpr(match.groupValues[1])
        val variable = match.groupValues[2]
        val source = englishExpr(match.groupValues[3])
        val condition = match.groupValues[4].takeIf { it.isNotBlank() }?.let(::englishCondition)
        return buildString {
            append("the set of $item, for every $variable in $source")
            if (condition != null) append(" where $condition")
        }
    }

    private fun englishCondition(expr: String): String {
        val normalizedNot = expr.replace(Regex("""\bnot\s+(?:([A-Za-z_][\w]*)\.)?([A-Za-z_][\w]*)""")) {
            val receiver = it.groupValues[1]
            val name = it.groupValues[2]
            if (receiver.isBlank()) {
                "$name is not true"
            } else {
                "$receiver is not $name"
            }
        }
        return englishExpr(normalizedNot)
            .replace(" == ", " is ")
            .replace(" != ", " is not ")
            .replace(" >= ", " is at least ")
            .replace(" <= ", " is at most ")
            .replace(" > ", " is over ")
            .replace(" < ", " is under ")
            .replace(" and ", " and ")
            .replace(" or ", " or ")
    }

    private fun englishExpr(expr: String): String {
        translateComprehension(expr.trim())?.let { return it }
        return replaceOutsideQuotedStrings(expr.trim()) { segment ->
            segment.replace("*", "×")
                .replace(Regex("""\b([A-Za-z_][\w]*)\.items\(\)""")) {
                    "key-value pairs from ${it.groupValues[1]}"
                }
                .replace(Regex("""\b([A-Za-z_][\w]*)\.keys\(\)""")) {
                    "keys from ${it.groupValues[1]}"
                }
                .replace(Regex("""\b([A-Za-z_][\w]*)\.values\(\)""")) {
                    "values from ${it.groupValues[1]}"
                }
                .replace(Regex("""\b([A-Za-z_][\w]*)\.([A-Za-z_][\w]*)\b""")) {
                EnglishPhrases.attribute(it.groupValues[1], it.groupValues[2])
            }
        }
    }

    private fun replaceOutsideQuotedStrings(text: String, transform: (String) -> String): String {
        val result = StringBuilder()
        val plain = StringBuilder()
        var quote: Char? = null
        var escaped = false

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                result.append(transform(plain.toString()))
                plain.clear()
            }
        }

        for (char in text) {
            val activeQuote = quote
            if (activeQuote == null) {
                if (char == '\'' || char == '"') {
                    flushPlain()
                    quote = char
                    escaped = false
                    result.append(char)
                } else {
                    plain.append(char)
                }
            } else {
                result.append(char)
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == activeQuote) {
                    quote = null
                }
            }
        }
        flushPlain()
        return result.toString()
    }

    private fun splitDepthZeroAssignment(text: String): AssignmentSplit? {
        val index = firstDepthZeroOperator(text, listOf("=")) ?: return null
        if (!isPlainAssignmentAt(text, index)) return null
        return splitAt(text, index, "=")
    }

    private fun splitDepthZeroAugmentedAssignment(text: String): AssignmentSplit? {
        val operators = listOf("**=", "//=", ">>=", "<<=", "+=", "-=", "*=", "/=", "%=", "|=", "&=", "^=", "@=")
        val index = firstDepthZeroOperator(text, operators) ?: return null
        val operator = operators.first { text.startsWith(it, index) }
        return splitAt(text, index, operator)
    }

    private fun firstDepthZeroOperator(text: String, operators: List<String>): Int? {
        var quote: Char? = null
        var escaped = false
        var depth = 0
        text.forEachIndexed { index, char ->
            val activeQuote = quote
            if (activeQuote != null) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == activeQuote) quote = null
                return@forEachIndexed
            }
            when (char) {
                '\'', '"' -> {
                    quote = char
                    escaped = false
                }
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
                else -> if (depth == 0 && operators.any { text.startsWith(it, index) }) return index
            }
        }
        return null
    }

    private fun isPlainAssignmentAt(text: String, index: Int): Boolean {
        val previous = text.getOrNull(index - 1)
        val next = text.getOrNull(index + 1)
        if (next == '=') return false
        if (previous != null && previous in "=!<>+-*/%|&^@:") return false
        return true
    }

    private fun splitAt(text: String, index: Int, operator: String): AssignmentSplit? {
        val left = text.substring(0, index).trim()
        val right = text.substring(index + operator.length).trim()
        if (left.isBlank() || right.isBlank()) return null
        return AssignmentSplit(left, operator, right)
    }

    private fun cleanParams(params: String): String {
        return splitTopLevelCommas(params)
            .mapNotNull(::cleanParam)
            .filter { it.isNotBlank() && it != "self" && it != "cls" }
            .joinToString(", ")
    }

    private fun splitTopLevelCommas(text: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var depth = 0
        var quote: Char? = null
        var escaped = false
        text.forEachIndexed { index, char ->
            val activeQuote = quote
            if (activeQuote != null) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == activeQuote) quote = null
            } else {
                when (char) {
                    '\'', '"' -> quote = char
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
                    ',' -> if (depth == 0) {
                        parts.add(text.substring(start, index))
                        start = index + 1
                    }
                }
            }
        }
        parts.add(text.substring(start))
        return parts
    }

    private fun cleanParam(param: String): String? {
        val text = param.trim().trimEnd(',')
        if (text.isBlank() || text == ")" || text == "*") return null
        val parts = text.split("=", limit = 2)
        val left = parts[0].trim()
        val default = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        val name = left.substringBefore(":").trim()
        if (name.isBlank() || name == "self" || name == "cls") return null
        val type = left.substringAfter(":", "").trim().takeIf { it.isNotBlank() }
        val details = listOfNotNull(
            type?.let { typeText -> "${articleFor(typeText)} $typeText" },
            default?.let { "defaults to $it" }
        )
        return if (details.isEmpty()) name else "$name (${details.joinToString(", ")})"
    }

    private fun articleFor(text: String): String {
        val first = text.firstOrNull()?.lowercaseChar()
        return if (first in setOf('a', 'e', 'i', 'o', 'u')) "an" else "a"
    }

    private data class AssignmentSplit(
        val left: String,
        val operator: String,
        val right: String
    )
}
