package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "keys")
class Keys {
    @PrimaryKey(autoGenerate = true)
    var keyID: Long = -1

    @ColumnInfo
    var identityFK: Long? = null

    @ColumnInfo
    var key: String? = null

    @ColumnInfo
    var value: ByteArray? = null
}