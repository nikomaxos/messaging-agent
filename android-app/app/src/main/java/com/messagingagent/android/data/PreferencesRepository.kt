package com.messagingagent.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")



@Serializable
data class SimRegistration(
    val simIccid: String,
    val phoneNumber: String?,
    val subscriptionId: Int
)

data class RegistrationState(
    val backendUrl: String?,
    val deviceName: String?,
    val groupName: String?,
    val groupId: Long?,
    val deviceId: Long?,
    val deviceToken: String?,
    val sims: List<SimRegistration>
) {
    val isRegistered: Boolean get() = deviceToken != null && deviceId != null
}

@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_BACKEND_URL  = stringPreferencesKey("backend_url")
        val KEY_DEVICE_NAME  = stringPreferencesKey("device_name")
        val KEY_GROUP_ID     = longPreferencesKey("group_id")
        val KEY_GROUP_NAME   = stringPreferencesKey("group_name")
        val KEY_AUTO_REBOOT_ENABLED = booleanPreferencesKey("auto_reboot_enabled")
        val KEY_AUTO_PURGE = stringPreferencesKey("auto_purge_mode")
        val KEY_LAST_PURGED_AT = longPreferencesKey("last_purged_at")
        val KEY_SELF_HEALING_ENABLED = booleanPreferencesKey("self_healing_enabled")
        val KEY_DEVICE_ID = longPreferencesKey("device_id")
        val KEY_DEVICE_TOKEN = stringPreferencesKey("device_token")
        val KEY_SIM_REGISTRATIONS = stringPreferencesKey("sim_registrations")
        val KEY_LAST_CONNECTED_TIME = longPreferencesKey("last_connected_time")
    }

    suspend fun getBackendUrl(): String? =
        context.dataStore.data.map { it[KEY_BACKEND_URL] }.first()

    suspend fun setBackendUrl(url: String) =
        context.dataStore.edit { it[KEY_BACKEND_URL] = url }

    suspend fun getDeviceName(): String? =
        context.dataStore.data.map { it[KEY_DEVICE_NAME] }.first()

    suspend fun setDeviceName(name: String) =
        context.dataStore.edit { it[KEY_DEVICE_NAME] = name }

    suspend fun isAutoRebootEnabled(): Boolean =
        context.dataStore.data.map { it[KEY_AUTO_REBOOT_ENABLED] ?: false }.first()

    suspend fun setAutoRebootEnabled(enabled: Boolean) =
        context.dataStore.edit { it[KEY_AUTO_REBOOT_ENABLED] = enabled }

    suspend fun getAutoPurgeMode(): String =
        context.dataStore.data.map { it[KEY_AUTO_PURGE] ?: "OFF" }.first()

    suspend fun setAutoPurgeMode(mode: String) =
        context.dataStore.edit { it[KEY_AUTO_PURGE] = mode }

    suspend fun getLastPurgedAt(): Long =
        context.dataStore.data.map { it[KEY_LAST_PURGED_AT] ?: 0L }.first()

    suspend fun setLastPurgedAt(timestamp: Long) =
        context.dataStore.edit { it[KEY_LAST_PURGED_AT] = timestamp }

    suspend fun isSelfHealingEnabled(): Boolean =
        context.dataStore.data.map { it[KEY_SELF_HEALING_ENABLED] ?: false }.first()

    suspend fun setSelfHealingEnabled(enabled: Boolean) =
        context.dataStore.edit { it[KEY_SELF_HEALING_ENABLED] = enabled }

    suspend fun setRegistrationResult(deviceId: Long, token: String, sims: List<SimRegistration>, groupName: String, groupId: Long) =
        context.dataStore.edit {
            it[KEY_DEVICE_ID] = deviceId
            it[KEY_DEVICE_TOKEN] = token
            it[KEY_SIM_REGISTRATIONS] = Json.encodeToString(sims)
            it[KEY_GROUP_NAME]   = groupName
            it[KEY_GROUP_ID]     = groupId
        }

    suspend fun getDeviceToken(): String? =
        context.dataStore.data.map { it[KEY_DEVICE_TOKEN] }.first()

    suspend fun getGroupName(): String? =
        context.dataStore.data.map { it[KEY_GROUP_NAME] }.first()

    fun settingsFlow() = context.dataStore.data.map {
        val simsStr = it[KEY_SIM_REGISTRATIONS] ?: "[]"
        val sims = try { Json.decodeFromString<List<SimRegistration>>(simsStr) } catch(e: Exception) { emptyList() }
        Triple(it[KEY_BACKEND_URL], sims, it[KEY_DEVICE_NAME])
    }

    fun registrationFlow() = context.dataStore.data.map {
        val simsStr = it[KEY_SIM_REGISTRATIONS] ?: "[]"
        val sims = try { Json.decodeFromString<List<SimRegistration>>(simsStr) } catch(e: Exception) { emptyList() }
        RegistrationState(
            backendUrl  = it[KEY_BACKEND_URL],
            deviceName  = it[KEY_DEVICE_NAME],
            groupName   = it[KEY_GROUP_NAME],
            groupId     = it[KEY_GROUP_ID],
            deviceId    = it[KEY_DEVICE_ID],
            deviceToken = it[KEY_DEVICE_TOKEN],
            sims        = sims
        )
    }

    suspend fun getLastConnectedTime(): Long =
        context.dataStore.data.map { it[KEY_LAST_CONNECTED_TIME] ?: 0L }.first()

    suspend fun setLastConnectedTime(timestamp: Long) =
        context.dataStore.edit { it[KEY_LAST_CONNECTED_TIME] = timestamp }
}

