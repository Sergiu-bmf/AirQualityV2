package com.example.android_app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One averaged window as written by `pipeline/sensor_pipeline.py` and served back by
 * `lambda/lambda_function.py`.
 *
 * Nullability mirrors the pipeline exactly:
 *  - [gasRaw] is null when the Arduino sketch doesn't print a `Gas:` field.
 *  - [soundDb] is null when the averaged raw value was <= 0 (`raw_to_db` returns None).
 * Everything else is always written, but the trailing fields are given defaults so an
 * older row missing them still deserializes instead of crashing the screen.
 */
@Serializable
data class SensorReading(
    @SerialName("device_id") val deviceId: String,
    val timestamp: Long,
    @SerialName("window_start") val windowStart: Long = timestamp,
    @SerialName("window_end") val windowEnd: Long = timestamp,
    val temperature: Float,
    val humidity: Float,
    @SerialName("sound_raw") val soundRaw: Float,
    @SerialName("sound_db") val soundDb: Float? = null,
    @SerialName("light_raw") val lightRaw: Float,
    @SerialName("gas_raw") val gasRaw: Float? = null,
    @SerialName("flame_raw") val flameRaw: Float = 0f,
    @SerialName("flame_detected") val flameDetected: Boolean = false,
    val alerts: List<String> = emptyList(),
    val status: String = "green",
    @SerialName("sample_count") val sampleCount: Int = 0,
    @SerialName("rejected_count") val rejectedCount: Int = 0,
)

/**
 * Shape of the Lambda's `/history` response: `{"items": [...]}`.
 *
 * [truncated] is set by the Lambda when it stopped paginating at its own item cap, so
 * the app can say the oldest part of the range is missing instead of drawing a chart
 * that just looks like the device was off.
 */
@Serializable
data class HistoryResponse(
    val items: List<SensorReading> = emptyList(),
    val truncated: Boolean = false,
)

/**
 * The pipeline precomputes the traffic light into `status`, so the app never re-derives
 * it from [SensorReading.alerts]. Unknown values fall back to [UNKNOWN] rather than
 * silently reading as "green", so a schema change shows up instead of looking healthy.
 */
enum class TrafficLight { GREEN, YELLOW, RED, UNKNOWN }

val SensorReading.trafficLight: TrafficLight
    get() = when (status.lowercase()) {
        "green" -> TrafficLight.GREEN
        "yellow" -> TrafficLight.YELLOW
        "red" -> TrafficLight.RED
        else -> TrafficLight.UNKNOWN
    }
