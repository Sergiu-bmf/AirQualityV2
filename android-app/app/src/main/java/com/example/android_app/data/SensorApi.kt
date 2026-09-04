package com.example.android_app.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The Lambda Function URL's two routes. Auth is a shared-secret `key` query param —
 * see the note in CLAUDE.md: this is not real authentication.
 */
interface SensorApi {

    /** Newest single window for a device. Returns 404 when the table has no rows yet. */
    @GET("latest")
    suspend fun latest(
        @Query("key") key: String,
        @Query("device_id") deviceId: String,
    ): SensorReading

    /** All windows between two unix timestamps (seconds), ascending by timestamp. */
    @GET("history")
    suspend fun history(
        @Query("key") key: String,
        @Query("device_id") deviceId: String,
        @Query("start") start: Long,
        @Query("end") end: Long,
    ): HistoryResponse

    /** Current notification settings. Never 404s — unset settings are an empty result. */
    @GET("prefs")
    suspend fun prefs(
        @Query("key") key: String,
        @Query("device_id") deviceId: String,
    ): NotificationPrefs

    /**
     * Replaces the notification settings and returns what was stored. The Lambda refuses
     * this route outright when its SHARED_SECRET is unset, so a misconfigured deployment
     * fails loudly here instead of quietly accepting writes from anyone.
     */
    @POST("prefs")
    suspend fun savePrefs(
        @Query("key") key: String,
        @Query("device_id") deviceId: String,
        @Body body: NotificationPrefsRequest,
    ): NotificationPrefs
}
