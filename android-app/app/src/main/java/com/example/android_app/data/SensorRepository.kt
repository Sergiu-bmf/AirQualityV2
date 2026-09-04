package com.example.android_app.data

import com.example.android_app.BuildConfig
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Everything the status screen needs for one refresh. */
data class SensorSnapshot(
    val latest: SensorReading?,
    val history: List<SensorReading>,
    /** The Lambda hit its item cap and the oldest rows in the range are missing. */
    val truncated: Boolean = false,
)

/** A network/config failure already translated into something worth showing a user. */
class SensorApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Talks to the Lambda Function URL. The app never touches DynamoDB directly, so no AWS
 * credentials exist on the device — the only secret here is the shared `key` param.
 */
class SensorRepository(
    private val api: SensorApi = defaultApi(),
    private val apiKey: String = BuildConfig.SENSOR_API_KEY,
    private val deviceId: String = BuildConfig.SENSOR_DEVICE_ID,
) {

    /**
     * Fetches the latest reading and the history window concurrently.
     *
     * A 404 from `/latest` means "no rows for this device yet", which is a normal empty
     * state rather than a failure — it maps to a null [SensorSnapshot.latest]. Any other
     * error propagates as [SensorApiException] with a message that says what to fix.
     */
    suspend fun snapshot(range: TimeRange, nowSeconds: Long): SensorSnapshot = coroutineScope {
        val bounds = range.bounds(nowSeconds)
        val latest = async { runCatching { api.latest(apiKey, deviceId) } }
        val history = async { runCatching { api.history(apiKey, deviceId, bounds.first, bounds.last) } }

        val latestResult = latest.await()
        val historyResult = history.await()

        val latestReading = latestResult.getOrElse { error ->
            if (error is HttpException && error.code() == 404) null else throw translate(error)
        }
        val historyReadings = historyResult.getOrElse { throw translate(it) }

        SensorSnapshot(
            latest = latestReading,
            // Defensive: the Lambda queries ascending already, but charts depend on it.
            history = historyReadings.items.sortedBy { it.timestamp },
            truncated = historyReadings.truncated,
        )
    }

    /** Reads the stored notification settings. */
    suspend fun loadPrefs(): NotificationPrefs =
        runCatching { api.prefs(apiKey, deviceId) }.getOrElse { throw translate(it) }

    /** Replaces the notification settings, returning what the Lambda actually stored. */
    suspend fun savePrefs(request: NotificationPrefsRequest): NotificationPrefs =
        runCatching { api.savePrefs(apiKey, deviceId, request) }.getOrElse { throw translate(it) }

    private fun translate(error: Throwable): Throwable = when {
        error is HttpException && error.code() == 401 ->
            SensorApiException("Rejected by the Lambda (401). Check sensor.api.key in local.properties matches the SHARED_SECRET env var.", error)
        error is HttpException && error.code() == 404 ->
            SensorApiException("Route not found (404). Check sensor.api.baseUrl points at the Function URL root.", error)
        // 400 and 503 from /prefs carry a specific reason in the body — surface it
        // verbatim, since it names exactly what to fix (a malformed address, an
        // unset SNS_TOPIC_ARN) far better than the status code does.
        error is HttpException && error.code() in setOf(400, 502, 503) ->
            SensorApiException(serverMessage(error) ?: "Lambda returned HTTP ${error.code()}.", error)
        error is HttpException ->
            SensorApiException("Lambda returned HTTP ${error.code()}.", error)
        error is IOException ->
            SensorApiException("Can't reach the Lambda — check your connection and the Function URL.", error)
        else -> error
    }

    /** Pulls the Lambda's own {"error": "..."} out of a failed response, if present. */
    private fun serverMessage(error: HttpException): String? = runCatching {
        val body = error.response()?.errorBody()?.string().orEmpty()
        json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()

    companion object {
        /** True when local.properties hasn't been filled in yet. */
        val isConfigured: Boolean
            get() = BuildConfig.SENSOR_API_BASE_URL.isNotBlank()

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        private fun defaultApi(): SensorApi {
            val client = OkHttpClient.Builder().build()
            return Retrofit.Builder()
                // Retrofit rejects a blank base URL, so unconfigured builds get a
                // placeholder; the UI checks [isConfigured] before ever calling out.
                .baseUrl(BuildConfig.SENSOR_API_BASE_URL.ifBlank { "https://example.invalid/" })
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(SensorApi::class.java)
        }
    }
}
