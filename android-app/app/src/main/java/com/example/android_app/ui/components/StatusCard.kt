package com.example.android_app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.android_app.data.PipelineTiming
import com.example.android_app.data.SensorReading
import com.example.android_app.data.TrafficLight
import com.example.android_app.data.trafficLight
import java.util.Locale

val TrafficLight.color: Color
    get() = when (this) {
        TrafficLight.GREEN -> Color(0xFF2E7D32)
        TrafficLight.YELLOW -> Color(0xFFF9A825)
        TrafficLight.RED -> Color(0xFFC62828)
        TrafficLight.UNKNOWN -> Color(0xFF616161)
    }

private val TrafficLight.headline: String
    get() = when (this) {
        TrafficLight.GREEN -> "All clear"
        TrafficLight.YELLOW -> "Warning"
        TrafficLight.RED -> "Flame detected"
        TrafficLight.UNKNOWN -> "Unknown status"
    }

/**
 * The traffic light, driven by the `status` field the pipeline already computed, plus
 * the latest value of every metric in the [SensorMetric] catalog and any alert strings
 * that came with the row.
 */
@Composable
fun StatusCard(
    reading: SensorReading,
    nowSeconds: Long,
    modifier: Modifier = Modifier,
) {
    val light = reading.trafficLight
    // Two missed windows is the point where "the pipeline is running" stops being a safe
    // assumption, and a green light on hours-old data would be actively misleading.
    val isStale = nowSeconds - reading.windowEnd > 2 * PipelineTiming.WINDOW_SECONDS

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = light.color.copy(alpha = 0.12f),
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TrafficLight.entries
                        .filter { it != TrafficLight.UNKNOWN }
                        .forEach { lamp ->
                            Column(
                                Modifier
                                    .size(18.dp)
                                    .background(
                                        color = if (lamp == light) lamp.color else lamp.color.copy(alpha = 0.15f),
                                        shape = CircleShape,
                                    ),
                            ) {}
                        }
                }
                Column(Modifier.padding(start = 20.dp)) {
                    Text(
                        text = light.headline,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = light.color,
                    )
                    Text(
                        text = "${formatAge(reading.windowEnd, nowSeconds)} · " +
                            "${reading.sampleCount} samples" +
                            if (reading.rejectedCount > 0) " · ${reading.rejectedCount} rejected" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isStale) {
                Text(
                    text = "Newest window ended ${formatStamp(reading.windowEnd)} , the pipeline may not be running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            if (reading.alerts.isNotEmpty()) {
                Column(Modifier.padding(top = 14.dp)) {
                    reading.alerts.forEach { alert ->
                        Text(
                            text = "• $alert",
                            style = MaterialTheme.typography.bodyMedium,
                            color = light.color,
                        )
                    }
                }
            }

            // Driven by the catalog, so a new sensor shows up here and on the charts
            // from a single enum entry.
            SensorMetric.entries.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { metric ->
                        ValueTile(
                            label = metric.label,
                            value = metric.select(reading)?.let { metric.render(it) } ?: "n/a",
                            unit = metric.unit,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keeps the last row's tiles aligned with the row above when the
                    // catalog size isn't a multiple of three.
                    repeat(3 - row.size) { Column(Modifier.weight(1f)) {} }
                }
            }
        }
    }
}

@Composable
private fun ValueTile(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .width(34.dp)
                    .padding(start = 3.dp, bottom = 3.dp),
            )
        }
    }
}
