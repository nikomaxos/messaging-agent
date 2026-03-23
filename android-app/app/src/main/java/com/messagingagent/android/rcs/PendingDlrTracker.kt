package com.messagingagent.android.rcs

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.CopyOnWriteArrayList

@Serializable
data class PendingDlr(
    val correlationId: String,
    val destinationAddress: String,
    val initialMaxId: Long,
    val deviceToken: String,
    val bugleMessageId: Long = 0,  // Exact bugle_db _id of the sent message (0 = not captured yet)
    val dlrDelayMinSec: Int = 2,
    val dlrDelayMaxSec: Int = 5,
    val addedAt: Long = System.currentTimeMillis()
)

@Singleton
class PendingDlrTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("dlr_tracker_prefs", Context.MODE_PRIVATE)
    private val pendingList = CopyOnWriteArrayList<PendingDlr>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        try {
            val savedJson = prefs.getString("pending_dlrs", "[]") ?: "[]"
            val list = json.decodeFromString<List<PendingDlr>>(savedJson)
            pendingList.clear()
            pendingList.addAll(list)
            Timber.i("PendingDlrTracker: Loaded ${pendingList.size} pending DLRs from SharedPreferences.")
        } catch (e: Exception) {
            Timber.e("Failed to load Pending DLRs from prefs: ${e.message}")
        }
    }

    private fun saveToPrefs() {
        try {
            val listJson = json.encodeToString(pendingList.toList())
            prefs.edit().putString("pending_dlrs", listJson).apply()
        } catch (e: Exception) {
            Timber.e("Failed to save Pending DLRs to prefs: ${e.message}")
        }
    }

    fun addPending(dlr: PendingDlr) {
        pendingList.add(dlr)
        saveToPrefs()
    }

    fun getPendingDlrs(): List<PendingDlr> {
        return pendingList.toList()
    }

    fun updateBugleMessageId(correlationId: String, bugleId: Long) {
        val idx = pendingList.indexOfFirst { it.correlationId == correlationId }
        if (idx >= 0) {
            val old = pendingList[idx]
            pendingList[idx] = old.copy(bugleMessageId = bugleId)
            saveToPrefs()
            Timber.i("PendingDlrTracker: Updated bugleMessageId=$bugleId for $correlationId")
        }
    }

    fun removeResolved(resolved: List<PendingDlr>) {
        if (resolved.isNotEmpty()) {
            pendingList.removeAll(resolved)
            saveToPrefs()
        }
    }

    /**
     * Check if any pending DLR already has the given bugle_db _id.
     * Used to prevent duplicate _id assignments which cause false deliveries.
     */
    fun hasPendingWithBugleId(bugleId: Long): Boolean {
        return bugleId > 0 && pendingList.any { it.bugleMessageId == bugleId }
    }
}
