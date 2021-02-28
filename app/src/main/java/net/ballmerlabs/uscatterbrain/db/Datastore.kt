package net.ballmerlabs.uscatterbrain.db

import androidx.room.Database
import androidx.room.RoomDatabase
import net.ballmerlabs.uscatterbrain.db.entities.*

@Database(entities = [HashlessScatterMessage::class, KeylessIdentity::class, Hashes::class, MessageHashCrossRef::class, Keys::class, ClientApp::class], version = 2, exportSchema = false)
abstract class Datastore : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun scatterMessageDao(): ScatterMessageDao
}