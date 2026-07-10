package dev.pygloss.cache

import com.intellij.openapi.application.PathManager
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets

/**
 * Persists LLM response bodies to the plugin system directory, keyed by content hash.
 * Never writes under the project/VCS root (KTD5).
 */
class DiskStore(private val baseDir: File) {

    /** Write a text body under the given key. */
    fun write(key: String, body: String) {
        val file = fileFor(key)
        file.parentFile?.mkdirs()
        file.writeText(body, StandardCharsets.UTF_8)
    }

    /** Read a previously written body, or null if not found. */
    fun read(key: String): String? {
        return try {
            fileFor(key).readText(StandardCharsets.UTF_8)
        } catch (_: FileNotFoundException) {
            null
        }
    }

    /** Delete a cached body by key. */
    fun delete(key: String) {
        fileFor(key).delete()
    }

    /** Whether a body exists for the given key. */
    fun exists(key: String): Boolean = fileFor(key).exists()

    private fun fileFor(key: String): File {
        val safeKey = key.replace("/", "_").replace("\\", "_")
        return File(baseDir, "llm-bodies/$safeKey.txt")
    }

    companion object {
        /** Create a DiskStore rooted at the plugin system directory. */
        fun pluginDir(): DiskStore {
            val systemDir = PathManager.getSystemPath()
            return DiskStore(File(systemDir, "pygloss"))
        }
    }
}
