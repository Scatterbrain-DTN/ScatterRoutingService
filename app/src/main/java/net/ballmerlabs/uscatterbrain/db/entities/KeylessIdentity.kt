package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "identities", indices = [Index(value = ["fingerprint"], unique = true)])
data class KeylessIdentity (
    @ColumnInfo(name = "givenname")
    var givenName: String,

    @ColumnInfo(name = "publickey")
    var publicKey: ByteArray,

    @ColumnInfo(name = "signature")
    var signature: ByteArray,

    @ColumnInfo(name = "fingerprint")
    var fingerprint: String,

    @ColumnInfo(name = "privatekey")
    var privatekey: ByteArray?
) {
    @PrimaryKey(autoGenerate = true)
    var identityID: Long = -1
}