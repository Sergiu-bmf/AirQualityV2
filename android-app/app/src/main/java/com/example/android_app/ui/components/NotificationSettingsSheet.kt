package com.example.android_app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.android_app.ui.NotificationViewModel

/**
 * Editor for the flame-alert notification settings.
 *
 * The copy here does real work. Two things about this feature are surprising enough that
 * leaving them implicit would make a silent failure look like a bug:
 *  - the alerts are sent by the pipeline on the laptop, so nothing arrives while it is
 *    not running;
 *  - an SNS subscription delivers nothing until the address confirms it by email.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsSheet(
    onDismiss: () -> Unit,
    viewModel: NotificationViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Flame alerts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "A flame is the only thing that notifies you. Everything else stays in the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.isLoading) {
                CircularProgressIndicator(Modifier.padding(vertical = 24.dp))
            } else {
                ChannelRow(
                    title = "Email via AWS SNS",
                    subtitle = "Sent the moment a flame is detected. Check your spam folder, " +
                        "AWS mail is frequently filtered.",
                    checked = state.emailEnabled,
                    onCheckedChange = viewModel::setEmailEnabled,
                )
                if (state.emailEnabled) {
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = viewModel::setEmail,
                        label = { Text("Email address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (state.awaitingConfirmation) {
                    Notice(
                        "AWS is waiting for you to confirm this address. Until you click the link " +
                            "it emailed you, no alert will be delivered.",
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }

                state.validationError?.let {
                    Notice(it, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.errorMessage?.let {
                    Notice(it, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                }
                state.savedMessage?.let {
                    Notice(it, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                }

                Button(
                    onClick = viewModel::save,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isSaving) "Saving…" else "Save settings")
                }

                Text(
                    "Alerts are sent by the pipeline on your laptop, nothing arrives while it isn't running.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun Notice(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = content,
            modifier = Modifier.padding(12.dp),
        )
    }
}
