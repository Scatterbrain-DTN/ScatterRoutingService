package net.ballmerlabs.uscatterbrain.network.desktop.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import net.ballmerlabs.scatterbrainsdk.DesktopApp
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.network.proto.ApiHeader
import java.util.UUID

@Entity(tableName = "desktop_clients", indices = [
    Index(
        value = ["remote_fingerprint"],
        unique = true
    )
])
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
    @ColumnInfo(name = "remote_fingerprint")
    val remoteFingerprint: ByteArray? = LibsodiumInterface.fingerprint(remotekey)
    ) {

    fun toApi(): DesktopApp {
        return DesktopApp(
            remotekey = this.remotekey,
            pubkey = this.pubkey,
            session = this.session,
            name = this.name,
            paired = this.paired,
            admin = this.admin,
            remoteFingerprint = this.remoteFingerprint!!
        )
    }

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

        if (!remotekey.contentEquals(other.remotekey)) return false
        if (!pubkey.contentEquals(other.pubkey)) return false
        if (session != other.session) return false
        if (!key.contentEquals(other.key)) return false
        if (name != other.name) return false
        if (paired != other.paired) return false
        if (admin != other.admin) return false

        return true
    }

    override fun hashCode(): Int {
        var result = remotekey.contentHashCode()
        result = 31 * result + pubkey.contentHashCode()
        result = 31 * result + session.hashCode()
        result = 31 * result + key.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + paired.hashCode()
        result = 31 * result + admin.hashCode()
        return result
    }

}