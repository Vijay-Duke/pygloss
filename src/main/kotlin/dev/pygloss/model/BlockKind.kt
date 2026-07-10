package dev.pygloss.model

/** Kind of semantic block detected in a Python file. */
enum class BlockKind {
    MODULE,
    CLASS,
    FUNCTION,
    IF,
    FOR,
    WHILE,
    WITH,
    TRY,
    COMPREHENSION
}
