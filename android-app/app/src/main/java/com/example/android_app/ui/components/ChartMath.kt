package com.example.android_app.ui.components

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/** One plotted sample: a unix timestamp (seconds) and the value at that time. */
data class ChartPoint(val timestamp: Long, val value: Float)

/**
 * Min/max/mean plus the net change across a plotted series. Pure data so the chart
 * header, the stats strip and tests can all share one definition of "the summary".
 */
data class SeriesStats(
    val min: Float,
    val max: Float,
    val average: Float,
    val first: Float,
    val latest: Float,
) {
    /** Net change from the oldest to the newest sample — sign carries the trend. */
    val delta: Float get() = latest - first
}

/**
 * Pure geometry/scaling helpers for [SensorLineChart], kept out of the composable so
 * they can be unit-tested on the JVM without an emulator.
 */
object ChartMath {

    /**
     * Vertical span to draw, padded so the line never touches the frame edge.
     *
     * A flat series (every sample identical, common for light at night or an idle gas
     * sensor) has zero span and would divide by zero, so it gets an arbitrary band
     * around the value instead of collapsing to a single row of pixels.
     */
    fun valueBounds(values: List<Float>): ClosedFloatingPointRange<Float> {
        if (values.isEmpty()) return 0f..1f
        val min = values.min()
        val max = values.max()
        if (abs(max - min) < 1e-4f) {
            val pad = max(abs(min) * 0.05f, 0.5f)
            return (min - pad)..(max + pad)
        }
        val pad = (max - min) * 0.1f
        return (min - pad)..(max + pad)
    }

    /**
     * Fraction (0f..1f) of the plot height for [value], measured from the bottom.
     * Values outside [bounds] are clamped so a threshold line drawn off-scale still
     * renders at the edge rather than outside the canvas.
     */
    fun yFraction(value: Float, bounds: ClosedFloatingPointRange<Float>): Float {
        val span = bounds.endInclusive - bounds.start
        if (span <= 0f) return 0.5f
        return ((value - bounds.start) / span).coerceIn(0f, 1f)
    }

    /**
     * Fraction (0f..1f) of the plot width for [timestamp] within [start]..[end].
     * A zero-width time span (a single sample) pins to the right edge, matching where
     * "the latest reading" belongs.
     */
    fun xFraction(timestamp: Long, start: Long, end: Long): Float {
        if (end <= start) return 1f
        return ((timestamp - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
    }

    /** Inverse of [xFraction]: the timestamp under a horizontal touch position. */
    fun timestampAt(fraction: Float, start: Long, end: Long): Long {
        if (end <= start) return end
        return start + ((end - start) * fraction.coerceIn(0f, 1f)).roundToLong()
    }

    /**
     * Index of the sample closest in time to [timestamp], or -1 when there are none.
     * Used to snap the scrub cursor onto a real reading rather than interpolating a
     * value that was never recorded.
     */
    fun nearestIndex(points: List<ChartPoint>, timestamp: Long): Int {
        if (points.isEmpty()) return -1
        var best = 0
        var bestDistance = Long.MAX_VALUE
        points.forEachIndexed { index, point ->
            val distance = abs(point.timestamp - timestamp)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    /**
     * Splits a series wherever consecutive samples are more than [maxGapSeconds] apart,
     * so the chart can leave outages blank. Drawing straight through a gap would invent
     * a smooth transition across hours when the device was simply off.
     */
    fun segments(points: List<ChartPoint>, maxGapSeconds: Long): List<List<ChartPoint>> {
        if (points.isEmpty()) return emptyList()
        val result = mutableListOf<List<ChartPoint>>()
        var current = mutableListOf(points.first())
        for (i in 1 until points.size) {
            if (points[i].timestamp - points[i - 1].timestamp > maxGapSeconds) {
                result.add(current)
                current = mutableListOf()
            }
            current.add(points[i])
        }
        result.add(current)
        return result
    }

    /**
     * Reduces a series to at most [maxPoints] by averaging equal-sized buckets. A week
     * of history is ~2500 rows, far more than a phone-width canvas has pixels; averaging
     * keeps the shape while cutting the path length.
     *
     * Averaging (rather than sampling every Nth point) is the right call here because
     * the underlying rows are themselves averages — but it does flatten brief spikes, so
     * the min/max in [statsOf] is always computed on the *full* series, not this one.
     */
    fun downsample(points: List<ChartPoint>, maxPoints: Int): List<ChartPoint> {
        if (maxPoints < 2 || points.size <= maxPoints) return points
        val out = ArrayList<ChartPoint>(maxPoints)
        for (bucket in 0 until maxPoints) {
            val from = (bucket.toLong() * points.size / maxPoints).toInt()
            val to = ((bucket + 1).toLong() * points.size / maxPoints).toInt().coerceAtLeast(from + 1)
            var valueSum = 0.0
            var timeSum = 0.0
            var count = 0
            for (i in from until minOf(to, points.size)) {
                valueSum += points[i].value
                timeSum += points[i].timestamp
                count++
            }
            if (count > 0) out.add(ChartPoint((timeSum / count).roundToLong(), (valueSum / count).toFloat()))
        }
        return out
    }

    /**
     * Gap-aware downsampling: buckets never span an outage, and every surviving segment
     * keeps at least its endpoints so short bursts of data don't vanish on a wide range.
     */
    fun renderSegments(
        points: List<ChartPoint>,
        maxGapSeconds: Long,
        maxPoints: Int,
    ): List<List<ChartPoint>> {
        val segments = segments(points, maxGapSeconds)
        if (points.size <= maxPoints) return segments
        return segments.map { segment ->
            val share = (maxPoints.toLong() * segment.size / points.size).toInt()
            downsample(segment, share.coerceAtLeast(2))
        }
    }

    /** Min/max/mean of [points], or null when there is nothing to summarise. */
    fun statsOf(points: List<ChartPoint>): SeriesStats? {
        if (points.isEmpty()) return null
        val values = points.map { it.value }
        return SeriesStats(
            min = values.min(),
            max = values.max(),
            average = values.average().toFloat(),
            first = values.first(),
            latest = values.last(),
        )
    }
}
