package dev.pytoenglish.cache

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.psi.PyFile
import dev.pytoenglish.engine.BlockDetector
import dev.pytoenglish.engine.EnglishModelService
import dev.pytoenglish.engine.PsiHash
import dev.pytoenglish.model.EnglishBlock
import dev.pytoenglish.model.EnglishModel
import java.io.File
import java.nio.file.Files

/** Tests for [ModelCacheService] cache hit/miss, invalidation, eviction, and stale tracking. */
class ModelCacheServiceTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        val cache = ModelCacheService.getInstance(project)
        cache.clearCache()
        cache.maxCacheSize = 100
        cache.setPromptVersion(1)
        cache.setPluginVersion(1)
    }

    private fun configureAndGet(code: String): PyFile {
        return myFixture.configureByText("test.py", code) as PyFile
    }

    private fun detect(code: String): EnglishModel {
        val file = myFixture.configureByText("sample.py", code) as PyFile
        return BlockDetector.detect(file)
    }

    private fun modelHash(model: EnglishModel): String {
        val blockHashes = buildList {
            fun collect(blocks: List<EnglishBlock>) {
                for (block in blocks) {
                    add("${block.stableId}:${block.psiHash}")
                    collect(block.children)
                }
            }
            collect(model.blocks)
        }
        return PsiHash.sha256Hex(blockHashes.joinToString("|"))
    }

    private fun flatten(model: EnglishModel): List<EnglishBlock> {
        val result = mutableListOf<EnglishBlock>()
        fun collect(blocks: List<EnglishBlock>) {
            for (block in blocks) {
                result.add(block)
                collect(block.children)
            }
        }
        collect(model.blocks)
        return result
    }

    // ---- Test: Same file + same key → second lookup returns cached instance ----

    fun testCacheHitReturnsCachedInstance() {
        val model = detect(
            """
            def greet(name):
                return f"Hello, {name}!"
            """.trimIndent()
        )
        val cache = ModelCacheService.getInstance(project)
        val key = cache.keyFor(modelHash(model), Profile.POLYGLOT_LENS, VerbosityLevel.HINTS)
        var buildCount = 0

        val first = cache.getOrBuild(key) {
            buildCount++
            model
        }
        val second = cache.getOrBuild(key) {
            buildCount++
            throw AssertionError("cache hit must not rebuild")
        }

        assertSame("Second call should return cached instance", first, second)
        assertEquals("Second lookup should not recompute", 1, buildCount)
    }

    // ---- Test: Whitespace/comment edit → key stable → PSI hashes unchanged ----

    fun testWhitespaceEditDoesNotBustCache() {
        val code = """
            def greet(name):
                return f"Hello, {name}!"
        """.trimIndent()
        val file = configureAndGet(code)
        val service = EnglishModelService.getInstance(project)
        val firstModel = service.getModel(file)
        val firstHashes = firstModel.blocks.map { it.psiHash }

        val commented = """
            # A greeting function
            def greet(name):
                return f"Hello, {name}!"
        """.trimIndent()
        val file2 = configureAndGet(commented)
        val secondModel = service.getModel(file2)
        val secondHashes = secondModel.blocks.map { it.psiHash }

        assertEquals("Comment-only edit should preserve PSI hashes", firstHashes, secondHashes)
    }

    // ---- Test: Semantic edit to one block → that block's hash changes, siblings stable ----

    fun testSemanticEditAffectsChangedBlockOnly() {
        val code = """
            class Greeter:
                def greet(self, name):
                    return "hello"

                def farewell(self):
                    return "bye"
        """.trimIndent()
        val file = configureAndGet(code)
        val service = EnglishModelService.getInstance(project)
        val firstModel = service.getModel(file)

        val greetHash = firstModel.blocks[0].children[0].psiHash
        val farewellHash = firstModel.blocks[0].children[1].psiHash

        val edited = """
            class Greeter:
                def greet(self, first, last):
                    return "hello"

                def farewell(self):
                    return "later"
        """.trimIndent()
        val file2 = configureAndGet(edited)
        val secondModel = service.getModel(file2)

        val newGreetHash = secondModel.blocks[0].children[0].psiHash
        val newFarewellHash = secondModel.blocks[0].children[1].psiHash
        assertTrue("Changed block hash should differ", greetHash != newGreetHash)
        assertEquals("Sibling block hash should be stable", farewellHash, newFarewellHash)
    }

    fun testSemanticEditDropsOnlyChangedBlockLlmBody() {
        val original = detect(
            """
            class Greeter:
                def greet(self, name):
                    return "hello"

                def farewell(self):
                    return "bye"
            """.trimIndent()
        )
        val changed = detect(
            """
            class Greeter:
                def greet(self, first, last):
                    return "hello"

                def farewell(self):
                    return "later"
            """.trimIndent()
        )
        val originalBlocks = flatten(original).associateBy { it.stableId }
        val changedBlocks = flatten(changed).associateBy { it.stableId }
        val greet = originalBlocks.getValue("module/class:Greeter/method:greet")
        val farewell = originalBlocks.getValue("module/class:Greeter/method:farewell")
        val cache = ModelCacheService.getInstance(project)

        cache.writeLlmBody(greet.psiHash, "old greet summary")
        cache.writeLlmBody(farewell.psiHash, "farewell summary")
        cache.markChangedBlocksStale(original, changed)

        assertTrue("Changed block should be stale", cache.isStale(greet.stableId))
        assertFalse("Unchanged sibling should stay fresh", cache.isStale(farewell.stableId))
        assertNull("Changed block LLM body should be invalidated", cache.readLlmBody(greet.psiHash))
        assertEquals("farewell summary", cache.readLlmBody(farewell.psiHash))
    }

    // ---- Test: promptVersion bump → LLM entries invalidated, deterministic retained ----

    fun testPromptVersionBumpClearsLlmEntriesOnly() {
        val model = detect("def compute(x): return x * 2")
        val cache = ModelCacheService.getInstance(project)
        val detKey = cache.keyFor(modelHash(model), Profile.POLYGLOT_LENS, VerbosityLevel.HINTS)
        val llmKey = cache.keyFor(modelHash(model), Profile.INTENT_SUMMARY, VerbosityLevel.HINTS)

        cache.put(detKey, model)
        cache.put(llmKey, model)

        cache.setPromptVersion(2)

        assertTrue("Deterministic entry should be retained", cache.containsKey(detKey))
        assertFalse("LLM entry should be evicted", cache.containsKey(llmKey))
    }

    fun testPluginVersionBumpClearsLlmEntriesOnly() {
        val model = detect("def compute(x): return x * 2")
        val cache = ModelCacheService.getInstance(project)
        val detKey = cache.keyFor(modelHash(model), Profile.POLYGLOT_LENS, VerbosityLevel.HINTS)
        val llmKey = cache.keyFor(modelHash(model), Profile.INTENT_SUMMARY, VerbosityLevel.HINTS)

        cache.put(detKey, model)
        cache.put(llmKey, model)

        cache.setPluginVersion(2)

        assertTrue("Deterministic entry should be retained", cache.containsKey(detKey))
        assertFalse("LLM entry should be evicted", cache.containsKey(llmKey))
    }

    // ---- Test: Eviction - exceeding size cap evicts LRU ----

    fun testEvictionEvictsLruEntries() {
        val cache = ModelCacheService.getInstance(project)

        cache.maxCacheSize = 2
        val first = detect("def a(): pass")
        val second = detect("def b(): pass")
        val third = detect("def c(): pass")
        val firstKey = cache.keyFor(modelHash(first), Profile.POLYGLOT_LENS, VerbosityLevel.HINTS)
        val secondKey = cache.keyFor(modelHash(second), Profile.POLYGLOT_LENS, VerbosityLevel.HINTS)
        val thirdKey = cache.keyFor(modelHash(third), Profile.POLYGLOT_LENS, VerbosityLevel.HINTS)

        cache.put(firstKey, first)
        cache.put(secondKey, second)
        assertSame(first, cache.get(firstKey))
        cache.put(thirdKey, third)

        assertTrue("Recently read entry should remain", cache.containsKey(firstKey))
        assertFalse("Least recently used entry should be evicted", cache.containsKey(secondKey))
        assertTrue("Newest entry should remain", cache.containsKey(thirdKey))

        var rebuilds = 0
        cache.getOrBuild(secondKey) {
            rebuilds++
            second
        }
        assertEquals("Evicted deterministic model should rebuild on demand", 1, rebuilds)
    }

    // ---- Test: LLM body round-trips through DiskStore; nothing under project root ----

    fun testDiskStoreRoundTrip() {
        val tempDir = Files.createTempDirectory("diskstore-test").toFile()
        try {
            val store = DiskStore(tempDir)
            val key = "test-hash-abc123"
            val body = "This is a simulated LLM response body for testing."

            store.write(key, body)
            val readBack = store.read(key)
            assertEquals("Round-trip body should match", body, readBack)

            val storedFile = File(tempDir, "llm-bodies/$key.txt")
            assertTrue("File should exist at expected path", storedFile.exists())

            val projectRoot = File(project.basePath ?: ".")
            val llmDirInProject = File(projectRoot, "llm-bodies")
            assertFalse("Should not write under project root", llmDirInProject.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun testDiskStoreReturnsNullForMissingKey() {
        val tempDir = Files.createTempDirectory("diskstore-test").toFile()
        try {
            val store = DiskStore(tempDir)
            assertNull("Should return null for missing key", store.read("nonexistent"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun testDiskStoreDelete() {
        val tempDir = Files.createTempDirectory("diskstore-test").toFile()
        try {
            val store = DiskStore(tempDir)
            store.write("key1", "body1")
            assertTrue(store.exists("key1"))

            store.delete("key1")
            assertFalse("Should be deleted", store.exists("key1"))
            assertNull("Should return null after delete", store.read("key1"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ---- Test: isStale flips true after simulatePsiChange ----

    fun testIsStaleFlipsAfterPsiChange() {
        val code = """
            def greet(name):
                return f"Hello, {name}!"
        """.trimIndent()
        val file = configureAndGet(code)
        val service = EnglishModelService.getInstance(project)
        val cache = ModelCacheService.getInstance(project)

        val model = service.getModel(file)
        val blockId = model.blocks[0].stableId

        assertFalse("Block should not be stale initially", cache.isStale(blockId))

        cache.simulatePsiChange(model.fileId)

        assertTrue("Block should be stale after PSI change", cache.isStale(blockId))

        cache.markBlockFresh(blockId)
        assertFalse("Block should be fresh after regeneration", cache.isStale(blockId))
    }

    // ---- Test: Multiple profiles produce separate cache entries ----

    fun testDifferentProfilesProduceSeparateCacheEntries() {
        val code = """
            def process(items):
                return [x * 2 for x in items]
        """.trimIndent()
        val file = configureAndGet(code)
        val service = EnglishModelService.getInstance(project)
        val cache = ModelCacheService.getInstance(project)

        service.getModel(file, Profile.POLYGLOT_LENS, VerbosityLevel.HINTS)
        service.getModel(file, Profile.POLYGLOT_LENS, VerbosityLevel.READER)
        service.getModel(file, Profile.INTENT_SUMMARY, VerbosityLevel.HINTS)

        assertTrue("Should have multiple entries for different profile/level combos", cache.cacheSize() >= 2)
    }

    // ---- Test: clearCache empties everything ----

    fun testClearCacheRemovesAllEntries() {
        val code = "def f(): pass"
        val file = configureAndGet(code)
        val service = EnglishModelService.getInstance(project)
        val cache = ModelCacheService.getInstance(project)

        service.getModel(file)
        assertTrue(cache.cacheSize() > 0)

        cache.clearCache()
        assertEquals("Cache should be empty after clear", 0, cache.cacheSize())
    }

    // ---- Test: Multiple blocks with independent staleness ----

    fun testMultipleBlocksIndependentStaleness() {
        val code = """
            def alpha():
                return 1

            def beta():
                return 2
        """.trimIndent()
        val file = configureAndGet(code)
        val service = EnglishModelService.getInstance(project)
        val cache = ModelCacheService.getInstance(project)

        val model = service.getModel(file)
        val alphaId = model.blocks[0].stableId
        val betaId = model.blocks[1].stableId

        cache.markBlockStale(alphaId)

        assertTrue("alpha should be stale", cache.isStale(alphaId))
        assertFalse("beta should not be stale", cache.isStale(betaId))
    }
}
