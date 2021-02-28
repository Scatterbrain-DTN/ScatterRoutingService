package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hashes")
class Hashes {
    @PrimaryKey(autoGenerate = true)
    var hashID: Long? = null

    @ColumnInfo
    var hash: ByteArray? = null
}