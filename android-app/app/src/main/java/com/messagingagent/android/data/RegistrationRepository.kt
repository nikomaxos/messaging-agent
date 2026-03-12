package com.messagingagent.android.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class GroupSummary(val id: Long, val name: String, val description: String?)

@Serializable
data class RegistrationRequest(
    val deviceName: String,
    val imei: String?,
    val groupId: Long
)

@Serializable
data class RegistrationResponse(
    val deviceId: Long,
    val token: String,
    val groupName: String
)

/**
 * Handles device-to-group registration with the backend.
 *
 * Registration flow:
 * 1. Fetch available groups:  GET  {url}/api/devices/register/groups
 * 2. User picks a group in SetupActivity
 * 3. POST {url}/api/devices/register  → receives deviceId + token
 * 4. Store in DataStore via PreferencesRepository
 */
@Singleton
class RegistrationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesRepository
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Fetch available groups from the backend. */
    suspend fun fetchGroups(backendUrl: String): Result<List<GroupSummary>> =
        withContext(Dispatchers.IO) {
            try {
                val url = "${backendUrl.trimEnd('/')}/api/devices/register/groups"
                val request = Request.Builder().url(url).get().build()
                val body = client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext Result.failure(
                        Exception("HTTP ${resp.code}: ${resp.message}")
                    )
                    resp.body?.string() ?: "[]"
                }
                val groups = json.decodeFromString<List<GroupSummary>>(body)
                Result.success(groups)
            } catch (e: Exception) {
                Timber.e(e, "fetchGroups failed")
                Result.failure(e)
            }
        }

    /**
     * Register this device with the backend.
     * On success, stores the received token + group info in DataStore.
     */
    suspend fun registerDevice(
        backendUrl: String,
        deviceName: String,
        groupId: Long,
        imei: String? = null
    ): Result<RegistrationResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "${backendUrl.trimEnd('/')}/api/devices/register"
            val reqBody = json.encodeToString(
                RegistrationRequest.serializer(),
                RegistrationRequest(deviceName, imei, groupId)
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder().url(url).post(reqBody).build()
            val (code, bodyStr) = client.newCall(request).execute().use { resp ->
                resp.code to (resp.body?.string() ?: "")
            }

            if (code !in 200..299) {
                return@withContext Result.failure(Exception("HTTP $code"))
            }

            val response = json.decodeFromString<RegistrationResponse>(bodyStr)
            // Persist result
            prefs.setBackendUrl(backendUrl.trimEnd('/'))
            prefs.setDeviceName(deviceName)
            prefs.setRegistrationResult(response.deviceId, response.token, response.groupName, groupId)

            Timber.i("Device registered: id=${response.deviceId} group=${response.groupName}")
            Result.success(response)
        } catch (e: Exception) {
            Timber.e(e, "registerDevice failed")
            Result.failure(e)
        }
    }
}
