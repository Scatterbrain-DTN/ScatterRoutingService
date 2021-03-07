package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.protobuf.ByteString
import java.util.*

@Entity(tableName = "messages", indices = [Index(value = ["filepath"], unique = true), Index(value = ["globalhash"], unique = true)])
data class HashlessScatterMessage(
        @ColumnInfo
        var body: ByteArray? = null,

        @ColumnInfo
        var identity_fingerprint: String? = null,

        @ColumnInfo
        var to: ByteArray? = null,

        @ColumnInfo
        var from: ByteArray? = null,


        @ColumnInfo
        var application: ByteArray,


        @ColumnInfo
        var sig: ByteArray? = null,


        @ColumnInfo
        var sessionid: Int,


        @ColumnInfo
        var blocksize: Int,


        @ColumnInfo
        var extension: String,


        @ColumnInfo(name = "filepath")
        var filePath: String,

        @ColumnInfo(name = "globalhash")
        var globalhash: ByteArray,

        @ColumnInfo
        var userFilename: String? = null,

        @ColumnInfo
        var mimeType: String?
) {
    companion object {
        fun hash2hashs(hashes: List<ByteString>): List<Hashes> {
            val result = ArrayList<Hashes>()
            for (hash in hashes) {
                val h = Hashes(hash.toByteArray())
                result.add(h)
            }
            return result
        }

        fun hashes2hash(hashes: List<Hashes>): List<ByteString> {
            val result = ArrayList<ByteString>()
            for (hash in hashes) {
                result.add(ByteString.copyFrom(hash.hash))
            }
            return result
        }
    }

    @PrimaryKey(autoGenerate = true)
    var messageID: Long = 0
}