package com.walterdeane.lore.search

import org.slf4j.Logger

/**
 * Runs [block], measures how long it took, and logs one line —
 * `stage=<stage> query='<query>' ms=<elapsed>` — so anyone can search the logs for a given stage
 * name (e.g. `vector_sql`) and see how long it's taking in practice. Returns both the block's
 * result and the elapsed time in milliseconds; callers that only care about the log line can
 * ignore the second value.
 */
inline fun <T> timedStage(log: Logger, stage: String, query: String, block: () -> T): Pair<T, Long> {
    val start = System.nanoTime()
    val result = block()
    val elapsedMs = (System.nanoTime() - start) / 1_000_000
    log.info("stage={} query='{}' ms={}", stage, query, elapsedMs)
    return result to elapsedMs
}
