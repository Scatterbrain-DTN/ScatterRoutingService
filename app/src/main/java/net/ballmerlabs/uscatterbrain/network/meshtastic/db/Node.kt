package net.ballmerlabs.uscatterbrain.network.meshtastic.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Node(
    val scope: String,
    @PrimaryKey(autoGenerate = true)
    val id: Long? = null,
    val nodeNum: Long,
    val userId: String?,
    val longName: String?,
    val shortName: String?,
    val hwModel: Long?,
    val isLicensed: Boolean?,
    val role: Long?,
    val publicKey: ByteArray?,
    val lastHeardEpoch: Long?,
    val snr: Double?,
    val rssi: Long?,
    val hopsAway: Long?,
    val viaMqtt: Boolean,
    val isSelf: Boolean,
    val rawNodeInfo: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Node

        if (id != other.id) return false
        if (nodeNum != other.nodeNum) return false
        if (hwModel != other.hwModel) return false
        if (isLicensed != other.isLicensed) return false
        if (role != other.role) return false
        if (lastHeardEpoch != other.lastHeardEpoch) return false
        if (snr != other.snr) return false
        if (rssi != other.rssi) return false
        if (hopsAway != other.hopsAway) return false
        if (viaMqtt != other.viaMqtt) return false
        if (isSelf != other.isSelf) return false
        if (scope != other.scope) return false
        if (userId != other.userId) return false
        if (longName != other.longName) return false
        if (shortName != other.shortName) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (!rawNodeInfo.contentEquals(other.rawNodeInfo)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (nodeNum?.hashCode() ?: 0)
        result = 31 * result + (hwModel?.hashCode() ?: 0)
        result = 31 * result + isLicensed.hashCode()
        result = 31 * result + (role?.hashCode() ?: 0)
        result = 31 * result + (lastHeardEpoch?.hashCode() ?: 0)
        result = 31 * result + (snr?.hashCode() ?: 0)
        result = 31 * result + (rssi?.hashCode() ?: 0)
        result = 31 * result + (hopsAway?.hashCode() ?: 0)
        result = 31 * result + viaMqtt.hashCode()
        result = 31 * result + isSelf.hashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + (userId?.hashCode() ?: 0)
        result = 31 * result + (longName?.hashCode() ?: 0)
        result = 31 * result + (shortName?.hashCode() ?: 0)
        result = 31 * result + (publicKey?.contentHashCode() ?: 0)
        result = 31 * result + (rawNodeInfo?.contentHashCode() ?: 0)
        return result
    }
}