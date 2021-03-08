package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class ClientApp(
    @ColumnInfo
    var identityFK: Long,

    @ColumnInfo
    var packageName: String,

    @ColumnInfo
    var packageSignature: String
) {
    @PrimaryKey(autoGenerate = true)
    var clientAppID: Long? = null
}