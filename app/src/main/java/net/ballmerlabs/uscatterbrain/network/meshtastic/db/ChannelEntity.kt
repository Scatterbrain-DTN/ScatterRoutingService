package net.ballmerlabs.uscatterbrain.network.meshtastic.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.meshtastic.proto.Channel

@Entity(tableName = "meshtastic_channels")
data class ChannelEntity(
    @Embedded
    val channelSettings: ChannelSettings?,
    @PrimaryKey
    val index: Int,
    val role: Int,
    @ColumnInfo(name = "channel_unknown_fields")
    val unknownFields: ByteArray,
) {
    constructor(channel: Channel) : this(
        channelSettings = if (channel.settings != null)
            ChannelSettings(channel.settings!!)
        else
            null,
        index = channel.index,
        role = channel.role.value,
        unknownFields = channel.unknownFields.toByteArray()
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChannelEntity

        if (index != other.index) return false
        if (role != other.role) return false
        if (channelSettings != other.channelSettings) return false
        if (!unknownFields.contentEquals(other.unknownFields)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + role
        result = 31 * result + (channelSettings?.hashCode() ?: 0)
        result = 31 * result + unknownFields.contentHashCode()
        return result
    }
}