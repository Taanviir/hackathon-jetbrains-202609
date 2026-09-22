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
        return !type.isBinary && type is LanguageFileType && file.length <= MAX_BYTES &&
            file.extension?.lowercase() !in SKIP_EXTENSIONS
    }
}
