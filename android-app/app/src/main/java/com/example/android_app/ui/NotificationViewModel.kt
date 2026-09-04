package com.example.android_app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.android_app.data.NotificationPrefs
import com.example.android_app.data.NotificationPrefsRequest
import com.example.android_app.data.SensorRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotificationUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val emailEnabled: Boolean = false,
    val email: String = "",
    val emailStatus: String = "none",
    val errorMessage: String? = null,
    val savedMessage: String? = null,
    /**
      * The prompt asking for an address. Shown on every launch until an email is actually
      * set , declining silences it for the session, not for good, so an unconfigured fire
      * alarm keeps announcing itself rather than being quietly forgotten.
      */
    val showOnboarding: Boolean = false,
) {
    /** Mirrors the Lambda's validation so the button explains itself before a round trip. */
    val validationError: String?
        get() = when {
            emailEnabled && email.isBlank() -> "Enter an email address, or turn email off."
            emailEnabled && !email.matches(EMAIL_SHAPE) -> "That doesn't look like an email address."
            else -> null
        }

    val canSave: Boolean get() = !isSaving && !isLoading && validationError == null

    /** True once an address is stored but AWS is still waiting for the confirmation click. */
    val awaitingConfirmation: Boolean get() = emailEnabled && emailStatus == "pending"

    private companion object {
        val EMAIL_SHAPE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}

/**
 * Edits the notification settings that live server-side.
 *
 * Worth being clear about what these settings do: they are read by the *pipeline* on the
 * laptop, which is the only thing watching the flame sensor. Nothing here makes the phone
 * itself listen for anything.
 */
class NotificationViewModel(
    private val repository: SensorRepository = SensorRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationUiState())
    val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()

    fun load() {
        if (!SensorRepository.isConfigured) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, savedMessage = null) }
            try {
                apply(repository.loadPrefs(), loading = false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = error.message ?: "Couldn't load settings.")
                }
            }
        }
    }

    /**
     * Decide whether to ask for an address, on every launch.
     *
     * The condition is simply "no email is set" , not "never been asked". An address that
     * is stored but still pending confirmation counts as set; the Alerts sheet is where
     * that state is explained, and re-asking for an address already given would be noise.
     *
     * Any failure leaves the prompt hidden: an unreachable Lambda is not a reason to
     * interrogate someone about their email.
     */
    fun checkOnboarding() {
        if (!SensorRepository.isConfigured) return
        viewModelScope.launch {
            try {
                val prefs = repository.loadPrefs()
                apply(prefs, loading = false)
                _uiState.update {
                    it.copy(showOnboarding = !prefs.emailEnabled || prefs.email.isNullOrBlank())
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Silent: the Alerts button in the top bar is always there as the way in.
            }
        }
    }

    /**
     * "No thanks": closes the prompt for this session only , it returns next launch until
     * an address is set. Still recorded server-side as an empty channel list so the
     * pipeline reads it as a deliberate silence rather than an unconfigured device.
     */
    fun declineOnboarding() {
        _uiState.update { it.copy(showOnboarding = false, emailEnabled = false) }
        viewModelScope.launch {
            runCatching { repository.savePrefs(NotificationPrefsRequest(channels = emptyList(), email = null)) }
        }
    }

    fun dismissOnboarding() = _uiState.update { it.copy(showOnboarding = false) }

    fun setEmailEnabled(enabled: Boolean) = _uiState.update { it.copy(emailEnabled = enabled, savedMessage = null) }
    fun setEmail(address: String) = _uiState.update { it.copy(email = address.trim(), savedMessage = null) }

    fun save() {
        val state = _uiState.value
        if (!state.canSave) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, savedMessage = null) }
            try {
                val stored = repository.savePrefs(
                    NotificationPrefsRequest(
                        channels = buildList {
                            if (state.emailEnabled) add(NotificationPrefs.CHANNEL_EMAIL)
                        },
                        email = state.email.ifBlank { null },
                    ),
                )
                // Re-seed from what the server actually stored, not from what was typed ,
                // email_status in particular is only known server-side.
                apply(stored, loading = false)
                _uiState.update {
                    it.copy(
                        savedMessage = if (stored.emailStatus == "pending") {
                            "Saved. Check your inbox and click the AWS confirmation link , " +
                                "no email is delivered until you do."
                        } else {
                            "Saved."
                        },
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, errorMessage = error.message ?: "Couldn't save settings.")
                }
            }
        }
    }

    /** Enable email from the first-launch prompt. Closes it only once the save succeeds. */
    fun enableFromOnboarding(address: String) {
        _uiState.update { it.copy(emailEnabled = true, email = address.trim()) }
        val state = _uiState.value
        if (state.validationError != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                val stored = repository.savePrefs(
                    NotificationPrefsRequest(
                        channels = listOf(NotificationPrefs.CHANNEL_EMAIL),
                        email = state.email,
                    ),
                )
                apply(stored, loading = false)
                _uiState.update { it.copy(showOnboarding = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Stay open with the reason showing , the address they typed is still there.
                _uiState.update {
                    it.copy(isSaving = false, errorMessage = error.message ?: "Couldn't save settings.")
                }
            }
        }
    }

    private fun apply(prefs: NotificationPrefs, loading: Boolean) {
        _uiState.update {
            it.copy(
                isLoading = loading,
                isSaving = false,
                emailEnabled = prefs.emailEnabled,
                email = prefs.email.orEmpty(),
                emailStatus = prefs.emailStatus,
            )
        }
    }
}
