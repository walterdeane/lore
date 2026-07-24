package com.walterdeane.lore.search

import org.slf4j.Logger

/**
 * Times [block], logs one greppable line (`stage=<stage> query='<query>' ms=<elapsed>`) via [log],
 * and returns both the block's result and the elapsed milliseconds — post-03 instrumentation for
 * building a before/after latency picture across the retrieval/chat pipeline without a metrics
 * stack. Callers that only need the logging side effect can discard the second element.
 */
inline fun <T> timedStage(log: Logger, stage: String, query: String, block: () -> T): Pair<T, Long> {
    val start = System.nanoTime()
    val result = block()
    val elapsedMs = (System.nanoTime() - start) / 1_000_000
    log.info("stage={} query='{}' ms={}", stage, query, elapsedMs)
    return result to elapsedMs
}
