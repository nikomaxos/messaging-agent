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
    val groupId: Long,
    val simIccid: String? = null,
    val phoneNumber: String? = null
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
     * Parse hardware SIMs and register each independently with the backend,
     * merging them into a list of SIM Registrations saved to DataStore.
     */
    suspend fun registerAllSims(
        backendUrl: String,
        deviceName: String,
        groupId: Long,
        groupName: String,
        baseImei: String,
        manualPhoneNumbers: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
            var activeSubs = emptyList<android.telephony.SubscriptionInfo>()
            
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                activeSubs = subscriptionManager.activeSubscriptionInfoList ?: emptyList()
            }

            val sims = mutableListOf<SimRegistration>()

            if (activeSubs.isEmpty()) {
                // Fallback: no SIMs or permission denied
                val override = manualPhoneNumbers.split(",").firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                val resp = performRegistrationRequest(backendUrl, deviceName, groupId, baseImei, null, override)
                sims.add(SimRegistration(resp.deviceId, resp.token, baseImei, override, -1))
            } else {
                val manualPhonesList = manualPhoneNumbers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                for ((index, sub) in activeSubs.withIndex()) {
                    val iccid = sub.iccId ?: "${baseImei}_$index"
                    var phone: String? = null
                    
                    if (index < manualPhonesList.size) {
                        phone = manualPhonesList[index]
                    } else if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_NUMBERS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        try { 
                            phone = sub.number 
                            if (phone.isNullOrBlank()) {
                                val tm = (context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager).createForSubscriptionId(sub.subscriptionId)
                                phone = tm.line1Number
                            }
                        } catch (e: Exception) {}
                    }
                    if (phone.isNullOrBlank()) phone = null

                    val simDeviceName = if (phone.isNullOrBlank()) "$deviceName - SIM${index + 1}" else "$deviceName - SIM${index + 1} ($phone)"
                    val uniqueImei = "${baseImei}_$iccid".take(100)
                    
                    val resp = performRegistrationRequest(backendUrl, simDeviceName, groupId, uniqueImei, iccid, phone)
                    sims.add(SimRegistration(resp.deviceId, resp.token, iccid, phone, sub.subscriptionId))
                }
            }

            prefs.setBackendUrl(backendUrl.trimEnd('/'))
            prefs.setDeviceName(deviceName)
            prefs.setRegistrationResult(sims, groupName, groupId)

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "registerAllSims failed")
            Result.failure(e)
        }
    }

    private fun performRegistrationRequest(
        backendUrl: String,
        deviceName: String,
        groupId: Long,
        imei: String,
        simIccid: String?,
        phoneNumber: String?
    ): RegistrationResponse {
        val url = "${backendUrl.trimEnd('/')}/api/devices/register"
        val reqBody = json.encodeToString(
            RegistrationRequest.serializer(),
            RegistrationRequest(deviceName, imei, groupId, simIccid, phoneNumber)
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(url).post(reqBody).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${resp.message}")
            return json.decodeFromString(resp.body?.string() ?: "")
        }
    }
}
