package dev.intellijev.core

/** Shared identifiers are a search lead, not evidence that two files have the same defect. */
internal object RelatedCode {
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val boundary = Regex("(?<=[a-z0-9])(?=[A-Z])|_")
    private val common = setOf(
        "assert", "await", "break", "catch", "class", "const", "continue", "else", "false", "final",
        "for", "from", "function", "import", "interface", "null", "override", "private", "public",
        "return", "static", "super", "this", "throw", "true", "type", "val", "var", "void", "while",
        "with", "when", "then", "test", "tests", "string", "int", "long", "boolean",
    )

    fun signature(selection: String): Set<String> = tokens(selection.take(4_000)).take(24).toSet()

    fun sharedTerms(line: String, signature: Set<String>): List<String> =
        if (signature.isEmpty()) emptyList() else tokens(line).filter { it in signature }.distinct()

    private fun tokens(text: String): List<String> = identifier.findAll(text).flatMap { match ->
        sequenceOf(match.value.lowercase()) + boundary.split(match.value).asSequence().map(String::lowercase)
    }.filter { it.length >= 4 && it !in common }.distinct().toList()
}
