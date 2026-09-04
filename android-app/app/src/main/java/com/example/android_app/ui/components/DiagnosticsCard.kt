package com.example.android_app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.android_app.data.HistoryDiagnostics
import com.example.android_app.data.PipelineTiming
import kotlin.math.roundToInt

/**
 * How complete the data behind the charts actually is.
 *
 * Coverage is measured against the pipeline's fixed cadence (one window every
 * [PipelineTiming.WINDOW_SECONDS]), so a low percentage means the laptop-side pipeline
 * wasn't running, not that the sensors were quiet. The rejected count comes from
 * `is_valid_reading()` upstream: a persistently high share points at a flaky DHT11 or
 * loose wiring rather than at the environment.
 */
@Composable
fun DiagnosticsCard(
    diagnostics: HistoryDiagnostics,
    modifier: Modifier = Modifier,
) {
    val coveragePercent = (diagnostics.coverage * 100).roundToInt()
    val rejectedPercent = (diagnostics.rejectedFraction * 100).roundToInt()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Data coverage",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "$coveragePercent%",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            LinearProgressIndicator(
                progress = { diagnostics.coverage },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )

            Text(
                text = "${diagnostics.windowCount} of ~${diagnostics.expectedWindows} expected windows " +
                    "(one every ${formatDuration(PipelineTiming.WINDOW_SECONDS)})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Metric("Samples", "${diagnostics.sampleCount}")
                Metric(
                    label = "Rejected",
                    value = if (diagnostics.rejectedCount == 0) "0" else "${diagnostics.rejectedCount} ($rejectedPercent%)",
                )
                Metric(
                    label = "Longest gap",
                    // Anything at or under the cadence isn't a gap, it's the schedule.
                    value = if (diagnostics.largestGapSeconds <= PipelineTiming.WINDOW_SECONDS) {
                        "none"
                    } else {
                        formatDuration(diagnostics.largestGapSeconds)
                    },
                )
                Metric("Flame windows", "${diagnostics.flameWindows}")
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
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
