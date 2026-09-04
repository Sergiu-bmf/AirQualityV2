package com.example.android_app.ui.components

import androidx.compose.ui.graphics.Color
import com.example.android_app.data.SensorReading
import java.util.Locale

/**
 * The catalog of everything the Arduino reports, in one place: how to pull it off a
 * [SensorReading], how to label and format it, and which reference line belongs on its
 * chart. The status tiles, the charts and the detail sheet all iterate this, so adding a
 * sensor to the sketch and the pipeline means adding exactly one entry here.
 *
 * `sound_raw` is deliberately absent — it is a monotonic transform of [SOUND] via the
 * pipeline's `raw_to_db()`, so charting both would draw the same curve twice.
 */
enum class SensorMetric(
    val label: String,
    val unit: String,
    val valueFormat: String,
    val color: Color,
    val threshold: Float?,
    val thresholdLabel: String,
    val sensor: String,
    val select: (SensorReading) -> Float?,
) {
    TEMPERATURE(
        label = "Temperature",
        unit = "°C",
        valueFormat = "%.1f",
        color = Color(0xFFE0662B),
        threshold = AlertThresholds.TEMPERATURE_C,
        thresholdLabel = "alert",
        sensor = "DHT11 · ±2°C, 0–50°C rated",
        select = { it.temperature },
    ),
    HUMIDITY(
        label = "Humidity",
        unit = "%",
        valueFormat = "%.0f",
        color = Color(0xFF1E88E5),
        threshold = AlertThresholds.HUMIDITY_PERCENT,
        thresholdLabel = "alert",
        sensor = "DHT11 · ±5%, 20–90% rated",
        select = { it.humidity },
    ),
    SOUND(
        label = "Sound",
        unit = "dB",
        valueFormat = "%.0f",
        color = Color(0xFF8E24AA),
        threshold = AlertThresholds.SOUND_DB,
        thresholdLabel = "alert",
        sensor = "Analog mic · dB derived from the averaged raw ADC",
        select = { it.soundDb },
    ),
    LIGHT(
        label = "Light",
        unit = "raw",
        valueFormat = "%.0f",
        color = Color(0xFFF9A825),
        threshold = AlertThresholds.LIGHT_RAW,
        thresholdLabel = "alert",
        sensor = "LDR · 0–1023 ADC, relative to your resistor pairing",
        select = { it.lightRaw },
    ),
    GAS(
        label = "Gas",
        unit = "raw",
        valueFormat = "%.0f",
        color = Color(0xFF00897B),
        threshold = AlertThresholds.GAS_RAW,
        thresholdLabel = "alert",
        sensor = "MQ-135 · 0–1023 ADC, threshold still a placeholder",
        select = { it.gasRaw },
    ),
    FLAME(
        label = "Flame",
        unit = "raw",
        valueFormat = "%.0f",
        color = Color(0xFFC62828),
        threshold = AlertThresholds.FLAME_RAW_DETECT,
        thresholdLabel = "detect",
        sensor = "IR flame sensor · ~3 at rest, ~700 at close range",
        select = { it.flameRaw },
    );

    /** The plottable samples for this metric, skipping rows where it wasn't recorded. */
    fun series(history: List<SensorReading>): List<ChartPoint> =
        history.mapNotNull { reading ->
            select(reading)?.let { ChartPoint(reading.timestamp, it) }
        }

    /** Formats a bare value with this metric's precision, without the unit. */
    fun render(value: Float): String = String.format(Locale.getDefault(), valueFormat, value)

    /** Formats a value with its unit, or an em dash when the sensor reported nothing. */
    fun renderWithUnit(value: Float?): String =
        if (value == null) "—" else "${render(value)} $unit"
}
