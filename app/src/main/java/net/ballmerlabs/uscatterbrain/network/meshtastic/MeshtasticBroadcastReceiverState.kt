package net.ballmerlabs.uscatterbrain.network.meshtastic

import io.reactivex.Flowable


interface MeshtasticBroadcastReceiverState {
    fun acceptConnectionState(connectionState: String)
    fun onConnectionState(): Flowable<String>
}