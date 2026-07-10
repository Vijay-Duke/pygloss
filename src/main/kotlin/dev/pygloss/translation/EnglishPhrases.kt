package dev.pygloss.translation

/** Shared wording primitives used by both PSI and text-based translators. */
object EnglishPhrases {
    data class CallOption(val name: String, val value: String)

    data class CallPhrase(
        val target: String,
        val positionals: List<String> = emptyList(),
        val options: List<CallOption> = emptyList(),
    )

    fun assignment(target: String, value: String): String = "set $target to $value"

    fun assignment(targets: List<String>, value: String): String = assignment(targets.joinToString(", "), value)

    fun augmentedAssignment(target: String, operator: String, value: String): String {
        return when (operator.removeSuffix("=")) {
            "+" -> "increase $target by $value"
            "-" -> "decrease $target by $value"
            "*" -> assignment(target, "$target × $value")
            else -> assignment(target, "$target ${operator.removeSuffix("=")} $value")
        }
    }

    fun returned(value: String?): String = value?.let { "give back $it" } ?: "give back"

    fun call(phrase: CallPhrase): String {
        val positionalText = phrase.positionals.takeIf { it.isNotEmpty() }
            ?.joinToString(", ", prefix = " with ")
            .orEmpty()
        val optionText = phrase.options.takeIf { it.isNotEmpty() }
            ?.joinToString(", ", prefix = " — options: ") { "${it.name} ${it.value}" }
            .orEmpty()
        return "call ${phrase.target}$positionalText$optionText"
    }

    fun attribute(receiver: String, name: String): String = "$receiver's $name"

    fun binary(left: String, operator: String, right: String): String = "$left ${operatorWords(operator)} $right"

    fun operatorWords(operator: String): String {
        return when (operator) {
            "*" -> "×"
            "==" -> "is"
            "!=" -> "is not"
            ">=" -> "is at least"
            "<=" -> "is at most"
            ">" -> "is over"
            "<" -> "is under"
            else -> operator
        }
    }
}
