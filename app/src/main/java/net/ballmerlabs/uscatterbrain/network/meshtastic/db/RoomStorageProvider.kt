package net.ballmerlabs.uscatterbrain.network.meshtastic.db

import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionScope
import org.meshtastic.sdk.DeviceStorage
import org.meshtastic.sdk.StorageProvider
import org.meshtastic.sdk.TransportIdentity
import javax.inject.Inject

@MeshtasticConnectionScope
class RoomStorageProvider @Inject constructor(
    val builder: MeshtasticDbSubcomponent.Builder
) : StorageProvider {

    override suspend fun activate(identity: TransportIdentity): DeviceStorage {
        return builder.build()!!.deviceStorage()
    }
}