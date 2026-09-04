package com.example.android_app

import com.example.android_app.data.SensorReading
import com.example.android_app.data.TimeRange
import com.example.android_app.data.TrafficLight
import com.example.android_app.data.diagnose
import com.example.android_app.data.largestGap
import com.example.android_app.data.trafficLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDiagnosticsTest {

    private fun reading(
        timestamp: Long,
        status: String = "green",
        samples: Int = 20,
        rejected: Int = 0,
        flame: Boolean = false,
    ) = SensorReading(
        deviceId = "arduino-01",
        timestamp = timestamp,
        windowStart = timestamp,
        windowEnd = timestamp + 60,
        temperature = 22f,
        humidity = 45f,
        soundRaw = 300f,
        soundDb = 60f,
        lightRaw = 400f,
        flameDetected = flame,
        status = status,
        sampleCount = samples,
        rejectedCount = rejected,
    )

    @Test
    fun `coverage compares stored windows against the pipeline cadence`() {
        // Half of the 15 windows an hour should produce.
        val readings = (0 until 8).map { reading(1_000L + it * 240L) }
        val diagnostics = diagnose(readings, TimeRange.LAST_HOUR)

        assertEquals(8, diagnostics.windowCount)
        assertEquals(15, diagnostics.expectedWindows)
        assertEquals(0.53f, diagnostics.coverage, 0.01f)
    }

    @Test
    fun `coverage never exceeds one when extra rows arrive`() {
        val readings = (0 until 40).map { reading(1_000L + it * 60L) }
        assertEquals(1f, diagnose(readings, TimeRange.LAST_HOUR).coverage, 0.001f)
    }

    @Test
    fun `an empty range reports zero coverage rather than dividing by zero`() {
        val diagnostics = diagnose(emptyList(), TimeRange.LAST_24_HOURS)
        assertEquals(0f, diagnostics.coverage, 0.001f)
        assertEquals(0f, diagnostics.rejectedFraction, 0.001f)
        assertEquals(0L, diagnostics.largestGapSeconds)
        assertTrue(diagnostics.isEmpty)
    }

    @Test
    fun `rejected fraction is measured against every line read, not just the kept ones`() {
        val readings = listOf(
            reading(1_000L, samples = 15, rejected = 5),
            reading(1_240L, samples = 15, rejected = 5),
        )
        val diagnostics = diagnose(readings, TimeRange.LAST_HOUR)

        assertEquals(30, diagnostics.sampleCount)
        assertEquals(10, diagnostics.rejectedCount)
        assertEquals(0.25f, diagnostics.rejectedFraction, 0.001f)
    }

    @Test
    fun `status counts split the range by traffic light`() {
        val readings = listOf(
            reading(1_000L, status = "green"),
            reading(1_240L, status = "yellow"),
            reading(1_480L, status = "red", flame = true),
            reading(1_720L, status = "green"),
        )
        val diagnostics = diagnose(readings, TimeRange.LAST_HOUR)

        assertEquals(2, diagnostics.greenCount)
        assertEquals(1, diagnostics.yellowCount)
        assertEquals(1, diagnostics.redCount)
        assertEquals(1, diagnostics.flameWindows)
    }

    @Test
    fun `largest gap finds the longest silence`() {
        assertEquals(3_600L, largestGap(listOf(0L, 240L, 480L, 4_080L, 4_320L)))
    }

    @Test
    fun `largest gap needs two samples to mean anything`() {
        assertEquals(0L, largestGap(emptyList()))
        assertEquals(0L, largestGap(listOf(1_000L)))
    }

    @Test
    fun `an unrecognised status reads as unknown rather than green`() {
        // A schema change should look broken, not healthy.
        assertEquals(TrafficLight.UNKNOWN, reading(1_000L, status = "amber").trafficLight)
        assertEquals(TrafficLight.RED, reading(1_000L, status = "RED").trafficLight)
    }
}
