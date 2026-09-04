package com.example.android_app.data

/**
 * How often the pipeline produces a row, mirrored from `pipeline/sensor_pipeline.py`.
 * It collects for `AVERAGING_WINDOW_SECONDS` (60) then idles for `IDLE_SECONDS` (180),
 * so a healthy device writes one averaged window every four minutes.
 */
object PipelineTiming {
    /** Expected spacing between consecutive rows, in seconds. */
    const val WINDOW_SECONDS = 240L

    /**
     * Spacing above which we treat the data as genuinely missing rather than jittery,
     * so charts break the line instead of drawing a straight segment across an outage.
     * Deliberately loose (2.5 windows), one late row is normal, three missed ones is not.
     */
    const val GAP_SECONDS = 600L
}

/**
 * Selectable history windows. At one row every [PipelineTiming.WINDOW_SECONDS] a day is
 * ~360 rows and a week is ~2500, still one query each, but long ranges are downsampled
 * before they are drawn (see `ChartMath.downsample`).
 */
enum class TimeRange(val label: String, val seconds: Long) {
    LAST_HOUR("1h", 60 * 60),
    LAST_6_HOURS("6h", 6 * 60 * 60),
    LAST_24_HOURS("24h", 24 * 60 * 60),
    LAST_3_DAYS("3d", 3 * 24 * 60 * 60),
    LAST_7_DAYS("7d", 7 * 24 * 60 * 60);

    /** Start/end unix timestamps (seconds) for this range ending at [nowSeconds]. */
    fun bounds(nowSeconds: Long): LongRange = (nowSeconds - seconds)..nowSeconds

    /** Rows this range should contain if the pipeline ran without interruption. */
    val expectedWindows: Int
        get() = (seconds / PipelineTiming.WINDOW_SECONDS).toInt().coerceAtLeast(1)

    /** True when the span is wide enough that "HH:mm" alone is ambiguous. */
    val spansMultipleDays: Boolean
        get() = seconds > 24 * 60 * 60
}
