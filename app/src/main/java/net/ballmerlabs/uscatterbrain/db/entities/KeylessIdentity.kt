package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "identities", indices = [Index(value = ["fingerprint"], unique = true)])
class KeylessIdentity {
    @PrimaryKey(autoGenerate = true)
    var identityID: Long = -1

    @ColumnInfo(name = "givenname")
    var givenName: String? = null

    @ColumnInfo(name = "publickey")
    var publicKey: ByteArray? = null

    @ColumnInfo(name = "signature")
    var signature: ByteArray? = null

    @ColumnInfo(name = "fingerprint")
    var fingerprint: String? = null

    @ColumnInfo(name = "privatekey")
    var privatekey: ByteArray? = null
}