package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * database object representing a single public key pinned to an identity
 */
@Entity(
        tableName = "keys",
        foreignKeys = [ForeignKey(
                entity = KeylessIdentity::class,
                parentColumns = arrayOf("identityID"),
                childColumns = arrayOf("identityFK"),
                onDelete = ForeignKey.CASCADE,
                onUpdate = ForeignKey.CASCADE
        )]
)
data class Keys(
    @ColumnInfo
    var key: String,

    @ColumnInfo
    var value: ByteArray
) {
    @PrimaryKey(autoGenerate = true)
    var keyID: Long? = null

    @ColumnInfo
    var identityFK: Long? = null

    /* override equals() and hashCode to make the linter happy */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Keys

        if (key != other.key) return false
        if (!value.contentEquals(other.value)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + value.contentHashCode()
        return result
    }
}

/**
 * helper class to query Room database by identity foreign key
 */
data class JustIdentiyFK(
        val identityFK: Long
)