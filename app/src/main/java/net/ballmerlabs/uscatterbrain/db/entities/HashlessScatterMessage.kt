package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.protobuf.ByteString
import net.ballmerlabs.uscatterbrain.db.hashAsUUID
import java.util.*

/**
 * database object for a message.
 */
@Entity(
        tableName = "messages",
        indices = [
            Index(
                    value = ["filepath"]
                    , unique = true
            ),
            Index(
                    value = ["globalhash"],
                    unique = true
            ),
            Index(
                    value = ["uuid"],
                    unique = true
            )
        ]
)
data class HashlessScatterMessage(
        var body: ByteArray? = null,
        var identity_fingerprint: String? = null,
        var recipient_fingerprint: String? = null,
        var application: String,
        var sig: ByteArray? = null,
        var sessionid: Int,
        var extension: String,
        @ColumnInfo(name = "filepath") var filePath: String,
        @ColumnInfo(name = "globalhash") var globalhash: ByteArray,
        var userFilename: String = "",
        var mimeType: String,
        var sendDate: Long,
        var receiveDate: Long?,
        @ColumnInfo(name = "uuid", defaultValue = "") var uuid: UUID = hashAsUUID(globalhash),
        var fileSize: Long
        ) {
    companion object {
        fun hash2hashs(hashes: List<ByteArray>): List<Hashes> {
            val result = ArrayList<Hashes>()
            for (hash in hashes) {
                val h = Hashes(hash)
                result.add(h)
            }
            return result
        }

        fun hash2hashsProto(hashes: List<ByteString>): List<Hashes> {
            val result = ArrayList<Hashes>()
            for (hash in hashes) {
                val h = Hashes(hash.toByteArray())
                result.add(h)
            }
            return result
        }

        fun hashes2hashProto(hashes: List<Hashes>): List<ByteString> {
            val result = ArrayList<ByteString>()
            for (hash in hashes) {
                result.add(ByteString.copyFrom(hash.hash))
            }
            return result
        }
        
        fun hashes2hash(hashes: List<Hashes>): List<ByteArray> {
            val result = ArrayList<ByteArray>()
            for (hash in hashes) {
                result.add(hash.hash)
            }
            return result
        }
    }

    /* override equals() and hashCode() to make linter happy */
    @PrimaryKey(autoGenerate = true)
    var messageID: Long? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HashlessScatterMessage

        if (!globalhash.contentEquals(other.globalhash)) return false

        return true
    }

    override fun hashCode(): Int {
        return globalhash.contentHashCode()
    }
}