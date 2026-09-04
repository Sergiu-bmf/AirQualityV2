package com.example.android_app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.android_app.data.SensorReading
import com.example.android_app.data.TrafficLight
import com.example.android_app.data.trafficLight

/** Cap the list so a bad afternoon doesn't produce an endlessly scrolling card. */
private const val MAX_EVENTS = 12

/**
 * Every window in the range whose `status` wasn't green, newest first, with the
 * pipeline's own `alerts` strings.
 *
 * The charts show *that* a threshold was crossed; this shows *which* one and by how
 * much, because the alert strings already carry the measured value and the threshold
 * it exceeded ("Temperature high: 29.4C (threshold 28.0C)").
 */
@Composable
fun AlertHistoryCard(
    readings: List<SensorReading>,
    nowSeconds: Long,
    modifier: Modifier = Modifier,
    multiDayAxis: Boolean = false,
) {
    val events = readings
        .filter { it.trafficLight != TrafficLight.GREEN }
        .sortedByDescending { it.timestamp }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Alert history",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (events.isEmpty()) "none" else "${events.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (events.isEmpty()) {
                Text(
                    text = "Every stored window in this range came back clear.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                return@Column
            }

            events.take(MAX_EVENTS).forEach { reading ->
                AlertRow(reading = reading, nowSeconds = nowSeconds, multiDayAxis = multiDayAxis)
            }

            if (events.size > MAX_EVENTS) {
                Text(
                    text = "+ ${events.size - MAX_EVENTS} older in this range",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun AlertRow(reading: SensorReading, nowSeconds: Long, multiDayAxis: Boolean) {
    val light = reading.trafficLight
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier
                .padding(top = 5.dp, end = 10.dp)
                .size(9.dp)
                .background(color = light.color, shape = CircleShape),
        ) {}
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "${formatAxis(reading.timestamp, multiDayAxis)} · ${formatAge(reading.timestamp, nowSeconds)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A red window always carries the flame alert string from the pipeline, but
            // fall back rather than render an empty row for a window that has no alert
            // strings, either a future status arriving without one, or a row written
            // before the pipeline stored a status at all.
            val lines = reading.alerts.ifEmpty {
                listOf(
                    reading.status
                        ?.let { "Status reported as \"$it\"" }
                        ?: "No status stored for this window.",
                )
            }
            lines.forEach { alert ->
                Text(
                    text = alert,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
