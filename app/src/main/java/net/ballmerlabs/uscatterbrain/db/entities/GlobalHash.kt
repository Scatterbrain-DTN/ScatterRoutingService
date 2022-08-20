package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import net.ballmerlabs.uscatterbrain.db.hashAsUUID
import java.util.*

@Entity(
    tableName = "globalhash",
    indices = [
        Index(
            value = ["uuid"],
            unique = true
        )
    ],
)
data class GlobalHash(
    @PrimaryKey
    val globalhash: ByteArray,
    var filePath: String,
    val uuid: UUID = hashAsUUID(globalhash)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GlobalHash

        if (!globalhash.contentEquals(other.globalhash)) return false
        if (filePath != other.filePath) return false

        return true
    }

    override fun hashCode(): Int {
        var result = globalhash.contentHashCode()
        result = 31 * result + filePath.hashCode()
        return result
    }
}