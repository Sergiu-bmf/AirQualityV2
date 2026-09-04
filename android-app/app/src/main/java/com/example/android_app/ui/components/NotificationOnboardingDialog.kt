package com.example.android_app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Asks for an address for flame alerts, on every launch until one is set.
 *
 * "No thanks" closes it for the session and is saved server-side as an empty channel
 * list, so the pipeline reads it as a deliberate silence rather than an unconfigured
 * device, but it returns next launch. That is intentional: an unconfigured fire alarm
 * should keep saying so rather than being permanently dismissed by one tap.
 *
 * No Android notification permission is involved: the alert is an email, so there is no
 * system notification to grant, and nothing to ask for on Android 13+.
 */
@Composable
fun NotificationOnboardingDialog(
    isSaving: Boolean,
    errorMessage: String?,
    onEnable: (String) -> Unit,
    onDecline: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    val looksValid = email.matches(EMAIL_SHAPE)

    AlertDialog(
        // Not dismissable by tapping outside, "No thanks" is the way past it, so that
        // declining is a recorded answer rather than an accidental tap on the scrim.
        onDismissRequest = {},
        title = { Text("Get alerted about fire?") },
        text = {
            Column {
                Text(
                    "If the flame sensor trips, this can email you. Nothing else notifies " +
                        "you, temperature and air quality stay in the app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    label = { Text("Email address") },
                    singleLine = true,
                    isError = email.isNotEmpty() && !looksValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
                Text(
                    "AWS will email you a confirmation link first, alerts only start once " +
                        "you click it. Check your spam folder.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                errorMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEnable(email) }, enabled = looksValid && !isSaving) {
                Text(if (isSaving) "Saving…" else "Email me")
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline, enabled = !isSaving) { Text("No thanks") }
        },
    )
}

private val EMAIL_SHAPE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
