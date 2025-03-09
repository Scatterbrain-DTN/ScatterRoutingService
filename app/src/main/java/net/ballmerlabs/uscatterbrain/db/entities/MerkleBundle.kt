package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

const val MERKLE_WIDTH = 4.toLong()

@Entity(
    tableName = "bundles",
    foreignKeys = [
        ForeignKey(
            entity = MerkleBundle::class,
            parentColumns = [ "id" ],
            childColumns = [ "childOne" ],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MerkleBundle::class,
            parentColumns = [ "id" ],
            childColumns = [ "childTwo" ],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            value = [ "hash" ],
            unique = false
        ),
        Index(
            value = [ "childOne", "childTwo" ],
            unique = false
        ),

    ]
)
data class MerkleBundle(
    @PrimaryKey(autoGenerate = true)
    var id: Long? = null,
    val hash: ByteArray? = null,
    var childOne: Long? = null,
    var childTwo: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val text: Boolean = false,
    val application: String? = null,
    @ColumnInfo(defaultValue = "1")
    var dirty: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MerkleBundle

        if (id != other.id) return false
        if (childOne != other.childOne) return false
        if (childTwo != other.childTwo) return false
        if (text != other.text) return false
        if (dirty != other.dirty) return false
        if (hash != null) {
            if (other.hash == null) return false
            if (!hash.contentEquals(other.hash)) return false
        } else if (other.hash != null) return false
        if (application != other.application) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + (childOne?.hashCode() ?: 0)
        result = 31 * result + (childTwo?.hashCode() ?: 0)
        result = 31 * result + text.hashCode()
        result = 31 * result + dirty.hashCode()
        result = 31 * result + (hash?.contentHashCode() ?: 0)
        result = 31 * result + (application?.hashCode() ?: 0)
        return result
    }

}