package dev.pytoenglish.engine

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.psi.PyFile
import dev.pytoenglish.model.BlockKind
import dev.pytoenglish.model.Concept
import dev.pytoenglish.model.EnglishBlock
import dev.pytoenglish.model.EnglishModel

class BlockDetectorTest : BasePlatformTestCase() {

    fun testClassMethodsModuleForAndComprehensionProduceExpectedTree() {
        val model = detect(
            """
            class Greeter:
                def greet(self):
                    pass

                def farewell(self):
                    pass

            for x in range(10):
                pass

            result = [x * 2 for x in items if x > 0]
            """.trimIndent()
        )

        assertEquals(
            listOf(BlockKind.CLASS, BlockKind.FOR, BlockKind.COMPREHENSION),
            model.blocks.map { it.kind }
        )
        assertEquals(
            listOf("module/class:Greeter", "module/for@0", "module/comprehension@0"),
            model.blocks.map { it.stableId }
        )

        val classBlock = model.blocks[0]
        assertEquals(
            listOf(BlockKind.FUNCTION, BlockKind.FUNCTION),
            classBlock.children.map { it.kind }
        )
        assertEquals(
            listOf("module/class:Greeter/method:greet", "module/class:Greeter/method:farewell"),
            classBlock.children.map { it.stableId }
        )
        assertTrue(classBlock.children.all { it.children.isEmpty() })
        assertEquals(5, model.totalBlockCount)
    }

    fun testReformattingPreservesIdsAndHashes() {
        val compact = detect(
            """
            class Foo:
                def bar(self):
                    x = 1
                    return x
            """.trimIndent()
        )
        val reformatted = detect(
            """
            # module comment

            class Foo:

                def bar(self):
                    # body comment
                    x = 1      # inline comment
                    return x
            """.trimIndent()
        )

        assertEquals(flatten(compact).map { it.stableId }, flatten(reformatted).map { it.stableId })
        assertEquals(flatten(compact).map { it.psiHash }, flatten(reformatted).map { it.psiHash })
    }

    fun testSemanticChangeAffectsChangedBlockNotSibling() {
        val original = detect(
            """
            class Foo:
                def bar(self, x):
                    if x:
                        return x

                def baz(self):
                    return 42
            """.trimIndent()
        )
        val changed = detect(
            """
            class Foo:
                def bar(self, renamed):
                    if renamed:
                        return renamed
                    else:
                        return None

                def baz(self):
                    return 99
            """.trimIndent()
        )

        val originalById = flatten(original).associateBy { it.stableId }
        val changedById = flatten(changed).associateBy { it.stableId }

        assertDifferentHash(originalById.getValue("module/class:Foo/method:bar"), changedById.getValue("module/class:Foo/method:bar"))
        assertDifferentHash(originalById.getValue("module/class:Foo/method:bar/if@0"), changedById.getValue("module/class:Foo/method:bar/if@0"))
        assertEquals(
            "Literal-only sibling body edits must not bust the hash",
            originalById.getValue("module/class:Foo/method:baz").psiHash,
            changedById.getValue("module/class:Foo/method:baz").psiHash
        )
    }

    fun testConceptDetection() {
        val model = detect(
            """
            def plain(x):
                return x

            def keyword_only(*, value):
                return value

            @decorator
            async def decorated(self, *args, **kwargs):
                with open("path") as handle:
                    if (line := handle.readline()):
                        yield [x for x in args if x]
                    await later()

            class Sized:
                def __len__(self):
                    return 0
            """.trimIndent()
        )
        val byId = flatten(model).associateBy { it.stableId }

        assertTrue(byId.getValue("module/function:plain").concepts.isEmpty())
        assertTrue(byId.getValue("module/function:keyword_only").concepts.isEmpty())

        val decorated = byId.getValue("module/function:decorated")
        assertContainsAll(
            decorated.concepts,
            Concept.ASYNC,
            Concept.AWAIT,
            Concept.WITH,
            Concept.YIELD,
            Concept.GENERATOR,
            Concept.SELF,
            Concept.ARGS,
            Concept.KWARGS,
            Concept.DECORATOR,
            Concept.WALRUS,
            Concept.COMPREHENSION
        )

        assertContainsAll(byId.getValue("module/function:decorated/with@0").concepts, Concept.WITH, Concept.WALRUS, Concept.COMPREHENSION)
        val comprehension = flatten(model).first { it.kind == BlockKind.COMPREHENSION }
        assertTrue(comprehension.stableId.startsWith("module/function:decorated/with@0/if@0/"))
        assertContainsAll(comprehension.concepts, Concept.COMPREHENSION)
        assertContainsAll(byId.getValue("module/class:Sized/method:__len__").concepts, Concept.SELF, Concept.DUNDER)
    }

    fun testEmptyFileProducesEmptyModel() {
        val model = detect("")

        assertTrue(model.blocks.isEmpty())
        assertEquals(0, model.totalBlockCount)
    }

    fun testIncompleteFileProducesPartialModelWithoutException() {
        val model = detect(
            """
            def complete():
                return 1

            def incomplete(
            """.trimIndent()
        )

        assertTrue(flatten(model).any { it.stableId == "module/function:complete" })
    }

    fun testBlockCapEnforcedForLargeFile() {
        val code = buildString {
            repeat(2500) { index ->
                appendLine("def func_$index():")
                appendLine("    pass")
                appendLine()
            }
        }

        val model = detect(code)

        assertTrue(model.totalBlockCount <= BlockDetector.MAX_BLOCKS)
        assertEquals(BlockDetector.MAX_BLOCKS, flatten(model).size)
    }

    private fun detect(code: String): EnglishModel {
        val file = myFixture.configureByText("sample.py", code) as PyFile
        return BlockDetector.detect(file)
    }

    private fun flatten(model: EnglishModel): List<EnglishBlock> {
        val result = mutableListOf<EnglishBlock>()
        fun collect(blocks: List<EnglishBlock>) {
            blocks.forEach { block ->
                result.add(block)
                collect(block.children)
            }
        }
        collect(model.blocks)
        return result
    }

    private fun assertDifferentHash(first: EnglishBlock, second: EnglishBlock) {
        assertTrue("Expected different hashes for ${first.stableId}", first.psiHash != second.psiHash)
    }

    private fun assertContainsAll(concepts: Set<Concept>, vararg expected: Concept) {
        expected.forEach { concept ->
            assertTrue("Expected concept $concept in $concepts", concept in concepts)
        }
    }
}
