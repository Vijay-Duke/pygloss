package dev.pygloss.cache

/** Reader profile that determines how English models are generated. */
enum class Profile {
    /** Deterministic Polyglot Lens (no LLM). */
    POLYGLOT_LENS,
    /** LLM-based Intent Summary. */
    INTENT_SUMMARY
}

/** Verbosity preset controlling detail level. */
enum class VerbosityLevel {
    CODE,
    HINTS,
    OUTLINE,
    READER
}
