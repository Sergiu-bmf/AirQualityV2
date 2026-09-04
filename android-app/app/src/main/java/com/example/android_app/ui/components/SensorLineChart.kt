package com.example.android_app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.android_app.data.PipelineTiming
import kotlin.math.abs

/** Ceiling on drawn samples. A phone is ~1000px wide; more points than this is detail no one can see. */
private const val MAX_PLOT_POINTS = 220

/**
 * A single-series line chart drawn straight onto a Compose [Canvas] , no charting
 * library.
 *
 * Three things it does that a naive line chart doesn't:
 *  - **Breaks the line across outages.** Consecutive rows more than
 *    [PipelineTiming.GAP_SECONDS] apart are drawn as separate segments, so a night when
 *    the laptop was asleep reads as missing rather than as a slow linear drift.
 *  - **Shares its time axis** with every other chart on screen ([axisStart]/[axisEnd]
 *    are the queried range, not this series' own extent), so a metric that only started
 *    reporting halfway through lines up with the others instead of being stretched.
 *  - **Scrubs.** Touching or dragging snaps a cursor to the nearest real reading and
 *    reports its value and timestamp in the header.
 */
@Composable
fun SensorLineChart(
    metric: SensorMetric,
    points: List<ChartPoint>,
    axisStart: Long,
    axisEnd: Long,
    modifier: Modifier = Modifier,
    multiDayAxis: Boolean = false,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            // Reset the cursor whenever the underlying series changes (refresh, range
            // switch) , keeping an index into a replaced list would point at nothing.
            var cursor by remember(points) { mutableStateOf<Int?>(null) }

            val rendered = remember(points) {
                ChartMath.renderSegments(points, PipelineTiming.GAP_SECONDS, MAX_PLOT_POINTS)
            }
            val plotted = remember(rendered) { rendered.flatten() }
            val stats = remember(points) { ChartMath.statsOf(points) }
            val cursorPoint = cursor?.let { plotted.getOrNull(it) }

            ChartHeader(metric = metric, stats = stats, cursorPoint = cursorPoint, multiDayAxis = multiDayAxis)

            if (points.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No readings in this range",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            // Bounds come from the *full* series, not the downsampled one, so a spike
            // that averaging smoothed away still sets the scale it deserves.
            val bounds = ChartMath.valueBounds(points.map { it.value })
            val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
            val thresholdColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            val cursorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            // An off-scale reference line would be pinned to the frame edge and read as
            // "we're right at the limit", so it is simply not drawn , the stats strip
            // still states the number.
            val drawableThreshold = metric.threshold?.takeIf { it in bounds }

            val moveCursor: (Float, Float) -> Unit = { x, width ->
                val at = ChartMath.timestampAt(x / width, axisStart, axisEnd)
                cursor = ChartMath.nearestIndex(plotted, at).takeIf { it >= 0 }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .padding(top = 12.dp, bottom = 4.dp)
                    .pointerInput(plotted, axisStart, axisEnd) {
                        detectTapGestures(
                            onTap = { offset -> moveCursor(offset.x, size.width.toFloat()) },
                        )
                    }
                    .pointerInput(plotted, axisStart, axisEnd) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> moveCursor(offset.x, size.width.toFloat()) },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                moveCursor(change.position.x, size.width.toFloat())
                            },
                        )
                    },
            ) {
                fun xOf(timestamp: Long) = size.width * ChartMath.xFraction(timestamp, axisStart, axisEnd)
                fun yOf(value: Float) = size.height * (1f - ChartMath.yFraction(value, bounds))

                listOf(0f, 0.5f, 1f).forEach { fraction ->
                    val y = size.height * (1f - fraction)
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }

                if (drawableThreshold != null) {
                    val y = yOf(drawableThreshold)
                    drawLine(
                        color = thresholdColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                    )
                }

                rendered.forEach { segment ->
                    val offsets = segment.map { Offset(xOf(it.timestamp), yOf(it.value)) }
                    if (offsets.isEmpty()) return@forEach

                    val fill = Path().apply {
                        moveTo(offsets.first().x, size.height)
                        offsets.forEach { lineTo(it.x, it.y) }
                        lineTo(offsets.last().x, size.height)
                        close()
                    }
                    drawPath(
                        path = fill,
                        brush = Brush.verticalGradient(
                            listOf(metric.color.copy(alpha = 0.28f), Color.Transparent),
                        ),
                    )

                    if (offsets.size == 1) {
                        // An isolated reading has no line to draw , mark the point.
                        drawCircle(color = metric.color, radius = 5f, center = offsets.first())
                    } else {
                        val line = Path().apply {
                            moveTo(offsets.first().x, offsets.first().y)
                            offsets.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(path = line, color = metric.color, style = Stroke(width = 3f))
                    }
                }

                plotted.lastOrNull()?.let {
                    drawCircle(metric.color, radius = 5f, center = Offset(xOf(it.timestamp), yOf(it.value)))
                }

                cursorPoint?.let { point ->
                    val x = xOf(point.timestamp)
                    drawLine(cursorColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                    drawCircle(Color.White, radius = 8f, center = Offset(x, yOf(point.value)))
                    drawCircle(metric.color, radius = 6f, center = Offset(x, yOf(point.value)))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ChartCaption(formatAxis(axisStart, multiDayAxis))
                ChartCaption(
                    if (rendered.size > 1) "${rendered.size - 1} gap${if (rendered.size > 2) "s" else ""}" else "",
                )
                ChartCaption(formatAxis(axisEnd, multiDayAxis))
            }

            if (stats != null) {
                StatsStrip(metric = metric, stats = stats, sampleCount = points.size)
            }
        }
    }
}

@Composable
private fun ChartHeader(
    metric: SensorMetric,
    stats: SeriesStats?,
    cursorPoint: ChartPoint?,
    multiDayAxis: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = metric.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = cursorPoint
                    ?.let { "at ${formatAxis(it.timestamp, multiDayAxis)}" }
                    ?: metric.sensor,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = metric.renderWithUnit(cursorPoint?.value ?: stats?.latest),
            style = MaterialTheme.typography.titleMedium,
            color = metric.color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Min / average / max over the whole range, the net change, and the reference line. */
@Composable
private fun StatsStrip(metric: SensorMetric, stats: SeriesStats, sampleCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StatCell("min", metric.render(stats.min))
        StatCell("avg", metric.render(stats.average))
        StatCell("max", metric.render(stats.max))
        StatCell(
            label = "change",
            // Below the display precision the sign is noise, so it reads as flat.
            value = if (abs(stats.delta) < 0.05f) "flat" else {
                (if (stats.delta > 0) "+" else "−") + metric.render(abs(stats.delta))
            },
        )
        StatCell(
            label = metric.threshold?.let { metric.thresholdLabel } ?: "rows",
            value = metric.threshold?.let { metric.render(it) } ?: "$sampleCount",
        )
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ChartCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
