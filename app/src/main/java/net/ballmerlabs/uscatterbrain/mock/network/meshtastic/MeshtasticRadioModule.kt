package net.ballmerlabs.uscatterbrain.mock.network.meshtastic

import io.reactivex.Single

interface MeshtasticRadioModule {
    fun getPacketId(): Single<Int>
    fun getMyId(): Single<String>
}