package net.ballmerlabs.uscatterbrain.mock.meshtastic

import io.reactivex.Flowable
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticBroadcastReceiverState
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionScope

@MeshtasticConnectionScope
class MockMeshBroadcastReceiverState :  MeshtasticBroadcastReceiverState {

    override fun acceptConnectionState(connectionState: String) {
        TODO("Not yet implemented")
    }

    override fun onConnectionState(): Flowable<String> {
        TODO("Not yet implemented")
    }

}