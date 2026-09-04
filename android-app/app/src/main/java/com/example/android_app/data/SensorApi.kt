package com.example.android_app.data

import retrofit2.http.GET
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
}
