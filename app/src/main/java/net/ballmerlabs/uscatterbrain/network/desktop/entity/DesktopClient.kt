package net.ballmerlabs.uscatterbrain.network.desktop.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import net.ballmerlabs.uscatterbrain.network.proto.ApiHeader
import java.util.UUID

@Entity(tableName = "desktop_clients")
data class DesktopClient(
    @PrimaryKey
    val remotekey: ByteArray,
    var pubkey: ByteArray,
    var session: UUID,
    @ColumnInfo(name = "key")
    var key: ByteArray,
    var name: String,
    @ColumnInfo(defaultValue = "false")
    var paired: Boolean = false,
    @ColumnInfo(defaultValue = "false")
    var admin: Boolean = false,
) {

    fun getHeader(stream: Int): ApiHeader {
        return ApiHeader (
            session = session,
            stream = stream
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DesktopClient

        if (session != other.session) return false
        if (!key.contentEquals(other.key)) return false
        if (!pubkey.contentEquals(other.pubkey)) return false
        if (name != other.name) return false
        if (admin != other.admin) return false

        return true
    }

    override fun hashCode(): Int {
        var result = session.hashCode()
        result = 31 * result + key.contentHashCode()
        result = 31 * result + pubkey.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + admin.hashCode()
        return result
    }

}