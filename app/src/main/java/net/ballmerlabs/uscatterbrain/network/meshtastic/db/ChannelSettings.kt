package net.ballmerlabs.uscatterbrain.network.meshtastic.db

import androidx.room.Embedded
import androidx.room.Entity
import org.meshtastic.proto.ChannelSettings

@Entity
class ChannelSettings(
    @Embedded val moduleSettings: ModuleSettings?,
    val channelNum: Int,
    val psk: ByteArray,
    val name: String,
    val id: Int,
    val uplinkEnabled: Boolean,
    val downlinkEnabled: Boolean,
) {
    constructor(channelSettings: ChannelSettings) : this(
        channelNum = channelSettings.channel_num,
        psk = channelSettings.psk.toByteArray(),
        name = channelSettings.name,
        id = channelSettings.id,
        uplinkEnabled = channelSettings.uplink_enabled,
        downlinkEnabled = channelSettings.downlink_enabled,
        moduleSettings = if (channelSettings.module_settings != null) ModuleSettings(channelSettings.module_settings!!)
        else null
    )
}