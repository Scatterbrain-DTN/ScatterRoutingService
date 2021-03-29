package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * database object representing an identity
 */
@Entity(
        tableName = "identities",
        indices = [
            Index(
                    value = ["fingerprint"],
                    unique = true
            )
        ]
)
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
    var identityID: Long? = null

    /* override equals() and hashCode() to make linter happy */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as KeylessIdentity

        if (fingerprint != other.fingerprint) return false

        return true
    }

    override fun hashCode(): Int {
        return fingerprint.hashCode()
    }
}