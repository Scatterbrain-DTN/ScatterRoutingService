package net.ballmerlabs.uscatterbrain.network.meshtastic.db

import androidx.room.Entity
import org.meshtastic.proto.FirmwareEdition

@Entity
data class FirmwareEditionEntity(
    val name: String,
    val value: Int
) {
    constructor(firmwareEdition: FirmwareEdition): this(
        name = firmwareEdition.name,
        value = firmwareEdition.value
    )
}