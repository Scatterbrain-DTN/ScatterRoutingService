package net.ballmerlabs.uscatterbrain.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase

class Migrate26: Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM bundles")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bundles_childOne ON bundles(childOne)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bundles_childTwo ON bundles(childTwo)")
    }
}