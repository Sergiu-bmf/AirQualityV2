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
 * `sound_raw` is deliberately absent , it is a monotonic transform of [SOUND] via the
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
        unit = "lux",
        valueFormat = "%.1f",
        color = Color(0xFFF9A825),
        threshold = AlertThresholds.LIGHT_LUX,
        thresholdLabel = "alert",
        sensor = "LDR · lux approximated from the 10k divider; scale is assumed, trend is real",
        select = { SensorConversions.lux(it.lightRaw) },
    ),
    // The only metric whose unit depends on calibration state. Until the MQ-135's
    // clean-air baseline is measured (SensorConversions.GAS_CLEAN_AIR_RAW), a ratio
    // cannot be computed, so this stays on raw ADC rather than showing an empty chart.
    // Setting that one constant flips this entry , unit, format, reference line and all ,
    // with no further edits here.
    GAS(
        label = "Gas",
        unit = if (SensorConversions.GAS_CLEAN_AIR_RAW == null) "raw" else "Rs/R0",
        valueFormat = if (SensorConversions.GAS_CLEAN_AIR_RAW == null) "%.0f" else "%.2f",
        color = Color(0xFF00897B),
        threshold = if (SensorConversions.GAS_CLEAN_AIR_RAW == null) {
            AlertThresholds.GAS_RAW
        } else {
            AlertThresholds.GAS_CLEAN_AIR_RATIO
        },
        thresholdLabel = if (SensorConversions.GAS_CLEAN_AIR_RAW == null) "alert" else "clean air",
        sensor = if (SensorConversions.GAS_CLEAN_AIR_RAW == null) {
            "MQ-135 · raw ADC , set GAS_CLEAN_AIR_RAW to read this as Rs/R0"
        } else {
            "MQ-135 · Rs/R0 vs clean air; 1.0 is baseline, lower means more contaminant"
        },
        // Falls back to raw whenever the ratio is unavailable, so the chart never empties.
        select = { reading -> reading.gasRaw?.let { SensorConversions.gasRatio(it) ?: it } },
    ),
    FLAME(
        label = "Flame",
        unit = "%",
        valueFormat = "%.0f",
        color = Color(0xFFC62828),
        threshold = AlertThresholds.FLAME_DETECT_PERCENT,
        thresholdLabel = "detect",
        sensor = "IR flame sensor · % of the alarm threshold; ~2% at rest, ~470% at close range",
        select = { SensorConversions.flamePercent(it.flameRaw) },
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
        if (value == null) "," else "${render(value)} $unit"
}
