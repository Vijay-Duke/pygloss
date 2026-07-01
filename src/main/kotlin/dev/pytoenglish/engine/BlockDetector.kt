package dev.pytoenglish.engine

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyComprehensionElement
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyForStatement
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyIfStatement
import com.jetbrains.python.psi.PyRecursiveElementVisitor
import com.jetbrains.python.psi.PyTryExceptStatement
import com.jetbrains.python.psi.PyWhileStatement
import com.jetbrains.python.psi.PyWithStatement
import dev.pytoenglish.model.BlockKind
import dev.pytoenglish.model.Concept
import dev.pytoenglish.model.EnglishBlock
import dev.pytoenglish.model.EnglishModel

/** Builds a deterministic EnglishModel tree from Python PSI blocks. */
object BlockDetector {

    /** Maximum semantic blocks produced for one file. */
    const val MAX_BLOCKS: Int = 2000

    /** Detect semantic blocks in a Python file without modifying the document. */
    fun detect(file: PyFile): EnglishModel {
        val context = DetectionContext()
        val blocks = collectChildBlocks(file, context)
        return EnglishModel(
            fileId = file.virtualFile?.path ?: file.name,
            blocks = blocks,
            totalBlockCount = context.blockCount
        )
    }

    private fun collectChildBlocks(parent: PsiElement, context: DetectionContext): List<EnglishBlock> {
        val blocks = mutableListOf<EnglishBlock>()
        var child = parent.firstChild
        while (child != null && context.canAddBlock()) {
            val block = createBlock(child, context)
            if (block != null) {
                blocks.add(block)
            } else if (shouldDescend(child)) {
                blocks.addAll(collectChildBlocks(child, context))
            }
            child = child.nextSibling
        }
        return blocks
    }

    private fun createBlock(element: PsiElement, context: DetectionContext): EnglishBlock? {
        if (!context.canAddBlock()) return null
        return when (element) {
            is PyClass -> createClassBlock(element, context)
            is PyFunction -> createFunctionBlock(element, context)
            is PyIfStatement -> createIfBlock(element, context)
            is PyForStatement -> createForBlock(element, context)
            is PyWhileStatement -> createWhileBlock(element, context)
            is PyWithStatement -> createWithBlock(element, context)
            is PyTryExceptStatement -> createTryBlock(element, context)
            is PyComprehensionElement -> createComprehensionBlock(element, context)
            else -> null
        }
    }

    private fun createClassBlock(pyClass: PyClass, context: DetectionContext): EnglishBlock {
        val name = pyClass.name.orAnonymous()
        val segment = "class:$name"
        return context.withBlock(segment) {
            val children = collectChildBlocks(pyClass, context)
            val skeleton = listOfNotNull(
                "name=$name",
                decoratorsSkeleton(pyClass).takeIf { it.isNotEmpty() }?.let { "decorators=$it" },
                classBases(pyClass).takeIf { it.isNotEmpty() }?.let { "bases=${it.joinToString(",")}" },
                children.kindSkeleton()
            ).joinToString("|")
            buildBlock(context.currentId(), BlockKind.CLASS, pyClass, skeleton, classConcepts(pyClass), children)
        }
    }

    private fun createFunctionBlock(function: PyFunction, context: DetectionContext): EnglishBlock {
        val name = function.name.orAnonymous()
        val isMethod = function.containingClass != null
        val segment = "${if (isMethod) "method" else "function"}:$name"
        return context.withBlock(segment) {
            val children = collectChildBlocks(function, context)
            val params = parameters(function)
            val decorators = decoratorsSkeleton(function)
            val facts = mutableListOf(
                "name=$name",
                "params=${params.joinToString(",")}",
                "async=${function.isAsyncFunction()}"
            )
            if (decorators.isNotEmpty()) facts.add("decorators=$decorators")
            facts.add(children.kindSkeleton())
            val skeleton = facts.joinToString("|")
            buildBlock(context.currentId(), BlockKind.FUNCTION, function, skeleton, functionConcepts(function), children)
        }
    }

    private fun createIfBlock(statement: PyIfStatement, context: DetectionContext): EnglishBlock {
        val segment = "if@${context.nextIndex("if")}"
        return context.withBlock(segment) {
            val children = collectChildBlocks(statement, context)
            val skeleton = listOf(
                "shape=if",
                "elif=${statement.elifParts.size}",
                "else=${statement.elsePart != null}",
                "conditions=${conditionShape(statement)}",
                children.kindSkeleton()
            ).joinToString("|")
            buildBlock(context.currentId(), BlockKind.IF, statement, skeleton, bodyConcepts(statement), children)
        }
    }

    private fun createForBlock(statement: PyForStatement, context: DetectionContext): EnglishBlock {
        val segment = "for@${context.nextIndex("for")}"
        return context.withBlock(segment) {
            val children = collectChildBlocks(statement, context)
            val skeleton = listOf(
                "shape=for",
                "header=${headerShape(statement, ":")}",
                children.kindSkeleton()
            ).joinToString("|")
            buildBlock(context.currentId(), BlockKind.FOR, statement, skeleton, bodyConcepts(statement), children)
        }
    }

    private fun createWhileBlock(statement: PyWhileStatement, context: DetectionContext): EnglishBlock {
        val segment = "while@${context.nextIndex("while")}"
        return context.withBlock(segment) {
            val children = collectChildBlocks(statement, context)
            val skeleton = listOf(
                "shape=while",
                "condition=${headerShape(statement, ":")}",
                children.kindSkeleton()
            ).joinToString("|")
            buildBlock(context.currentId(), BlockKind.WHILE, statement, skeleton, bodyConcepts(statement), children)
        }
    }

    private fun createWithBlock(statement: PyWithStatement, context: DetectionContext): EnglishBlock {
        val segment = "with@${context.nextIndex("with")}"
        return context.withBlock(segment) {
            val children = collectChildBlocks(statement, context)
            val skeleton = listOf(
                "shape=with",
                "items=${headerShape(statement, ":")}",
                children.kindSkeleton()
            ).joinToString("|")
            buildBlock(context.currentId(), BlockKind.WITH, statement, skeleton, bodyConcepts(statement) + Concept.WITH, children)
        }
    }

    private fun createTryBlock(statement: PyTryExceptStatement, context: DetectionContext): EnglishBlock {
        val segment = "try@${context.nextIndex("try")}"
        return context.withBlock(segment) {
            val children = collectChildBlocks(statement, context)
            val skeleton = listOf(
                "shape=try",
                "except=${statement.exceptParts.size}",
                "else=${statement.text.lineSequence().any { it.trimStart().startsWith("else:") }}",
                "finally=${statement.text.lineSequence().any { it.trimStart().startsWith("finally:") }}",
                children.kindSkeleton()
            ).joinToString("|")
            buildBlock(context.currentId(), BlockKind.TRY, statement, skeleton, bodyConcepts(statement), children)
        }
    }

    private fun createComprehensionBlock(element: PyComprehensionElement, context: DetectionContext): EnglishBlock {
        val segment = "comprehension@${context.nextIndex("comprehension")}"
        return context.withBlock(segment) {
            val skeleton = listOf(
                "shape=comprehension",
                "async=${element.text.contains(Regex("""\basync\b"""))}",
                "filters=${Regex("""\bif\b""").findAll(element.text).count()}"
            ).joinToString("|")
            val concepts = mutableSetOf(Concept.COMPREHENSION)
            if (element.text.contains(Regex("""\basync\b"""))) concepts.add(Concept.ASYNC)
            buildBlock(context.currentId(), BlockKind.COMPREHENSION, element, skeleton, concepts, emptyList())
        }
    }

    private fun buildBlock(
        stableId: String,
        kind: BlockKind,
        element: PsiElement,
        skeleton: String,
        concepts: Set<Concept>,
        children: List<EnglishBlock>
    ): EnglishBlock {
        val block = EnglishBlock(
            stableId = stableId,
            kind = kind,
            textRange = element.textRange,
            anchorOffset = element.textRange.startOffset,
            skeleton = skeleton,
            concepts = concepts.toSortedSet(compareBy { it.name }),
            children = children,
            psiHash = ""
        )
        return block.copy(psiHash = PsiHash.hash(block, element))
    }

    private fun shouldDescend(element: PsiElement): Boolean {
        return element !is PsiWhiteSpace && element !is PsiComment
    }

    private fun classConcepts(pyClass: PyClass): Set<Concept> {
        return buildSet {
            if (decoratorsSkeleton(pyClass).isNotEmpty()) add(Concept.DECORATOR)
            addAll(bodyConcepts(pyClass))
        }
    }

    private fun functionConcepts(function: PyFunction): Set<Concept> {
        return buildSet {
            val name = function.name.orEmpty()
            val params = parameters(function)
            if (function.isAsyncFunction()) add(Concept.ASYNC)
            if (name.isDunderName()) add(Concept.DUNDER)
            if (decoratorsSkeleton(function).isNotEmpty()) add(Concept.DECORATOR)
            if (params.firstOrNull() == "self") add(Concept.SELF)
            if (params.any { it.startsWith("*") && !it.startsWith("**") && it != "*" }) add(Concept.ARGS)
            if (params.any { it.startsWith("**") }) add(Concept.KWARGS)
            addAll(bodyConcepts(function))
        }
    }

    private fun bodyConcepts(element: PsiElement): Set<Concept> {
        return buildSet {
            val text = textWithoutComments(element)
            if (text.contains(Regex("""\bawait\b"""))) add(Concept.AWAIT)
            if (text.contains(Regex("""\byield\b"""))) {
                add(Concept.YIELD)
                add(Concept.GENERATOR)
            }
            if (text.contains(":=")) add(Concept.WALRUS)
            element.accept(object : PyRecursiveElementVisitor() {
                override fun visitPyWithStatement(node: PyWithStatement) {
                    add(Concept.WITH)
                    super.visitPyWithStatement(node)
                }

                override fun visitPyComprehensionElement(node: PyComprehensionElement) {
                    add(Concept.COMPREHENSION)
                    super.visitPyComprehensionElement(node)
                }
            })
        }
    }

    private fun textWithoutComments(element: PsiElement): String {
        val builder = StringBuilder()
        fun appendText(node: PsiElement) {
            if (node is PsiComment) return
            val firstChild = node.firstChild
            if (firstChild == null) {
                builder.append(node.text).append(' ')
                return
            }
            var child: PsiElement? = firstChild
            while (child != null) {
                appendText(child)
                child = child.nextSibling
            }
        }
        appendText(element)
        return builder.toString()
    }

    private fun parameters(function: PyFunction): List<String> {
        return function.parameterList.parameters.map { parameter ->
            val text = parameter.text.trim()
            val name = parameter.name
            if (text == "*" && name == null) return@map "*"
            val starPrefix = when {
                text.startsWith("**") -> "**"
                text.startsWith("*") -> "*"
                else -> ""
            }
            starPrefix + name.orAnonymous()
        }
    }

    private fun decoratorsSkeleton(element: PsiElement): String {
        val decorators = when (element) {
            is PyClass -> element.decoratorList?.decorators
            is PyFunction -> element.decoratorList?.decorators
            else -> null
        }.orEmpty()
        return decorators.map { decorator ->
            normalizeExpressionShape(decorator.text.removePrefix("@"))
        }.joinToString(",")
    }

    private fun classBases(pyClass: PyClass): List<String> {
        return pyClass.superClassExpressions.map { normalizeExpressionShape(it.text) }
    }

    private fun conditionShape(statement: PyIfStatement): String {
        val lines = statement.text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("if ") || it.startsWith("elif ") }
            .map { normalizeExpressionShape(it.substringBefore(":")) }
            .toList()
        return lines.joinToString(",")
    }

    private fun headerShape(element: PsiElement, delimiter: String): String {
        return normalizeExpressionShape(element.text.lineSequence().firstOrNull().orEmpty().substringBefore(delimiter))
    }

    private fun normalizeExpressionShape(text: String): String {
        return text
            .replace(Regex("'''[\\s\\S]*?'''|\"\"\"[\\s\\S]*?\"\"\"|'(?:\\\\.|[^'])*'|\"(?:\\\\.|[^\"])*\""), "<literal>")
            .replace(Regex("""\b\d+(?:\.\d+)?\b"""), "<literal>")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun PyFunction.isAsyncFunction(): Boolean {
        return text.lineSequence().any { it.trimStart().startsWith("async def ") }
    }

    private fun String?.orAnonymous(): String {
        return this?.takeIf { it.isNotBlank() } ?: "<anonymous>"
    }

    private fun String.isDunderName(): Boolean {
        return startsWith("__") && endsWith("__") && length > 4
    }

    private fun List<EnglishBlock>.kindSkeleton(): String {
        return "children=${joinToString(",") { it.kind.name.lowercase() }}"
    }

    private class DetectionContext {
        private val path = mutableListOf("module")
        private val childCounters = mutableMapOf<String, Int>()
        var blockCount: Int = 0
            private set

        fun canAddBlock(): Boolean = blockCount < MAX_BLOCKS

        fun currentId(): String = path.joinToString("/")

        fun nextIndex(kind: String): Int {
            val key = (path + kind).joinToString("/")
            val index = childCounters.getOrDefault(key, 0)
            childCounters[key] = index + 1
            return index
        }

        fun <T> withBlock(segment: String, build: () -> T): T {
            blockCount++
            path.add(segment)
            try {
                return build()
            } finally {
                path.removeAt(path.lastIndex)
            }
        }
    }
}
