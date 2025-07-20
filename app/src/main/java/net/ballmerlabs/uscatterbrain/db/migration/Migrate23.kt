package net.ballmerlabs.uscatterbrain.db.migration
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface

class Migrate23: Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE desktop_clients ADD COLUMN remote_fingerprint BLOB")
        val clients = db.query("SELECT remotekey FROM desktop_clients")
        clients.moveToFirst()
        for(x in 0 until  clients.count) {
            val b = clients.getBlob(0)
            val fingerprint = LibsodiumInterface.fingerprint(b)
            db.execSQL("UPDATE desktop_clients SET remote_fingerprint = ? where remotekey = ?", arrayOf(fingerprint, b))
            clients.moveToNext()
        }

        db.execSQL("CREATE UNIQUE INDEX index_desktop_clients_remote_fingerprint ON desktop_clients(remote_fingerprint)")
    }
}