package dev.pygloss.model

/** Per-file deterministic model of semantic blocks. */
data class EnglishModel(
    /** Unique file identifier (path-based). */
    val fileId: String,
    /** Top-level blocks in order. */
    val blocks: List<EnglishBlock>,
    /** Total block count across the entire tree (for cap enforcement). */
    val totalBlockCount: Int
)
