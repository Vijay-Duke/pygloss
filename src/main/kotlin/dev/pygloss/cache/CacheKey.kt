package dev.pygloss.cache

/**
 * Cache key for EnglishModel entries.
 * Combines file identity, profile/level, and version tracking (KTD5/R9).
 */
data class CacheKey(
    /** SHA-256 hex of normalized PSI structure (from PsiHash). */
    val normalizedPsiHash: String,
    /** Reader profile (Polyglot Lens or Intent Summary). */
    val profile: Profile,
    /** Verbosity level preset. */
    val level: VerbosityLevel,
    /** Intent Summary prompt version (bumped when prompt wording changes). */
    val promptVersion: Int,
    /** Plugin version (bumped on release). */
    val pluginVersion: Int
) {
    /** Whether this key targets the LLM-backed Intent Summary profile. */
    val isLlmProfile: Boolean get() = profile == Profile.INTENT_SUMMARY
}
