package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.*
import com.google.protobuf.ByteString

/**
 * database object for a message.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = GlobalHash::class,
            parentColumns = ["globalhash"],
            childColumns = ["fileGlobalHash"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
            deferred = true
        )
    ]
)
data class HashlessScatterMessage(
    var body: ByteArray?,
    var application: String,
    var sig: ByteArray?,
    var sessionid: Int,
    var extension: String,
    var userFilename: String = "",
    var mimeType: String = "application/octet-stream",
    var sendDate: Long,
    var receiveDate: Long,
    var fileGlobalHash: ByteArray,
    @ColumnInfo(defaultValue = "-1") var fileSize: Long,
    @ColumnInfo(defaultValue = "0") var shareCount: Int = 0,
    @ColumnInfo(defaultValue = "") var packageName: String
    ) {
    companion object {
        fun hash2hashs(hashes: List<ByteArray>, globalhash: ByteArray): List<Hashes> {
            val result = ArrayList<Hashes>()
            for (hash in hashes) {
                val h = Hashes(hash, parent = globalhash)
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

        if (body != null) {
            if (other.body == null) return false
            if (!body.contentEquals(other.body)) return false
        } else if (other.body != null) return false
        if (application != other.application) return false
        if (sig != null) {
            if (other.sig == null) return false
            if (!sig.contentEquals(other.sig)) return false
        } else if (other.sig != null) return false
        if (sessionid != other.sessionid) return false
        if (extension != other.extension) return false
        if (userFilename != other.userFilename) return false
        if (mimeType != other.mimeType) return false
        if (sendDate != other.sendDate) return false
        if (receiveDate != other.receiveDate) return false
        if (fileSize != other.fileSize) return false
        if (shareCount != other.shareCount) return false
        if (packageName != other.packageName) return false
        if (messageID != other.messageID) return false

        return true
    }

    override fun hashCode(): Int {
        var result = body?.contentHashCode() ?: 0
        result = 31 * result + application.hashCode()
        result = 31 * result + (sig?.contentHashCode() ?: 0)
        result = 31 * result + sessionid
        result = 31 * result + extension.hashCode()
        result = 31 * result + userFilename.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + sendDate.hashCode()
        result = 31 * result + receiveDate.hashCode()
        result = 31 * result + fileSize.hashCode()
        result = 31 * result + shareCount
        result = 31 * result + packageName.hashCode()
        result = 31 * result + (messageID?.hashCode() ?: 0)
        return result
    }
}