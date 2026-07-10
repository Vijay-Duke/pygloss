package dev.pygloss.model

import com.intellij.openapi.util.TextRange

/** A semantic block in a Python file with deterministic metadata. */
data class EnglishBlock(
    /** Structural path ID, e.g. "module/class:Foo/method:bar/if@0". */
    val stableId: String,
    /** Kind of this block. */
    val kind: BlockKind,
    /** Text range in the source file. */
    val textRange: TextRange,
    /** Anchor offset for caret sync (start of the block). */
    val anchorOffset: Int,
    /** Deterministic facts string (name, params, decorators, control shape). */
    val skeleton: String,
    /** Detected Python idioms. */
    val concepts: Set<Concept>,
    /** Nested child blocks. */
    val children: List<EnglishBlock>,
    /** Normalized PSI hash (SHA-256 hex). */
    val psiHash: String,
    /** Polyglot Lens analogy placeholder (filled later in U3). */
    val analogy: String? = null,
    /** Intent Summary placeholder (filled later in U8). */
    val summary: String? = null
)
