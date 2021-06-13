package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.protobuf.ByteString
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
            )
        ]
)
data class HashlessScatterMessage(@ColumnInfo
                                  var body: ByteArray? = null, @ColumnInfo
                                  var identity_fingerprint: String? = null, @ColumnInfo
                                  var to: String? = null, @ColumnInfo
                                  var from: String? = null, @ColumnInfo
                                  var application: String, @ColumnInfo
                                  var sig: ByteArray? = null, @ColumnInfo
                                  var sessionid: Int, @ColumnInfo
                                  var extension: String, @ColumnInfo(name = "filepath")
                                  var filePath: String, @ColumnInfo(name = "globalhash")
                                  var globalhash: ByteArray, @ColumnInfo
                                  var userFilename: String = "", @ColumnInfo
                                  var mimeType: String,
                                  var sendDate: Long,
                                  var receiveDate: Long? = null
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