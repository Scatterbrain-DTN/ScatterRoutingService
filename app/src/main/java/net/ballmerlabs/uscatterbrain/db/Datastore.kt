package net.ballmerlabs.uscatterbrain.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
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
import net.ballmerlabs.uscatterbrain.db.entities.MerkleBundle
import net.ballmerlabs.uscatterbrain.db.entities.MerkleDao
import net.ballmerlabs.uscatterbrain.db.entities.MessageFlags
import net.ballmerlabs.uscatterbrain.db.entities.Metrics
import net.ballmerlabs.uscatterbrain.db.entities.ScatterMessageDao
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopClientDao
import net.ballmerlabs.uscatterbrain.network.desktop.entity.DesktopClient
import net.ballmerlabs.uscatterbrain.network.meshtastic.db.ChannelEntity
import net.ballmerlabs.uscatterbrain.network.meshtastic.db.ConfigEntity
import net.ballmerlabs.uscatterbrain.network.meshtastic.db.Heartbeat
import net.ballmerlabs.uscatterbrain.network.meshtastic.db.MeshtasticDao
import net.ballmerlabs.uscatterbrain.network.meshtastic.db.MyNodeInfoEntity
import net.ballmerlabs.uscatterbrain.network.meshtastic.db.Node
import net.ballmerlabs.uscatterbrain.network.meshtastic.db.SessionEntity
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
        DesktopClient::class,
        MerkleBundle::class,
        MessageFlags::class,
        ChannelEntity::class,
        MyNodeInfoEntity::class,
        SessionEntity::class,
        ConfigEntity::class,
        Node::class,
        Heartbeat::class
    ],
    version = 29,
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
        ),
        AutoMigration(
            from = 23,
            to = 24
        ),
        AutoMigration(
            from = 24,
            to = 25
        ),
        AutoMigration(
            from = 25,
            to = 26
        ),
        AutoMigration(
            from = 27,
            to = 28
        ),
        AutoMigration(
            from = 28,
            to = 29
        )
    ]
)
@TypeConverters(UuidTypeConverter::class)
abstract class Datastore : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun scatterMessageDao(): ScatterMessageDao
    abstract fun desktopClientDao(): DesktopClientDao
    abstract fun merkleDao(): MerkleDao
    abstract fun meshtasticDao(): MeshtasticDao

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