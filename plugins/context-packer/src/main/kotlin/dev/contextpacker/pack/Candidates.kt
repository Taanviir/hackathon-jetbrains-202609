package dev.contextpacker.pack

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VfsUtilCore
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
        val base = project.guessProjectDir() ?: return emptyList()
        val index = ProjectFileIndex.getInstance(project)
        val files = ArrayList<VirtualFile>()
        index.iterateContent { file ->
            if (!file.isDirectory && isInsideProjectWithoutLinks(base, file) && isSource(file) &&
                !index.isExcluded(file) && !index.isInLibrary(file) &&
                !index.isUnderIgnored(file) && !index.isInGeneratedSources(file)
            ) files += file
            true
        }
        return files
    }

    /** The VFS ancestry check alone accepts links whose contents live outside the project. */
    internal fun isInsideProjectWithoutLinks(base: VirtualFile, file: VirtualFile): Boolean {
        if (!VfsUtilCore.isAncestor(base, file, true)) return false
        var current: VirtualFile? = file
        while (current != null && current != base) {
            if (current.`is`(VFileProperty.SYMLINK)) return false
            current = current.parent
        }
        if (current != base) return false
        // Also catch a local filesystem alias (such as a junction) if VFS did not mark it as a symlink.
        val resolved = file.canonicalFile ?: return true
        val resolvedBase = base.canonicalFile ?: base
        return VfsUtilCore.isAncestor(resolvedBase, resolved, true)
    }

    /** Keep unsaved editor text under the same numeric cap without creating documents during a scan. */
    internal fun fitsSizeLimit(file: VirtualFile): Boolean =
        file.length <= MAX_BYTES &&
            (FileDocumentManager.getInstance().getCachedDocument(file)?.textLength?.toLong() ?: 0L) <= MAX_BYTES

    private fun isSource(file: VirtualFile): Boolean {
        val type = file.fileType
        val ext = file.extension?.lowercase()
        return !type.isBinary && type is LanguageFileType && fitsSizeLimit(file) &&
            ext !in SKIP_EXTENSIONS && (ONLY == null || ext in ONLY)
    }
}
