package com.example.android_app.ui.components

import kotlin.math.pow

/**
 * Turns the raw ADC values the pipeline stores into units a person can act on.
 *
 * These run at **read time**, in the app, rather than being precomputed into each row the
 * way `sound_db` is. That is a deliberate departure from the `raw_to_db()` precedent, and
 * the reason is that these calibrations are provisional: [LDR_R10_OHMS] is an assumed
 * datasheet figure for an LDR nobody has identified, and [GAS_CLEAN_AIR_RAW] has not been
 * measured yet. Converting here means changing a constant re-scales the *entire* history
 * on the next launch, instead of splicing a differently-scaled tail onto rows already
 * written under the old numbers. `sound_db` stays precomputed because it was fitted to
 * real measurements and isn't expected to move.
 *
 * The raw values remain the ground truth in DynamoDB; nothing here is ever written back.
 */
object SensorConversions {

    // ---------- Light: raw ADC -> lux (approximate) ----------

    /**
     * The fixed leg of the divider, per the wiring in PROJECT_DOCUMENTATION.md:
     * `5V -> LDR -> A1 -> 10k -> GND`, so a higher raw value means more light.
     */
    private const val LDR_DIVIDER_OHMS = 10_000.0

    /**
     * Assumed LDR resistance at 10 lux. **This is the scale knob.** The kit's photoresistor
     * is a bare unmarked component, so this is the nominal figure for a GL5528, the most
     * common part in kits of this kind — not a measurement. If a phone lux meter says the
     * room is 10x brighter than the app claims, this constant is what to correct: lux
     * scales as `(R10 / R)^(1/gamma)`, so raising R10 raises every reading.
     */
    private const val LDR_R10_OHMS = 10_000.0

    /** Assumed slope of the LDR's log-log response. Typical GL5528 range is 0.5-0.8. */
    private const val LDR_GAMMA = 0.6

    /**
     * Brightest value worth reporting. Direct sunlight is roughly 100k lux; past that the
     * divider is effectively saturated and the exponent turns rounding noise in the last
     * ADC step into millions of lux, which would flatten every real reading on the chart.
     */
    private const val MAX_LUX = 100_000.0

    /** LDR resistance implied by a raw reading, or null when the divider is pinned. */
    fun ldrResistanceOhms(raw: Float): Float? {
        if (raw <= 0f || raw >= 1023f) return null
        return (LDR_DIVIDER_OHMS * (1023.0 - raw) / raw).toFloat()
    }

    /**
     * Approximate ambient light in lux. Trustworthy for *relative* change; the absolute
     * scale is only as good as [LDR_R10_OHMS], which is assumed rather than measured.
     */
    fun lux(raw: Float): Float? {
        val resistance = ldrResistanceOhms(raw)?.toDouble() ?: return null
        if (resistance <= 0.0) return null
        val lux = 10.0 * (LDR_R10_OHMS / resistance).pow(1.0 / LDR_GAMMA)
        return lux.coerceAtMost(MAX_LUX).toFloat()
    }

    // ---------- Gas: raw ADC -> Rs/R0 against clean air ----------

    /**
     * The raw value this specific MQ-135 settles at in clean air once the heater has
     * stabilised. **Not measured yet — until it is set, [gasRatio] returns null and the
     * app keeps showing raw ADC.** To find it: run the pipeline in a well-ventilated room
     * for a few windows and read `gas_raw` off the stored item it prints.
     *
     * Note this is the only number needed. The module's load resistor cancels out of the
     * ratio, so it does not matter that we don't know whether the board carries 1k or 10k.
     */
    val GAS_CLEAN_AIR_RAW: Float? = null

    /**
     * Sensor resistance relative to its clean-air resistance: 1.0 is the baseline, and
     * lower means more of whatever the MQ-135 responds to (CO2, ammonia, benzene, smoke —
     * it cannot tell them apart, which is why this is a ratio and not a ppm figure).
     *
     * Returns null when the baseline is unset or the reading is pinned at either rail.
     */
    fun gasRatio(raw: Float): Float? {
        val baseline = GAS_CLEAN_AIR_RAW ?: return null
        if (raw <= 0f || raw >= 1023f) return null
        if (baseline <= 0f || baseline >= 1023f) return null
        val rs = (1023.0 - raw) / raw
        val r0 = (1023.0 - baseline) / baseline
        if (r0 <= 0.0) return null
        return (rs / r0).toFloat()
    }

    // ---------- Flame: raw ADC -> % of the detection threshold ----------

    /**
     * Mirrors `FLAME_THRESHOLD` in `arduino/sensor_data.ino` — the level at which the
     * sketch lights the red LED and sounds the buzzer. Hand-copied, like every other
     * threshold in this project; if it changes in the sketch it must change here.
     */
    const val FLAME_DETECT_RAW = 150f

    /**
     * How close this reading sits to tripping the on-device alarm: 100% is the threshold
     * itself. The sensor measures IR intensity with no physical unit attached, so a
     * percentage of the alarm point is the only honest way to make it readable — it
     * answers "how much headroom is left", which is what the chart is for.
     *
     * Values above 100% are real and expected: a flame held close reads ~700 raw, ~467%.
     */
    fun flamePercent(raw: Float): Float = 100f * raw / FLAME_DETECT_RAW
}
