package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * database entity representing a mapping between a message and many hashes
 */
@Entity(
        primaryKeys = ["messageID", "hashID"],
        indices = [Index("messageID"), Index("hashID")]
)
data class MessageHashCrossRef(
    @ColumnInfo(name = "messageID")
    var messageID: Long,

    @ColumnInfo(name = "hashID")
    var hashID: Long
)