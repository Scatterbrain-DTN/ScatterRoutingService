package net.ballmerlabs.uscatterbrain.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

const val TABLE_NAME = "ClientApp"
class Migrate21: Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE `$TABLE_NAME`")
        db.execSQL("DELETE FROM keys")
        db.execSQL("CREATE TABLE `$TABLE_NAME` (`identityFK` INTEGER, `packageName` TEXT NOT NULL, `packageSignature` TEXT, `isDesktop` INTEGER NOT NULL DEFAULT false, `clientAppID` INTEGER PRIMARY KEY AUTOINCREMENT)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ClientApp_packageName_packageSignature` ON `${TABLE_NAME}` (`packageName`, `packageSignature`)")
    }
}
