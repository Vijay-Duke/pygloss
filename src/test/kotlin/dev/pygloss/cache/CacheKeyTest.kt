package dev.pygloss.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Tests for [CacheKey] equality and hashing semantics. */
class CacheKeyTest {

    @Test
    fun testSameFieldsProduceEqualKeys() {
        val key1 = CacheKey("abc123", Profile.POLYGLOT_LENS, VerbosityLevel.HINTS, 1, 1)
        val key2 = CacheKey("abc123", Profile.POLYGLOT_LENS, VerbosityLevel.HINTS, 1, 1)
        assertEquals(key1, key2)
        assertEquals(key1.hashCode(), key2.hashCode())
    }

    @Test
    fun testDifferentPsiHashProducesDifferentKey() {
        val key1 = CacheKey("abc123", Profile.POLYGLOT_LENS, VerbosityLevel.HINTS, 1, 1)
        val key2 = CacheKey("def456", Profile.POLYGLOT_LENS, VerbosityLevel.HINTS, 1, 1)
        assertNotEquals(key1, key2)
    }

    @Test
    fun testDifferentProfileProducesDifferentKey() {
        val key1 = CacheKey("abc123", Profile.POLYGLOT_LENS, VerbosityLevel.HINTS, 1, 1)
        val key2 = CacheKey("abc123", Profile.INTENT_SUMMARY, VerbosityLevel.HINTS, 1, 1)
        assertNotEquals(key1, key2)
    }

    @Test
    fun testDifferentLevelProducesDifferentKey() {
        val key1 = CacheKey("abc123", Profile.POLYGLOT_LENS, VerbosityLevel.CODE, 1, 1)
        val key2 = CacheKey("abc123", Profile.POLYGLOT_LENS, VerbosityLevel.READER, 1, 1)
        assertNotEquals(key1, key2)
    }

    @Test
    fun testDifferentPromptVersionProducesDifferentKey() {
        val key1 = CacheKey("abc123", Profile.INTENT_SUMMARY, VerbosityLevel.HINTS, 1, 1)
        val key2 = CacheKey("abc123", Profile.INTENT_SUMMARY, VerbosityLevel.HINTS, 2, 1)
        assertNotEquals(key1, key2)
    }

    @Test
    fun testDifferentPluginVersionProducesDifferentKey() {
        val key1 = CacheKey("abc123", Profile.INTENT_SUMMARY, VerbosityLevel.HINTS, 1, 1)
        val key2 = CacheKey("abc123", Profile.INTENT_SUMMARY, VerbosityLevel.HINTS, 1, 2)
        assertNotEquals(key1, key2)
    }

    @Test
    fun testIsLlmProfileReturnsTrueForIntentSummary() {
        val key = CacheKey("abc123", Profile.INTENT_SUMMARY, VerbosityLevel.HINTS, 1, 1)
        assertEquals(true, key.isLlmProfile)
    }

    @Test
    fun testIsLlmProfileReturnsFalseForPolyglotLens() {
        val key = CacheKey("abc123", Profile.POLYGLOT_LENS, VerbosityLevel.HINTS, 1, 1)
        assertEquals(false, key.isLlmProfile)
    }

    @Test
    fun testCopyProducesEqualKey() {
        val key = CacheKey("abc123", Profile.POLYGLOT_LENS, VerbosityLevel.HINTS, 1, 1)
        val copy = key.copy()
        assertEquals(key, copy)
        assertEquals(key.hashCode(), copy.hashCode())
    }

    @Test
    fun testCopyWithDifferentHashProducesDifferentKey() {
        val key = CacheKey("abc123", Profile.POLYGLOT_LENS, VerbosityLevel.HINTS, 1, 1)
        val copy = key.copy(normalizedPsiHash = "different")
        assertNotEquals(key, copy)
    }
}
