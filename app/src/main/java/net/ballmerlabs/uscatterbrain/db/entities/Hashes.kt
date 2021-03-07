package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hashes")
data class Hashes(
    @ColumnInfo
    var hash: ByteArray
) {
    @PrimaryKey(autoGenerate = true)
    var hashID: Long = -1
}