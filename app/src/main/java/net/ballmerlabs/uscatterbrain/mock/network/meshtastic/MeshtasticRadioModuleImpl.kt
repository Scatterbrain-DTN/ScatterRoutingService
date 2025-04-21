package net.ballmerlabs.uscatterbrain.mock.network.meshtastic

import io.reactivex.Single
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshtasticRadioModuleImpl @Inject constructor() : MeshtasticRadioModule {
    override fun getMyId(): Single<String> {
        TODO("Not yet implemented")
    }

    override fun getPacketId(): Single<Int> {
        TODO("Not yet implemented")
    }
}