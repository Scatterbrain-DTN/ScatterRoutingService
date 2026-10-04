package net.ballmerlabs.uscatterbrain.network.meshtastic.db

import androidx.room.Entity


@Entity(tableName = "meshtastic_channels", primaryKeys = ["scope", "idx"])
data class ChannelEntity(
    val scope: String,
    val idx: Long,
    val role: Long,
    val name: String?,
    val psk: ByteArray?,
    val pskIndex: Long?,
    val uplink: Boolean,
    val downlink: Boolean,
    val settingsRaw: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChannelEntity

        if (idx != other.idx) return false
        if (role != other.role) return false
        if (pskIndex != other.pskIndex) return false
        if (uplink != other.uplink) return false
        if (downlink != other.downlink) return false
        if (scope != other.scope) return false
        if (name != other.name) return false
        if (!psk.contentEquals(other.psk)) return false
        if (!settingsRaw.contentEquals(other.settingsRaw)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = idx.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + (pskIndex?.hashCode() ?: 0)
        result = 31 * result + uplink.hashCode()
        result = 31 * result + downlink.hashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + (psk?.contentHashCode() ?: 0)
        result = 31 * result + (settingsRaw?.contentHashCode() ?: 0)
        return result
    }

}