package net.ballmerlabs.uscatterbrain.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.DeleteTable
import androidx.room.RenameColumn
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import net.ballmerlabs.uscatterbrain.db.entities.ClientApp
import net.ballmerlabs.uscatterbrain.db.entities.GlobalHash
import net.ballmerlabs.uscatterbrain.db.entities.Hashes
import net.ballmerlabs.uscatterbrain.db.entities.HashlessScatterMessage
import net.ballmerlabs.uscatterbrain.db.entities.IdentityDao
import net.ballmerlabs.uscatterbrain.db.entities.IdentityId
import net.ballmerlabs.uscatterbrain.db.entities.KeylessIdentity
import net.ballmerlabs.uscatterbrain.db.entities.Keys
import net.ballmerlabs.uscatterbrain.db.entities.Metrics
import net.ballmerlabs.uscatterbrain.db.entities.ScatterMessageDao
import net.ballmerlabs.uscatterbrain.db.migration.Migrate21
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopClientDao
import net.ballmerlabs.uscatterbrain.network.desktop.entity.DesktopClient
import java.util.UUID


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
        GlobalHash::class,
        Metrics::class,
        DesktopClient::class
    ],
    version = 22,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(
            from = 17,
            to = 18
        ),
        AutoMigration(
            from = 18,
            to = 19
        ),
        AutoMigration(
            from = 19,
            to = 20
        ),
        AutoMigration(
            from = 21,
            to = 22
        )
    ]
)
@TypeConverters(UuidTypeConverter::class)
abstract class Datastore : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun scatterMessageDao(): ScatterMessageDao
    abstract fun desktopClientDao(): DesktopClientDao

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