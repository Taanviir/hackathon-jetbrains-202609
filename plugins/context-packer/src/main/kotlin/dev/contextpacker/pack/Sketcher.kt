package dev.contextpacker.pack

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * A ~300-token summary of a file: its path, package, and declarations two levels deep with the
 * first line of each doc comment. Built from the IDE's Structure View, so it works for any
 * language the IDE understands without per-language code.
 */
object Sketcher {
    const val MAX_CHARS = 1_200
    private const val MAX_DEPTH = 2

    /** Call inside a read action. */
    fun sketch(file: PsiFile, path: String, text: String): String {
        val lines = mutableListOf("path: $path")
        text.lineSequence().firstOrNull { it.startsWith("package ") }?.let { lines += it.trim() }
        val structure = structureLines(file)
        if (structure.isNullOrEmpty()) return RegexSketcher.sketch(path, text)
        lines += structure
        return lines.joinToString("\n").take(MAX_CHARS)
    }

    private fun structureLines(file: PsiFile): List<String>? {
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file)
            as? TreeBasedStructureViewBuilder ?: return null
        val model = builder.createStructureViewModel(null)
        return try {
            buildList { model.root.children.forEach { walk(it, 0, this) } }
        } finally {
            Disposer.dispose(model)
        }
    }

    private fun walk(element: TreeElement, depth: Int, out: MutableList<String>) {
        if (depth >= MAX_DEPTH || out.size > 60) return
        val presentation = element.presentation
        val name = presentation.presentableText?.takeIf { it.isNotBlank() } ?: return
        val psi = (element as? StructureViewTreeElement)?.value as? PsiElement
        val doc = psi?.let(::firstDocLine)
        out += "  ".repeat(depth) + "- " + name + (doc?.let { "  // $it" } ?: "")
        element.children.forEach { walk(it, depth + 1, out) }
    }

    /** KDoc sits inside a Kotlin declaration; Javadoc and friends usually sit just before it. */
    private fun firstDocLine(element: PsiElement): String? {
        val comment = element.children.firstOrNull { it is PsiComment && it.text.startsWith("/**") }
            ?: PsiTreeUtil.skipWhitespacesBackward(element)?.takeIf { it is PsiComment && it.text.startsWith("/**") }
            ?: return null
        return comment.text.lineSequence()
            .map { it.trim().removePrefix("/**").removeSuffix("*/").removePrefix("*").trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("@") }
            ?.take(120)
    }
}

/** The spike's sketcher, for files whose language has no tree-based structure view. */
object RegexSketcher {
    // Exactly the spike's patterns (jev_spike.py), so the plugin sketches what the eval measured.
    private val DECL = Regex("^(?:[\\w@]+(?:\\([^)]*\\))?\\s+)*?(class|interface|object|fun|typealias|val|var)\\b")
    private val SIGNATURE_END = Regex("\\s[{=]\\s|\\s\\{$|\\{$")

    fun sketch(path: String, text: String): String {
        val out = mutableListOf("path: $path")
        var doc: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            val s = line.trim()
            if (s.startsWith("package ")) out += s
            if (s.startsWith("/**")) doc = s.removePrefix("/**").removeSuffix("*/").trim().ifEmpty { null }
            val indent = line.length - line.trimStart().length
            if (indent > 8 || s.startsWith("private ") || s.startsWith("//") || s.startsWith("*") || s.startsWith("import ")) {
                if (s.startsWith("* ") && doc == null) doc = s.removePrefix("* ").trim()
                continue
            }
            if (DECL.containsMatchIn(s)) {
                val sig = SIGNATURE_END.split(s, 2)[0]
                out += "  ".repeat(indent / 4) + "- " + sig + (doc?.let { "  // $it" } ?: "")
                doc = null
            }
        }
        return out.joinToString("\n").take(Sketcher.MAX_CHARS)
    }
}
