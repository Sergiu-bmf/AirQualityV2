package com.example.android_app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.android_app.data.HistoryDiagnostics
import com.example.android_app.data.SensorReading
import com.example.android_app.data.SensorRepository
import com.example.android_app.data.TimeRange
import com.example.android_app.data.diagnose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatusUiState(
    val isLoading: Boolean = false,
    val isConfigured: Boolean = true,
    val latest: SensorReading? = null,
    val history: List<SensorReading> = emptyList(),
    val range: TimeRange = TimeRange.LAST_6_HOURS,
    val diagnostics: HistoryDiagnostics? = null,
    /** The Lambda capped the result set , the oldest part of the range is missing. */
    val isTruncated: Boolean = false,
    val errorMessage: String? = null,
    val lastRefreshedAt: Long? = null,
    /**
     * The clock reading the current data was requested against. Every chart and the
     * status ribbon share this as their right-hand edge, so a series that stopped early
     * shows the silence instead of stretching to fill the width.
     */
    val queriedAt: Long = 0L,
) {
    /** The table exists and answered, but this device has never written a row. */
    val isEmpty: Boolean
        get() = !isLoading && errorMessage == null && latest == null && history.isEmpty()

    val axisEnd: Long get() = queriedAt
    val axisStart: Long get() = queriedAt - range.seconds
}

class StatusViewModel(
    private val repository: SensorRepository = SensorRepository(),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        StatusUiState(
            isConfigured = SensorRepository.isConfigured,
            queriedAt = nowSeconds(),
        ),
    )
    val uiState: StateFlow<StatusUiState> = _uiState.asStateFlow()

    private var inFlight: Job? = null

    init {
        if (SensorRepository.isConfigured) refresh()
    }

    fun selectRange(range: TimeRange) {
        if (range == _uiState.value.range) return
        _uiState.update { it.copy(range = range) }
        refresh()
    }

    fun refresh() {
        if (!SensorRepository.isConfigured) return
        // Cancel rather than queue: a rapid range switch should not leave a stale
        // response landing after the newer one.
        inFlight?.cancel()
        inFlight = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val now = nowSeconds()
            val range = _uiState.value.range
            try {
                val snapshot = repository.snapshot(range, now)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        latest = snapshot.latest,
                        history = snapshot.history,
                        diagnostics = diagnose(snapshot.history, range),
                        isTruncated = snapshot.truncated,
                        lastRefreshedAt = now,
                        queriedAt = now,
                    )
                }
            } catch (cancellation: CancellationException) {
                // A newer refresh superseded this one , leave the spinner to that job.
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Something went wrong.",
                    )
                }
            }
        }
    }
}
