package dev.contextpacker

/** Turns a task and its picked files into one prompt an LLM can answer in a single shot. */
object Prompt {
    const val SYSTEM = "You are a senior engineer working in this codebase. The files below were selected " +
        "as the ones this task needs. Say which files to change and show the changes as concise diffs. " +
        "If something you need is missing from the files given, say what."

    private const val MAX_CHARS_PER_FILE = 12_000

    fun build(task: String, files: List<Pair<String, String>>): String = buildString {
        append("# Task\n\n").append(task.trim()).append("\n\n# Relevant files\n")
        for ((path, text) in files) {
            val body = if (text.length > MAX_CHARS_PER_FILE) text.take(MAX_CHARS_PER_FILE) + "\n// … truncated" else text
            append("\n## ").append(path).append("\n\n```").append(path.substringAfterLast('.', "")).append('\n')
            append(body.trimEnd()).append("\n```\n")
        }
    }
}
