package com.example.android_app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.android_app.data.TimeRange
import com.example.android_app.ui.components.AlertHistoryCard
import com.example.android_app.ui.components.DiagnosticsCard
import com.example.android_app.ui.components.NotificationOnboardingDialog
import com.example.android_app.ui.components.NotificationSettingsSheet
import com.example.android_app.ui.components.SensorLineChart
import com.example.android_app.ui.components.SensorMetric
import com.example.android_app.ui.components.StatusCard
import com.example.android_app.ui.components.StatusTimeline
import com.example.android_app.ui.components.formatClock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(viewModel: StatusViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showNotificationSettings by rememberSaveable { mutableStateOf(false) }

    // Hoisted so the first-launch prompt and the Alerts sheet share one instance, and the
    // address typed into the prompt is still there if it is reopened from the sheet.
    val notificationViewModel: NotificationViewModel = viewModel()
    val notificationState by notificationViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { notificationViewModel.checkOnboarding() }

    if (notificationState.showOnboarding) {
        NotificationOnboardingDialog(
            isSaving = notificationState.isSaving,
            errorMessage = notificationState.errorMessage,
            onEnable = { notificationViewModel.enableFromOnboarding(it) },
            onDecline = notificationViewModel::declineOnboarding,
        )
    }

    if (showNotificationSettings) {
        NotificationSettingsSheet(
            onDismiss = { showNotificationSettings = false },
            viewModel = notificationViewModel,
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Air Quality") },
                actions = {
                    state.lastRefreshedAt?.let {
                        Text(
                            text = "updated ${formatClock(it)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    TextButton(
                        onClick = { showNotificationSettings = true },
                        enabled = state.isConfigured,
                    ) {
                        Text("Alerts")
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!state.isConfigured) {
                    item { SetupCard() }
                    return@LazyColumn
                }

                item {
                    RangeSelector(
                        selected = state.range,
                        onSelect = viewModel::selectRange,
                    )
                }

                state.errorMessage?.let { message ->
                    item { ErrorCard(message = message, onRetry = viewModel::refresh) }
                }

                state.latest?.let { latest ->
                    item { StatusCard(reading = latest, nowSeconds = state.queriedAt) }
                }

                if (state.isEmpty) {
                    item { InfoCard("No readings stored yet. Start pipeline/sensor_pipeline.py and wait for the first averaging window to finish.") }
                }

                if (state.isTruncated) {
                    item {
                        InfoCard("The Lambda capped this query, the oldest part of the ${state.range.label} range isn't shown.")
                    }
                }

                if (state.history.isNotEmpty()) {
                    item {
                        StatusTimeline(
                            readings = state.history,
                            axisStart = state.axisStart,
                            axisEnd = state.axisEnd,
                            multiDayAxis = state.range.spansMultipleDays,
                        )
                    }

                    state.diagnostics?.let { diagnostics ->
                        item { DiagnosticsCard(diagnostics) }
                    }

                    item {
                        AlertHistoryCard(
                            readings = state.history,
                            nowSeconds = state.queriedAt,
                            multiDayAxis = state.range.spansMultipleDays,
                        )
                    }
                }

                // One chart per catalogued metric. Sound and gas are nullable in the
                // stored rows, so their series can legitimately come back empty; those
                // charts are dropped rather than drawn blank.
                SensorMetric.entries.forEach { metric ->
                    val points = metric.series(state.history)
                    if (points.isEmpty()) return@forEach
                    item(key = metric.name) {
                        SensorLineChart(
                            metric = metric,
                            points = points,
                            axisStart = state.axisStart,
                            axisEnd = state.axisEnd,
                            multiDayAxis = state.range.spansMultipleDays,
                        )
                    }
                }

                if (state.history.isEmpty() && state.latest != null && state.errorMessage == null && !state.isLoading) {
                    item {
                        InfoCard("Nothing recorded in the last ${state.range.label}, try a wider range.")
                    }
                }

                if (state.isLoading && state.history.isEmpty() && state.latest == null) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSelector(selected: TimeRange, onSelect: (TimeRange) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TimeRange.entries.forEach { range ->
            FilterChip(
                selected = range == selected,
                onClick = { onSelect(range) },
                label = { Text(range.label) },
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun InfoCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** Shown when local.properties has no Function URL, instead of a confusing network error. */
@Composable
private fun SetupCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Not configured yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add your Lambda Function URL and shared secret to android-app/local.properties, then rebuild:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "sensor.api.baseUrl=https://<id>.lambda-url.eu-central-1.on.aws/\n" +
                    "sensor.api.key=<SHARED_SECRET>\n" +
                    "sensor.deviceId=arduino-01",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
