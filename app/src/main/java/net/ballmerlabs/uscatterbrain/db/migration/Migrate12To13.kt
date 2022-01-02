package net.ballmerlabs.uscatterbrain.db.migration

import android.annotation.SuppressLint
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import android.util.Log
import androidx.core.database.getBlobOrNull
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migrate12To13 : Migration(12, 13) {
    @SuppressLint("Range")
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE messages RENAME TO tmp_messages")
        database.execSQL("CREATE TABLE messages " +
                "(`body` BLOB, `application` TEXT NOT NULL, `sig` BLOB, `sessionid` " +
                "INTEGER NOT NULL, `extension` TEXT NOT NULL, `filepath` TEXT NOT NULL," +
                " `globalhash` TEXT NOT NULL, `userFilename` TEXT NOT NULL, `mimeType` TEXT NOT NULL, " +
                "`sendDate` INTEGER NOT NULL, `receiveDate` INTEGER NOT NULL, " +
                "`uuid` TEXT NOT NULL DEFAULT '0000-0000-0000-000000000000'," +
                " `fileSize` INTEGER NOT NULL DEFAULT -1, `shareCount` INTEGER NOT NULL DEFAULT 0, " +
                "`packageName` TEXT NOT NULL DEFAULT '', `messageID` INTEGER PRIMARY KEY AUTOINCREMENT)")
        database.execSQL("DROP INDEX `index_messages_filepath`")
        database.execSQL("DROP INDEX `index_messages_globalhash`")
        database.execSQL("DROP INDEX `index_messages_uuid`")
        database.execSQL("CREATE UNIQUE INDEX `index_messages_filepath` ON messages (`filepath`)")
        database.execSQL("CREATE UNIQUE INDEX `index_messages_globalhash` ON messages (`globalhash`)")
        database.execSQL("CREATE UNIQUE INDEX `index_messages_uuid` ON messages (`uuid`)")

        val cursor = database.query("SELECT * FROM tmp_messages")
        while(cursor.moveToNext()) {
            val globalhash = cursor.getBlob(cursor.getColumnIndex("globalhash"))
            val body = cursor.getBlobOrNull(cursor.getColumnIndex("body"))
            val application = cursor.getBlob(cursor.getColumnIndex("application"))
            val sig = cursor.getBlobOrNull(cursor.getColumnIndex("sig"))
            val sessionid = cursor.getInt(cursor.getColumnIndex("sessionid"))
            val extension = cursor.getString(cursor.getColumnIndex("extension"))
            val filepath = cursor.getString(cursor.getColumnIndex("filepath"))
            val userFilename = cursor.getString(cursor.getColumnIndex("userFilename"))
            val mimeType = cursor.getString(cursor.getColumnIndex("mimeType"))
            val sendDate = cursor.getInt(cursor.getColumnIndex("sendDate"))
            val receiveDate = cursor.getInt(cursor.getColumnIndex("receiveDate"))
            val fileSize = cursor.getInt(cursor.getColumnIndex("fileSize"))
            val shareCount = cursor.getInt(cursor.getColumnIndex("shareCount"))
            val packageName = cursor.getString(cursor.getColumnIndex("packageName"))
            val uuid = cursor.getString(cursor.getColumnIndex("uuid"))
            Log.e("debug", "migrating row ${cursor.position}")
            val content = ContentValues()
            content.put("globalhash", Base64.encodeToString(globalhash, Base64.DEFAULT))
            content.put("body", body)
            content.put("application", application)
            content.put("sig", sig)
            content.put("sessionid", sessionid)
            content.put("extension", extension)
            content.put("filepath", filepath)
            content.put("userFilename", userFilename)
            content.put("mimeType", mimeType)
            content.put("sendDate", sendDate)
            content.put("receiveDate", receiveDate)
            content.put("fileSize", fileSize)
            content.put("shareCount", shareCount)
            content.put("packageName", packageName)
            content.put("uuid", uuid)
            database.insert("messages", SQLiteDatabase.CONFLICT_ABORT, content)
        }
        cursor.close()
        database.execSQL("DROP TABLE tmp_messages")

    }
}