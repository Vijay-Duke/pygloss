package dev.pytoenglish.engine

import dev.pytoenglish.model.Concept

/** Confidence tier for Polyglot Lens analogy. */
enum class ConfidenceTier {
    /** Direct mapping with minimal loss. */
    HIGH,
    /** Good fit with minor caveats. */
    MEDIUM,
    /** Rough approximation; significant differences exist. */
    LOW
}

/** A single Polyglot Lens entry for a concept-language pair. */
data class ConceptEntry(
    /** Closest analogy in the target language. */
    val closestAnalogy: String,
    /** Confidence in this analogy. */
    val confidenceTier: ConfidenceTier,
    /** One-line lossy caveat. */
    val caveat: String
)

/**
 * Deterministic concept table mapping each [Concept] to JS, Java, Go, and C# entries.
 */
object ConceptTable {

    private val table: Map<Concept, Map<String, ConceptEntry>> = buildTable()

    /** Look up an entry for a concept-language pair. */
    fun lookup(concept: Concept, language: String): ConceptEntry? {
        return table[concept]?.get(language)
    }

    /** Return all entries as a flat map keyed by concept. */
    fun allEntries(): Map<Concept, Map<String, ConceptEntry>> = table

    private fun buildTable(): Map<Concept, Map<String, ConceptEntry>> {
        val langs = listOf("JS", "Java", "Go", "C#")
        return Concept.entries.associateWith { concept ->
            langs.associateWith { lang -> entryFor(concept, lang) }
        }
    }

    private fun entryFor(concept: Concept, lang: String): ConceptEntry {
        return when (concept) {
            Concept.ASYNC -> when (lang) {
                "JS" -> ConceptEntry("async/await", ConfidenceTier.HIGH, "JS async is single-threaded event loop; Python uses OS threads too")
                "Java" -> ConceptEntry("CompletableFuture", ConfidenceTier.MEDIUM, "Java futures are eager; Python coroutines are lazy")
                "Go" -> ConceptEntry("goroutine + channel", ConfidenceTier.MEDIUM, "Go concurrency is CSP-based; Python async is cooperative")
                "C#" -> ConceptEntry("async/await", ConfidenceTier.HIGH, "C# async uses Task; Python uses coroutine with asyncio")
                else -> error("Unsupported language: $lang")
            }
            Concept.AWAIT -> when (lang) {
                "JS" -> ConceptEntry("await", ConfidenceTier.HIGH, "Both suspend execution; JS runs on microtask queue")
                "Java" -> ConceptEntry(".join() or .get()", ConfidenceTier.LOW, "Java blocks the thread; Python suspends coroutine")
                "Go" -> ConceptEntry("<-channel receive", ConfidenceTier.LOW, "Go channels are synchronous; Python await is cooperative")
                "C#" -> ConceptEntry("await", ConfidenceTier.HIGH, "Both suspend; C# captures SynchronizationContext")
                else -> error("Unsupported language: $lang")
            }
            Concept.WITH -> when (lang) {
                "JS" -> ConceptEntry("try/finally", ConfidenceTier.LOW, "JavaScript needs manual cleanup for this pattern")
                "Java" -> ConceptEntry("try-with-resources", ConfidenceTier.HIGH, "Closest analogy: AutoCloseable pattern")
                "Go" -> ConceptEntry("defer", ConfidenceTier.HIGH, "Closest analogy: deferred cleanup calls")
                "C#" -> ConceptEntry("using statement", ConfidenceTier.HIGH, "Closest analogy: IDisposable pattern")
                else -> error("Unsupported language: $lang")
            }
            Concept.YIELD -> when (lang) {
                "JS" -> ConceptEntry("function*", ConfidenceTier.HIGH, "Both are generator functions; JS uses iterator protocol")
                "Java" -> ConceptEntry("Stream.generate", ConfidenceTier.LOW, "No direct yield; streams are pull-based")
                "Go" -> ConceptEntry("channel-based iterator", ConfidenceTier.LOW, "No native yield; uses goroutine + channel")
                "C#" -> ConceptEntry("yield return", ConfidenceTier.HIGH, "Both create lazy iterators; C# uses IEnumerable")
                else -> error("Unsupported language: $lang")
            }
            Concept.GENERATOR -> when (lang) {
                "JS" -> ConceptEntry("Generator function", ConfidenceTier.HIGH, "Both produce lazy iterators via iterator protocol")
                "Java" -> ConceptEntry("Iterator/Stream", ConfidenceTier.MEDIUM, "Java uses explicit Iterator; Python generators are implicit")
                "Go" -> ConceptEntry("func-based iterator", ConfidenceTier.LOW, "No native generators; requires manual state")
                "C#" -> ConceptEntry("IEnumerable with yield", ConfidenceTier.HIGH, "Both produce lazy sequences")
                else -> error("Unsupported language: $lang")
            }
            Concept.SELF -> when (lang) {
                "JS" -> ConceptEntry("this", ConfidenceTier.HIGH, "JS this is dynamic; Python self is explicit parameter")
                "Java" -> ConceptEntry("this", ConfidenceTier.HIGH, "Both refer to current instance; Java this is implicit")
                "Go" -> ConceptEntry("receiver variable", ConfidenceTier.HIGH, "Go methods have explicit receiver; similar to self")
                "C#" -> ConceptEntry("this", ConfidenceTier.HIGH, "Both refer to current instance; C# this is implicit")
                else -> error("Unsupported language: $lang")
            }
            Concept.ARGS -> when (lang) {
                "JS" -> ConceptEntry("...args rest params", ConfidenceTier.HIGH, "Both collect extra positional args; JS uses spread syntax")
                "Java" -> ConceptEntry("varargs (T...)", ConfidenceTier.HIGH, "Both collect variable args; Java uses array internally")
                "Go" -> ConceptEntry("variadic (...T)", ConfidenceTier.HIGH, "Both collect extra args; Go slices them")
                "C#" -> ConceptEntry("params T[]", ConfidenceTier.HIGH, "Both collect variable args; C# uses array")
                else -> error("Unsupported language: $lang")
            }
            Concept.KWARGS -> when (lang) {
                "JS" -> ConceptEntry("destructured object param", ConfidenceTier.MEDIUM, "No direct keyword args; uses object destructuring")
                "Java" -> ConceptEntry("Map parameter", ConfidenceTier.LOW, "No keyword args; requires manual Map handling")
                "Go" -> ConceptEntry("struct options pattern", ConfidenceTier.LOW, "No keyword args; uses functional options or struct")
                "C#" -> ConceptEntry("optional/named params", ConfidenceTier.MEDIUM, "C# named params do not capture arbitrary keywords")
                else -> error("Unsupported language: $lang")
            }
            Concept.DECORATOR -> when (lang) {
                "JS" -> ConceptEntry("decorator syntax (@)", ConfidenceTier.HIGH, "Both use @ syntax; JS decorators are stage-3 proposal")
                "Java" -> ConceptEntry("annotations", ConfidenceTier.MEDIUM, "Annotations are metadata-only; Python decorators wrap functions")
                "Go" -> ConceptEntry("middleware pattern", ConfidenceTier.LOW, "No decorator syntax; uses function wrapping")
                "C#" -> ConceptEntry("attributes", ConfidenceTier.MEDIUM, "Attributes are metadata-only; Python decorators transform")
                else -> error("Unsupported language: $lang")
            }
            Concept.WALRUS -> when (lang) {
                "JS" -> ConceptEntry("assignment in condition", ConfidenceTier.LOW, "No walrus operator; requires separate assignment")
                "Java" -> ConceptEntry("assignment in condition", ConfidenceTier.LOW, "No walrus operator; requires separate statement")
                "Go" -> ConceptEntry("if init statement", ConfidenceTier.HIGH, "Go if supports init; similar scope to walrus")
                "C#" -> ConceptEntry("assignment in condition", ConfidenceTier.LOW, "No walrus operator; requires separate statement")
                else -> error("Unsupported language: $lang")
            }
            Concept.DUNDER -> when (lang) {
                "JS" -> ConceptEntry("Symbol methods", ConfidenceTier.MEDIUM, "JS uses Symbols for protocol methods; less pervasive")
                "Java" -> ConceptEntry("Object methods (toString, equals)", ConfidenceTier.HIGH, "Both override core behavior; Java uses annotations")
                "Go" -> ConceptEntry("interface satisfaction", ConfidenceTier.MEDIUM, "Go uses implicit interfaces; no special naming")
                "C#" -> ConceptEntry("operator overloads + ToString", ConfidenceTier.HIGH, "C# has explicit operator overloading and ToString")
                else -> error("Unsupported language: $lang")
            }
            Concept.COMPREHENSION -> when (lang) {
                "JS" -> ConceptEntry("Array.map/filter", ConfidenceTier.HIGH, "Both transform/filter; JS chains methods")
                "Java" -> ConceptEntry("Stream.map/filter/collect", ConfidenceTier.HIGH, "Both functional pipelines; Java streams are lazy")
                "Go" -> ConceptEntry("for loop + append", ConfidenceTier.LOW, "No comprehension syntax; manual iteration required")
                "C#" -> ConceptEntry("LINQ query or method syntax", ConfidenceTier.HIGH, "Both functional; C# LINQ is integrated query language")
                else -> error("Unsupported language: $lang")
            }
        }
    }
}
