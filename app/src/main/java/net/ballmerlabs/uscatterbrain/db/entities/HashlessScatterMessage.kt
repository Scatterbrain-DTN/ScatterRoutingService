package net.ballmerlabs.uscatterbrain.db.entities

import android.os.IBinder
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
        var body: ByteArray?,
        var application: String,
        var sig: ByteArray?,
        var sessionid: Int,
        var extension: String,
        @ColumnInfo(name = "filepath") var filePath: String,
        @ColumnInfo(name = "globalhash") var globalhash: ByteArray,
        var userFilename: String = "",
        var mimeType: String = "application/octet-stream",
        var sendDate: Long,
        var receiveDate: Long,
        @ColumnInfo(name = "uuid", defaultValue = "0000-0000-0000-000000000000") var uuid: UUID = hashAsUUID(globalhash),
        @ColumnInfo(defaultValue = "-1") var fileSize: Long,
        @ColumnInfo(defaultValue = "0") var shareCount: Int = 0,
        @ColumnInfo(defaultValue = "") var packageName: String
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
}