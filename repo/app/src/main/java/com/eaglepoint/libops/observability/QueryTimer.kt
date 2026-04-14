package com.eaglepoint.libops.observability

/**
 * Utility to time DAO/query operations and report to [ObservabilityPipeline].
 *
 * Usage:
 * ```
 * val result = queryTimer.timed("query", "recordDao.search") {
 *     recordDao.search(...)
 * }
 * ```
 */
class QueryTimer(private val pipeline: ObservabilityPipeline) {

    suspend fun <T> timed(kind: String, label: String, block: suspend () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val durationMs = (System.nanoTime() - start) / 1_000_000L
        pipeline.recordPerformanceSample(kind, label, durationMs)
        return result
    }
}
