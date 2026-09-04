package com.example.android_app.ui.components

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Formatting helpers shared by every card on the status screen.
 *
 * These use [SimpleDateFormat] rather than java.time because minSdk is 24 and the
 * project has no core library desugaring enabled.
 */

/** A unix timestamp as a local wall clock time, e.g. "14:05". */
internal fun formatClock(epochSeconds: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))

/** A unix timestamp with its date, for ranges where the time alone is ambiguous. */
internal fun formatStamp(epochSeconds: Long): String =
    SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))

/** Picks [formatClock] or [formatStamp] depending on how wide the plotted span is. */
internal fun formatAxis(epochSeconds: Long, multiDay: Boolean): String =
    if (multiDay) formatStamp(epochSeconds) else formatClock(epochSeconds)

/** A duration as a compact "2h 15m" / "45m" / "30s". */
internal fun formatDuration(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}m"
    val hours = minutes / 60
    val remainder = minutes % 60
    if (hours < 24) return if (remainder == 0L) "${hours}h" else "${hours}h ${remainder}m"
    val days = hours / 24
    val leftoverHours = hours % 24
    return if (leftoverHours == 0L) "${days}d" else "${days}d ${leftoverHours}h"
}

/** How long ago something happened, e.g. "4m ago". Future times read as "just now". */
internal fun formatAge(epochSeconds: Long, nowSeconds: Long): String {
    val age = nowSeconds - epochSeconds
    return if (age <= 0) "just now" else "${formatDuration(age)} ago"
}
