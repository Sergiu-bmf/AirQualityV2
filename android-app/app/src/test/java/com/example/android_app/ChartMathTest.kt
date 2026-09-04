package com.example.android_app

import com.example.android_app.data.TimeRange
import com.example.android_app.ui.components.ChartMath
import com.example.android_app.ui.components.ChartPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {

    @Test
    fun `flat series gets a padded band instead of a zero-height range`() {
        val bounds = ChartMath.valueBounds(listOf(300f, 300f, 300f))
        assertTrue("expected a non-zero span", bounds.endInclusive > bounds.start)
        assertTrue(bounds.start < 300f && bounds.endInclusive > 300f)
    }

    @Test
    fun `varied series is padded around min and max`() {
        val bounds = ChartMath.valueBounds(listOf(20f, 30f))
        assertEquals(19f, bounds.start, 0.001f)
        assertEquals(31f, bounds.endInclusive, 0.001f)
    }

    @Test
    fun `empty series does not divide by zero`() {
        val bounds = ChartMath.valueBounds(emptyList())
        assertTrue(bounds.endInclusive > bounds.start)
    }

    @Test
    fun `y fraction spans bottom to top and clamps outliers`() {
        val bounds = 0f..100f
        assertEquals(0f, ChartMath.yFraction(0f, bounds), 0.001f)
        assertEquals(0.5f, ChartMath.yFraction(50f, bounds), 0.001f)
        assertEquals(1f, ChartMath.yFraction(100f, bounds), 0.001f)
        // A threshold line above the data still draws at the edge, not off-canvas.
        assertEquals(1f, ChartMath.yFraction(400f, bounds), 0.001f)
        assertEquals(0f, ChartMath.yFraction(-5f, bounds), 0.001f)
    }

    @Test
    fun `x fraction maps timestamps across the window`() {
        assertEquals(0f, ChartMath.xFraction(1000L, 1000L, 2000L), 0.001f)
        assertEquals(0.5f, ChartMath.xFraction(1500L, 1000L, 2000L), 0.001f)
        assertEquals(1f, ChartMath.xFraction(2000L, 1000L, 2000L), 0.001f)
    }

    @Test
    fun `single sample pins to the right edge rather than dividing by zero`() {
        assertEquals(1f, ChartMath.xFraction(1000L, 1000L, 1000L), 0.001f)
    }

    @Test
    fun `time range bounds end at now and span the labelled duration`() {
        val now = 1_700_000_000L
        val bounds = TimeRange.LAST_6_HOURS.bounds(now)
        assertEquals(now, bounds.last)
        assertEquals(now - 6 * 60 * 60, bounds.first)
    }

    @Test
    fun `wide ranges are flagged as spanning multiple days`() {
        assertTrue(!TimeRange.LAST_24_HOURS.spansMultipleDays)
        assertTrue(TimeRange.LAST_3_DAYS.spansMultipleDays)
        assertTrue(TimeRange.LAST_7_DAYS.spansMultipleDays)
    }

    @Test
    fun `expected window count follows the pipeline cadence`() {
        // One row every 4 minutes: 15 an hour, 360 a day.
        assertEquals(15, TimeRange.LAST_HOUR.expectedWindows)
        assertEquals(360, TimeRange.LAST_24_HOURS.expectedWindows)
    }

    // ---- scrubbing ----

    @Test
    fun `timestamp at fraction is the inverse of x fraction`() {
        assertEquals(1000L, ChartMath.timestampAt(0f, 1000L, 2000L))
        assertEquals(1500L, ChartMath.timestampAt(0.5f, 1000L, 2000L))
        assertEquals(2000L, ChartMath.timestampAt(1f, 1000L, 2000L))
        // A touch outside the canvas still resolves to an in-range instant.
        assertEquals(1000L, ChartMath.timestampAt(-0.4f, 1000L, 2000L))
        assertEquals(2000L, ChartMath.timestampAt(1.4f, 1000L, 2000L))
    }

    @Test
    fun `nearest index snaps to a real sample`() {
        val points = listOf(ChartPoint(100L, 1f), ChartPoint(200L, 2f), ChartPoint(500L, 3f))
        assertEquals(0, ChartMath.nearestIndex(points, 90L))
        assertEquals(1, ChartMath.nearestIndex(points, 210L))
        // Touching the middle of a gap picks the closer edge, not an interpolation.
        assertEquals(2, ChartMath.nearestIndex(points, 400L))
        assertEquals(2, ChartMath.nearestIndex(points, 9_999L))
    }

    @Test
    fun `nearest index on an empty series returns no match`() {
        assertEquals(-1, ChartMath.nearestIndex(emptyList(), 100L))
    }

    // ---- gaps ----

    @Test
    fun `evenly spaced points stay one segment`() {
        val points = (0..5).map { ChartPoint(1000L + it * 240L, it.toFloat()) }
        val segments = ChartMath.segments(points, maxGapSeconds = 600L)
        assertEquals(1, segments.size)
        assertEquals(6, segments.first().size)
    }

    @Test
    fun `an outage splits the series so the line is not drawn across it`() {
        val points = listOf(
            ChartPoint(1_000L, 1f),
            ChartPoint(1_240L, 2f),
            // Four hours of nothing, the pipeline was not running.
            ChartPoint(15_640L, 3f),
            ChartPoint(15_880L, 4f),
        )
        val segments = ChartMath.segments(points, maxGapSeconds = 600L)
        assertEquals(2, segments.size)
        assertEquals(listOf(1_000L, 1_240L), segments[0].map { it.timestamp })
        assertEquals(listOf(15_640L, 15_880L), segments[1].map { it.timestamp })
    }

    @Test
    fun `segmenting an empty series yields no segments`() {
        assertTrue(ChartMath.segments(emptyList(), 600L).isEmpty())
    }

    // ---- downsampling ----

    @Test
    fun `series shorter than the cap is left untouched`() {
        val points = (0..9).map { ChartPoint(it.toLong(), it.toFloat()) }
        assertEquals(points, ChartMath.downsample(points, 50))
    }

    @Test
    fun `downsampling reduces to the cap and keeps the time span`() {
        val points = (0 until 1000).map { ChartPoint(1000L + it, it.toFloat()) }
        val reduced = ChartMath.downsample(points, 100)
        assertEquals(100, reduced.size)
        // Bucket means, so the ends move inward by half a bucket rather than staying put.
        assertTrue(reduced.first().timestamp >= points.first().timestamp)
        assertTrue(reduced.last().timestamp <= points.last().timestamp)
        assertTrue(reduced.zipWithNext().all { (a, b) -> a.timestamp < b.timestamp })
    }

    @Test
    fun `downsampling preserves the overall level`() {
        val points = (0 until 500).map { ChartPoint(it.toLong(), 42f) }
        val reduced = ChartMath.downsample(points, 20)
        assertTrue(reduced.all { it.value == 42f })
    }

    @Test
    fun `render segments downsamples without merging across a gap`() {
        val before = (0 until 300).map { ChartPoint(1_000L + it * 240L, 10f) }
        val after = (0 until 300).map { ChartPoint(500_000L + it * 240L, 90f) }
        val segments = ChartMath.renderSegments(before + after, maxGapSeconds = 600L, maxPoints = 100)

        assertEquals(2, segments.size)
        assertTrue("segments should be reduced", segments.sumOf { it.size } <= 100)
        // No bucket straddles the outage, so no averaged point lands between the levels.
        assertTrue(segments[0].all { it.value == 10f })
        assertTrue(segments[1].all { it.value == 90f })
    }

    @Test
    fun `a short segment survives downsampling of a long series`() {
        val long = (0 until 1000).map { ChartPoint(1_000L + it * 240L, 1f) }
        val brief = listOf(ChartPoint(900_000L, 5f), ChartPoint(900_240L, 6f))
        val segments = ChartMath.renderSegments(long + brief, maxGapSeconds = 600L, maxPoints = 100)

        assertEquals(2, segments.size)
        assertTrue("a two-point segment must not be dropped", segments[1].isNotEmpty())
    }

    // ---- stats ----

    @Test
    fun `stats summarise the whole series`() {
        val points = listOf(
            ChartPoint(1L, 10f),
            ChartPoint(2L, 30f),
            ChartPoint(3L, 20f),
        )
        val stats = ChartMath.statsOf(points)!!
        assertEquals(10f, stats.min, 0.001f)
        assertEquals(30f, stats.max, 0.001f)
        assertEquals(20f, stats.average, 0.001f)
        assertEquals(10f, stats.first, 0.001f)
        assertEquals(20f, stats.latest, 0.001f)
        assertEquals(10f, stats.delta, 0.001f)
    }

    @Test
    fun `stats of an empty series is null rather than zeroes`() {
        // Zeroes would render as a real reading of 0; null lets the caller show nothing.
        assertEquals(null, ChartMath.statsOf(emptyList()))
    }
}
