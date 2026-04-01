package com.messagingagent.android.bot

import android.database.sqlite.SQLiteDatabase

/**
 * Executes natively in a root shell via app_process.
 * Allows using the Android SDK's robust SQLite engine without requiring the sqlite3 binary.
 */
object BugleDbQuery {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("ERROR|Missing cutoff timestamp")
            System.exit(1)
        }
        val cutoff = args[0]
        try {
            val dbPath = "/data/data/com.google.android.apps.messaging/databases/bugle_db"
            val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
            
            // 8, 9 = Error, 11, 12 = Delivered, 13 = Read
            // To ensure we catch delivery reports that arrive minutes or hours late,
            // we look back up to 4 hours instead of restricting by a strict timestamp.
            val lookbackCutoff = System.currentTimeMillis() - (4 * 60 * 60 * 1000L)
            val sql = "SELECT c.participant_normalized_destination, m.message_status, p.text FROM messages m INNER JOIN parts p ON p.message_id = m._id INNER JOIN conversations c ON m.conversation_id = c._id WHERE m.message_status IN (8,9,11,12,13) AND m.timestamp > ?"
            val cursor = db.rawQuery(sql, arrayOf(lookbackCutoff.toString()))
            
            var count = 0
            while (cursor.moveToNext()) {
                val dest = cursor.getString(0) ?: ""
                val status = cursor.getInt(1)
                val text = cursor.getString(2) ?: ""
                // Use java.util.Base64 encoding to make the text completely safe against injection/newlines
                val enc = java.util.Base64.getEncoder().encodeToString(text.toByteArray())
                println("$dest|$status|$enc")
                count++
            }
            cursor.close()
            db.close()
            if (count == 0) {
                println("EMPTY|No new events")
            }
            System.exit(0)
        } catch (e: Exception) {
            println("ERROR|" + e.message)
            System.exit(2)
        }
    }
}
