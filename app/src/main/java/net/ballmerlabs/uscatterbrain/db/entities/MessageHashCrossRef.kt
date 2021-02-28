package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(primaryKeys = ["messageID", "hashID"], indices = [Index("messageID"), Index("hashID")])
class MessageHashCrossRef {
    @ColumnInfo(name = "messageID")
    var messageID: Long = -1

    @ColumnInfo(name = "hashID")
    var hashID: Long = -1
}