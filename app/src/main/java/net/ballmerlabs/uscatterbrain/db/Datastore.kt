package net.ballmerlabs.uscatterbrain.db

import androidx.room.*
import androidx.room.migration.AutoMigrationSpec
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
        Keys::class,
        ClientApp::class,
        IdentityId::class,
        GlobalHash::class
    ],
    version = 17,
    exportSchema = true,
    autoMigrations = []
)
@TypeConverters(UuidTypeConverter::class)
abstract class Datastore : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun scatterMessageDao(): ScatterMessageDao

    @DeleteColumn(
        tableName = "messages",
        columnName = "from"
    )
    @RenameColumn(
        tableName = "messages",
        fromColumnName = "to",
        toColumnName = "recipient_fingerprint"
    )
    class MigrationSpec9 : AutoMigrationSpec
}