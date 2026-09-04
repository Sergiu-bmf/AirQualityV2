package com.example.android_app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Notification settings as stored by the Lambda's `/prefs` route.
 *
 * These do not reach the phone , they reach the *pipeline*, which is what watches the
 * flame sensor. The app is only the place you edit them.
 */
@Serializable
data class NotificationPrefs(
    @SerialName("device_id") val deviceId: String = "",
    /**
     * False until someone has actually saved settings. It is what separates "nobody has
     * ever chosen" from "chose to receive nothing" , both have an empty [channels] list,
     * and they must mean opposite things: the first prompts on launch and lets the
     * pipeline fall back to its local config, the second stays silent forever.
     *
     * Defaults to true so that a response from an older Lambda, which omits the field,
     * is never mistaken for an unconfigured device and used to nag someone.
     */
    val configured: Boolean = true,
    val channels: List<String> = emptyList(),
    val email: String? = null,
    /**
     * "none", "pending" or "confirmed". SNS will not deliver a single email until the
     * address clicks the link AWS sends it, and a pending subscription is indistinguishable
     * from a working one until the moment you actually need it , so this is surfaced in
     * the UI rather than hidden.
     */
    @SerialName("email_status") val emailStatus: String = "none",
) {
    val emailEnabled: Boolean get() = CHANNEL_EMAIL in channels

    companion object {
        /** A list rather than a flag, so a second channel can be added without a reshape. */
        const val CHANNEL_EMAIL = "email"
    }
}

/** Body of a `POST /prefs`. Sent whole , the route replaces rather than merges. */
@Serializable
data class NotificationPrefsRequest(
    val channels: List<String>,
    val email: String?,
)
