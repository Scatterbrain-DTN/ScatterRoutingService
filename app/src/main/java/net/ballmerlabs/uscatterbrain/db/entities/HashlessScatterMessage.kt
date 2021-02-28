package net.ballmerlabs.uscatterbrain.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.protobuf.ByteString
import java.util.*

@Entity(tableName = "messages", indices = [Index(value = ["filepath"], unique = true), Index(value = ["identity_fingerprint"], unique = true), Index(value = ["globalhash"], unique = true)])
class HashlessScatterMessage {
    @PrimaryKey(autoGenerate = true)
    var messageID: Long = 0

    
    @ColumnInfo
    var body: ByteArray? = null

    
    @ColumnInfo
    var identity_fingerprint: String? = null

    
    @ColumnInfo
    var to: ByteArray? = null

    
    @ColumnInfo
    var from: ByteArray? = null

    
    @ColumnInfo
    var application: ByteArray? = null

    
    @ColumnInfo
    var sig: ByteArray? = null

    
    @ColumnInfo
    var sessionid = 0

    
    @ColumnInfo
    var blocksize = 0

    
    @ColumnInfo
    var extension: String? = null

    
    @ColumnInfo(name = "filepath")
    var filePath: String? = null

    
    @ColumnInfo(name = "globalhash")
    var globalhash: ByteArray? = null

    
    @ColumnInfo
    var userFilename: String? = null

    
    @ColumnInfo
    var mimeType: String? = null

    companion object {
        fun hash2hashs(hashes: List<ByteString>): List<Hashes> {
            val result = ArrayList<Hashes>()
            for (hash in hashes) {
                val h = Hashes()
                h.hash = hash.toByteArray()
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
}