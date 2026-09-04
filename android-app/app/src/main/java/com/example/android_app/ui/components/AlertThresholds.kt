package com.example.android_app.ui.components

/**
 * Threshold lines drawn on the charts, for context only — the app never evaluates them.
 * Alerting happens in `pipeline/sensor_pipeline.py`, which precomputes `status` and
 * `alerts` into each row.
 *
 * These values are a hand-copy of that file's `*_ALERT_HIGH` constants (which are
 * themselves hand-mirrored in `arduino/sensor_data.ino`). Nothing enforces the match, so
 * changing a threshold in the pipeline means changing it here too or the reference line
 * will disagree with the traffic light above it.
 */
object AlertThresholds {
    const val TEMPERATURE_C = 28.0f
    const val HUMIDITY_PERCENT = 50.0f
    const val SOUND_DB = 75.0f
    const val LIGHT_RAW = 800.0f
    const val GAS_RAW = 400.0f

    /**
     * Unlike the others this one is not an *alert* threshold: it mirrors the Arduino's
     * `FLAME_THRESHOLD`, the raw ADC level at which the sketch decides a flame is
     * present. Charted anyway, because seeing how close the resting value sits to the
     * detection line is the only way to tell whether the sensor is about to false-fire.
     */
    const val FLAME_RAW_DETECT = 150.0f
}
