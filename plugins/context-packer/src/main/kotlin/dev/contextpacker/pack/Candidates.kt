package dev.contextpacker.pack

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile

/** Project files worth scoring: source in any language, minus docs, config, generated and huge files. */
object Candidates {
    const val MAX_BYTES = 100_000L
    private val SKIP_EXTENSIONS = setOf(
        "md", "txt", "json", "yml", "yaml", "toml", "properties", "xml", "html", "css", "csv", "lock", "svg", "sql",
    )

    /**
     * Optional allowlist of extensions, e.g. "kt". The eval scored Kotlin files only, so setting this to kt makes
     * the plugin rank exactly the candidate set the published numbers were measured on.
     */
    private val ONLY: Set<String>? = System.getenv("CONTEXT_PACKER_EXTENSIONS")
        ?.split(',')?.map { it.trim().lowercase().removePrefix(".") }?.filter { it.isNotEmpty() }?.toSet()?.ifEmpty { null }

    /** Call inside a read action. */
    fun collect(project: Project): List<VirtualFile> {
        val index = ProjectFileIndex.getInstance(project)
        val files = ArrayList<VirtualFile>()
        index.iterateContent { file ->
            if (!file.isDirectory && isSource(file) && !index.isExcluded(file) && !index.isInLibrary(file) &&
                !index.isUnderIgnored(file) && !index.isInGeneratedSources(file)
            ) files += file
            true
        }
        return files
    }

    private fun isSource(file: VirtualFile): Boolean {
        val type = file.fileType
        val ext = file.extension?.lowercase()
        return !type.isBinary && type is LanguageFileType && file.length <= MAX_BYTES &&
            ext !in SKIP_EXTENSIONS && (ONLY == null || ext in ONLY)
    }
}
