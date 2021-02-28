package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
class ClientApp {
    @PrimaryKey(autoGenerate = true)
    var clientAppID: Long = -1

    @ColumnInfo
    var identityFK: Long? = null

    @ColumnInfo
    var packageName: String? = null

    @ColumnInfo
    var packageSignature: String? = null
}