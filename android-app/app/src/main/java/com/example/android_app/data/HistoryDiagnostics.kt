package com.example.android_app.data

/**
 * How trustworthy the plotted history actually is, derived from the bookkeeping fields
 * the pipeline already stores on every row (`sample_count`, `rejected_count`) plus the
 * spacing between rows.
 *
 * This matters because a chart drawn from four rows looks exactly like a chart drawn
 * from four hundred — without this, a mostly-offline device reads as a calm one.
 */
data class HistoryDiagnostics(
    val windowCount: Int,
    val expectedWindows: Int,
    val sampleCount: Int,
    val rejectedCount: Int,
    val largestGapSeconds: Long,
    val greenCount: Int,
    val yellowCount: Int,
    val redCount: Int,
    val flameWindows: Int,
) {
    /** Rows present vs. rows expected, clamped — a burst of catch-up writes isn't >100%. */
    val coverage: Float
        get() = if (expectedWindows <= 0) 0f else (windowCount.toFloat() / expectedWindows).coerceIn(0f, 1f)

    /** Share of raw serial lines thrown out by `is_valid_reading()` before averaging. */
    val rejectedFraction: Float
        get() {
            val total = sampleCount + rejectedCount
            return if (total <= 0) 0f else rejectedCount.toFloat() / total
        }

    val isEmpty: Boolean get() = windowCount == 0
}

/**
 * Summarises [readings] against the range they were requested for. [readings] must be
 * sorted ascending by timestamp, which is what [SensorRepository] guarantees.
 */
fun diagnose(readings: List<SensorReading>, range: TimeRange): HistoryDiagnostics =
    HistoryDiagnostics(
        windowCount = readings.size,
        expectedWindows = range.expectedWindows,
        sampleCount = readings.sumOf { it.sampleCount },
        rejectedCount = readings.sumOf { it.rejectedCount },
        largestGapSeconds = largestGap(readings.map { it.timestamp }),
        greenCount = readings.count { it.trafficLight == TrafficLight.GREEN },
        yellowCount = readings.count { it.trafficLight == TrafficLight.YELLOW },
        redCount = readings.count { it.trafficLight == TrafficLight.RED },
        flameWindows = readings.count { it.flameDetected },
    )

/**
 * Longest silence between consecutive timestamps, in seconds. Returns 0 for fewer than
 * two samples — one row can't establish a gap, and reporting the whole range as a gap
 * would overstate what we know.
 */
fun largestGap(timestamps: List<Long>): Long {
    if (timestamps.size < 2) return 0L
    var largest = 0L
    for (i in 1 until timestamps.size) {
        val delta = timestamps[i] - timestamps[i - 1]
        if (delta > largest) largest = delta
    }
    return largest
}
