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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_BACKEND_URL  = stringPreferencesKey("backend_url")
        val KEY_DEVICE_TOKEN = stringPreferencesKey("device_token")
        val KEY_DEVICE_NAME  = stringPreferencesKey("device_name")
        val KEY_DEVICE_ID    = longPreferencesKey("device_id")
        val KEY_GROUP_ID     = longPreferencesKey("group_id")
        val KEY_GROUP_NAME   = stringPreferencesKey("group_name")
    }

    suspend fun getBackendUrl(): String? =
        context.dataStore.data.map { it[KEY_BACKEND_URL] }.first()

    suspend fun setBackendUrl(url: String) =
        context.dataStore.edit { it[KEY_BACKEND_URL] = url }

    suspend fun getDeviceToken(): String? =
        context.dataStore.data.map { it[KEY_DEVICE_TOKEN] }.first()

    suspend fun setDeviceToken(token: String) =
        context.dataStore.edit { it[KEY_DEVICE_TOKEN] = token }

    suspend fun getDeviceName(): String? =
        context.dataStore.data.map { it[KEY_DEVICE_NAME] }.first()

    suspend fun setDeviceName(name: String) =
        context.dataStore.edit { it[KEY_DEVICE_NAME] = name }

    suspend fun getDeviceId(): Long? =
        context.dataStore.data.map { it[KEY_DEVICE_ID] }.first()

    suspend fun setRegistrationResult(deviceId: Long, token: String, groupName: String, groupId: Long) =
        context.dataStore.edit {
            it[KEY_DEVICE_ID]    = deviceId
            it[KEY_DEVICE_TOKEN] = token
            it[KEY_GROUP_NAME]   = groupName
            it[KEY_GROUP_ID]     = groupId
        }

    suspend fun getGroupName(): String? =
        context.dataStore.data.map { it[KEY_GROUP_NAME] }.first()

    fun settingsFlow() = context.dataStore.data.map {
        Triple(it[KEY_BACKEND_URL], it[KEY_DEVICE_TOKEN], it[KEY_DEVICE_NAME])
    }

    fun registrationFlow() = context.dataStore.data.map {
        RegistrationState(
            backendUrl  = it[KEY_BACKEND_URL],
            deviceName  = it[KEY_DEVICE_NAME],
            deviceToken = it[KEY_DEVICE_TOKEN],
            deviceId    = it[KEY_DEVICE_ID],
            groupName   = it[KEY_GROUP_NAME],
            groupId     = it[KEY_GROUP_ID]
        )
    }
}

data class RegistrationState(
    val backendUrl: String?,
    val deviceName: String?,
    val deviceToken: String?,
    val deviceId: Long?,
    val groupName: String?,
    val groupId: Long?
) {
    val isRegistered: Boolean get() = deviceToken != null && deviceId != null
}
