package net.ballmerlabs.uscatterbrain.db

import androidx.room.*
import net.ballmerlabs.uscatterbrain.db.entities.*
import java.util.*


class UuidTypeConverter {
    @TypeConverter
    fun uuidToString(uuid: UUID): String {
        return uuid.toString()
    }

    @TypeConverter
    fun stringToUUID(string: String): UUID {
        return UUID.fromString(string)
    }
}

/**
 * declaration of room database
 */
@Database(
        entities = [
            HashlessScatterMessage::class,
            KeylessIdentity::class,
            Hashes::class,

            MessageHashCrossRef::class,
            Keys::class,
            ClientApp::class
                   ],
        version = 7,
        exportSchema = true,
        autoMigrations = [
            AutoMigration(from = 6, to = 7)
        ]
)
@TypeConverters(UuidTypeConverter::class)
abstract class Datastore : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun scatterMessageDao(): ScatterMessageDao
}