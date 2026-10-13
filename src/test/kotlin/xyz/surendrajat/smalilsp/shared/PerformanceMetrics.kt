package xyz.surendrajat.smalilsp.shared

import kotlin.math.sqrt

/**
 * Performance metrics collector for consistent measurement across tests.
 * Collects latency statistics: min, max, avg, p50, p95, p99.
 */
class PerformanceMetrics(val name: String) {
    private val latencies = mutableListOf<Long>()
    private var totalTime = 0L
    private var count = 0L
    
    /**
     * Record a single operation latency in nanoseconds.
     */
    fun record(latencyNanos: Long) {
        latencies.add(latencyNanos)
        totalTime += latencyNanos
        count++
    }
    
    /**
     * Get average latency in milliseconds.
     */
    fun getAvgMs(): Double {
        return if (count > 0) totalTime.toDouble() / count / 1_000_000.0 else 0.0
    }
    
    /**
     * Get maximum latency in milliseconds.
     */
    fun getMaxMs(): Long {
        return if (latencies.isNotEmpty()) latencies.maxOrNull()!! / 1_000_000 else 0
    }
    
    /**
     * Get minimum latency in milliseconds.
     */
    fun getMinMs(): Long {
        return if (latencies.isNotEmpty()) latencies.minOrNull()!! / 1_000_000 else 0
    }
    
    /**
     * Get percentile latency in milliseconds.
     * @param percentile Value from 0.0 to 1.0 (e.g., 0.95 for p95)
     */
    fun getPercentileMs(percentile: Double): Double {
        if (latencies.isEmpty()) return 0.0
        val sorted = latencies.sorted()
        val index = ((sorted.size - 1) * percentile).toInt()
        return sorted[index] / 1_000_000.0
    }
    
    /**
     * Get median (p50) latency in milliseconds.
     */
    fun getMedianMs(): Double = getPercentileMs(0.5)
    
    /**
     * Get p95 latency in milliseconds.
     */
    fun getP95Ms(): Double = getPercentileMs(0.95)
    
    /**
     * Get p99 latency in milliseconds.
     */
    fun getP99Ms(): Double = getPercentileMs(0.99)
    
    /**
     * Get standard deviation in milliseconds.
     */
    fun getStdDevMs(): Double {
        if (count < 2) return 0.0
        val avg = getAvgMs() * 1_000_000.0 // Convert back to nanos
        val variance = latencies.map { (it - avg) * (it - avg) }.average()
        return sqrt(variance) / 1_000_000.0
    }
    
    
    /**
     * Print summary statistics.
     */
    fun printSummary() {
        println("$name Performance:")
        println("  Count:  $count")
        println("  Min:    ${"%.3f".format(getMinMs().toDouble())}ms")
        println("  Max:    ${"%.3f".format(getMaxMs().toDouble())}ms")
        println("  Avg:    ${"%.3f".format(getAvgMs())}ms")
        println("  Median: ${"%.3f".format(getMedianMs())}ms")
        println("  P95:    ${"%.3f".format(getP95Ms())}ms")
        println("  P99:    ${"%.3f".format(getP99Ms())}ms")
        println("  StdDev: ${"%.3f".format(getStdDevMs())}ms")
    }
    
    /**
     * Get detailed statistics as a map.
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "name" to name,
            "count" to count,
            "min_ms" to getMinMs(),
            "max_ms" to getMaxMs(),
            "avg_ms" to getAvgMs(),
            "median_ms" to getMedianMs(),
            "p95_ms" to getP95Ms(),
            "p99_ms" to getP99Ms(),
            "stddev_ms" to getStdDevMs()
        )
    }
}
