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

    fun settingsFlow() = context.dataStore.data.map {
        Triple(it[KEY_BACKEND_URL], it[KEY_DEVICE_TOKEN], it[KEY_DEVICE_NAME])
    }
}
