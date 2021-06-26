package net.ballmerlabs.uscatterbrain.db.migration

import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.ballmerlabs.uscatterbrain.db.Datastore.MigrationSpec9

class Migrate9 : Migration(5, 11) {
    private val callback: AutoMigrationSpec = MigrationSpec9()

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE TABLE IF NOT EXISTS `_new_messages` (`messageID` INTEGER PRIMARY KEY AUTOINCREMENT, `body` BLOB, `identity_fingerprint` TEXT, `recipient_fingerprint` TEXT, `application` TEXT NOT NULL, `sig` BLOB, `sessionid` INTEGER NOT NULL, `extension` TEXT NOT NULL, `filepath` TEXT NOT NULL, `globalhash` BLOB NOT NULL, `userFilename` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `sendDate` INTEGER NOT NULL, `receiveDate` INTEGER NOT NULL, `uuid` TEXT NOT NULL DEFAULT '0000-0000-0000-000000000000', `fileSize` INTEGER NOT NULL DEFAULT -1, `shareCount` INTEGER NOT NULL DEFAULT 0, `packageName` TEXT NOT NULL DEFAULT '')")
        database.execSQL("INSERT INTO `_new_messages` (extension,recipient_fingerprint,sendDate,globalhash,messageID,receiveDate,identity_fingerprint,sessionid,mimeType,body,sig,application,filepath,userFilename) SELECT extension,\"to\",sendDate,globalhash,messageID,receiveDate,identity_fingerprint,sessionid,mimeType,body,sig,application,filepath,userFilename FROM `messages`")
        database.execSQL("DROP TABLE `messages`")
        database.execSQL("ALTER TABLE `_new_messages` RENAME TO `messages`")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_filepath` ON `messages` (`filepath`)")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_globalhash` ON `messages` (`globalhash`)")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_uuid` ON `messages` (`uuid`)")
        callback.onPostMigrate(database)
    }
}