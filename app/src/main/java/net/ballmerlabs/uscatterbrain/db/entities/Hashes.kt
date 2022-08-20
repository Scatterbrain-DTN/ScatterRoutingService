package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Database object for a message hash
 * TODO: add foreign key for HashlessScatterMessage to make deletion cleaner
 */
@Entity(
    tableName = "hashes",
    foreignKeys = [
        ForeignKey(
            entity = GlobalHash::class,
            parentColumns = ["globalhash"],
            childColumns = ["parent"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
            deferred = true
        )
    ]
)
data class Hashes(
    @ColumnInfo
    var hash: ByteArray,
    var parent: ByteArray
) {

    @PrimaryKey(autoGenerate = true)
    var id: Long? = null

    /*
     * the linter seems to want me to implement equals() and
     * hashCode(). I am not using them but I want no warnings
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Hashes

        if (!hash.contentEquals(other.hash)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = hash.contentHashCode()
        result *= 31
        return result
    }
}