package dev.contextpacker.pack

/** A ~300-token summary of a file: its path, package, and declarations with the first line of each doc comment. */
object RegexSketcher {
    const val MAX_CHARS = 1_200

    // Exactly the spike's patterns (jev_spike.py), so the plugin sketches what the eval measured.
    private val DECL = Regex("^(?:[\\w@]+(?:\\([^)]*\\))?\\s+)*?(class|interface|object|fun|typealias|val|var)\\b")
    private val SIGNATURE_END = Regex("\\s[{=]\\s|\\s\\{$|\\{$")
    // The local benchmark predates Jev's parity correction; keep its inputs reproducible.
    private val LAYA_DECL = Regex("^(?:[\\w@]+(?:\\([^)]*\\))?\\s+)*?(class|interface|object|fun|typealias|val|var|def|function|struct|enum|trait|impl)\\b")
    private val LAYA_SIGNATURE_END = Regex("\\s[{=]\\s|\\s\\{$|\\{$|:$")

    fun sketch(path: String, text: String): String = sketchWith(path, text, DECL, SIGNATURE_END)

    fun layaSketch(path: String, text: String): String = sketchWith(path, text, LAYA_DECL, LAYA_SIGNATURE_END)

    private fun sketchWith(path: String, text: String, declaration: Regex, signatureEnd: Regex): String {
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
            if (declaration.containsMatchIn(s)) {
                val sig = signatureEnd.split(s, 2)[0]
                out += "  ".repeat(indent / 4) + "- " + sig + (doc?.let { "  // $it" } ?: "")
                doc = null
            }
        }
        return out.joinToString("\n").take(MAX_CHARS)
    }
}
