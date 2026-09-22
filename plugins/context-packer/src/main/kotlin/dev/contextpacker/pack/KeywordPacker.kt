package dev.contextpacker.pack

/** Full-corpus lexical baseline. The ordinal rank is the only score exposed for this mode. */
object KeywordPacker {
    fun pack(task: String, docs: Map<String, String>, keep: Int = 20, checkCancelled: () -> Unit = {}): PackResult {
        require(task.isNotBlank()) { "Task must not be blank" }
        require(keep > 0) { "keep must be positive" }
        val started = System.nanoTime()
        checkCancelled()
        val ranked = Bm25.rank(task, docs, checkCancelled = checkCancelled)
        checkCancelled()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        return PackResult(
            task = task,
            files = ranked.take(keep).mapIndexed { index, path ->
                PackedFile(
                    path = path,
                    relevance = 0.0,
                    score = 0.0,
                    bm25Rank = index + 1,
                    isTest = Packer.isTest(path),
                )
            },
            candidates = docs.size,
            pass1Ms = elapsed,
            pass2Ms = 0,
            totalMs = elapsed,
            failedBatches = 0,
        )
    }
}
