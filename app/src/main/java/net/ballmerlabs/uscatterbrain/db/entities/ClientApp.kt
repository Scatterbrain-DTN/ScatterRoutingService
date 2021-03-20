package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(indices = [
    Index(value = ["packageName"], unique = true),
    Index(value = ["packageSignature"], unique = true)
])
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