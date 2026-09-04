package com.example.android_app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.android_app.data.SensorReading
import com.example.android_app.data.TrafficLight
import com.example.android_app.data.trafficLight

/**
 * A one-strip overview of the whole range: every stored window painted at its position
 * in time, coloured by the `status` the pipeline computed. Blank stretches are windows
 * that were never written, a device outage, which is information the line charts can
 * only imply.
 *
 * This reads the precomputed `status` rather than re-deriving it from `alerts`, matching
 * the rest of the app.
 */
@Composable
fun StatusTimeline(
    readings: List<SensorReading>,
    axisStart: Long,
    axisEnd: Long,
    modifier: Modifier = Modifier,
    multiDayAxis: Boolean = false,
) {
    val counts = TrafficLight.entries.associateWith { light ->
        readings.count { it.trafficLight == light }
    }
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)

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
                    text = "Status over time",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${readings.size} windows",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .padding(top = 10.dp),
            ) {
                val radius = CornerRadius(6f, 6f)
                drawRoundRect(color = trackColor, cornerRadius = radius, size = size)

                readings.forEach { reading ->
                    val from = size.width * ChartMath.xFraction(reading.windowStart, axisStart, axisEnd)
                    val to = size.width * ChartMath.xFraction(reading.windowEnd, axisStart, axisEnd)
                    // A window is ~60s of a range that may be a week wide, so it can
                    // round to sub-pixel width; floor it at 2px to stay visible.
                    val width = (to - from).coerceAtLeast(2f)
                    drawRect(
                        color = reading.trafficLight.color,
                        topLeft = Offset(from.coerceAtMost(size.width - width), 0f),
                        size = Size(width, size.height),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatAxis(axisStart, multiDayAxis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatAxis(axisEnd, multiDayAxis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LegendEntry(TrafficLight.GREEN.color, "clear", counts[TrafficLight.GREEN] ?: 0)
                LegendEntry(TrafficLight.YELLOW.color, "warning", counts[TrafficLight.YELLOW] ?: 0)
                LegendEntry(TrafficLight.RED.color, "flame", counts[TrafficLight.RED] ?: 0)
                val unknown = counts[TrafficLight.UNKNOWN] ?: 0
                if (unknown > 0) LegendEntry(TrafficLight.UNKNOWN.color, "unknown", unknown)
            }
        }
    }
}

@Composable
private fun LegendEntry(color: Color, label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .size(9.dp)
                .background(color = color, shape = CircleShape),
        ) {}
        Text(
            text = " $count $label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
