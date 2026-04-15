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
data class SimData(
    val iccid: String?,
    val phoneNumber: String?,
    val carrierName: String?,
    val slotIndex: Int,
    val imei: String?
)

@Serializable
data class RegistrationRequest(
    val deviceName: String,
    val hardwareId: String,
    val groupId: Long,
    val simCards: List<SimData>
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
            val hardwareId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "UNKNOWN_${System.currentTimeMillis()}"
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
            var activeSubs = emptyList<android.telephony.SubscriptionInfo>()
            
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                activeSubs = subscriptionManager.activeSubscriptionInfoList ?: emptyList()
            }

            val simsData = mutableListOf<SimData>()
            val simsLocal = mutableListOf<SimRegistration>()
            val manualPhonesList = manualPhoneNumbers.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            if (activeSubs.isEmpty()) {
                val override = manualPhoneNumbers.split(",").firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                simsData.add(SimData(baseImei, override, null, 0, baseImei))
                simsLocal.add(SimRegistration(baseImei, override, -1))
            } else {
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

                    simsData.add(SimData(iccid, phone, sub.carrierName?.toString(), sub.simSlotIndex, baseImei))
                    simsLocal.add(SimRegistration(iccid, phone, sub.subscriptionId))
                }
            }

            val resp = performRegistrationRequest(backendUrl, deviceName, groupId, hardwareId, simsData)
            
            prefs.setBackendUrl(backendUrl.trimEnd('/'))
            prefs.setDeviceName(deviceName)
            prefs.setRegistrationResult(resp.deviceId, resp.token, simsLocal, groupName, groupId)

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
        hardwareId: String,
        simCards: List<SimData>
    ): RegistrationResponse {
        val url = "${backendUrl.trimEnd('/')}/api/devices/register"
        val reqBody = json.encodeToString(
            RegistrationRequest.serializer(),
            RegistrationRequest(deviceName, hardwareId, groupId, simCards)
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(url).post(reqBody).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${resp.message}")
            return json.decodeFromString(resp.body?.string() ?: "")
        }
    }
}
