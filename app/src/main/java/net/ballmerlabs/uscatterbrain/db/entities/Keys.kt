package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "keys")
data class Keys(
    @ColumnInfo
    var key: String,

    @ColumnInfo
    var value: ByteArray
) {
    @PrimaryKey(autoGenerate = true)
    var keyID: Long = -1

    @ColumnInfo
    var identityFK: Long? = null
}