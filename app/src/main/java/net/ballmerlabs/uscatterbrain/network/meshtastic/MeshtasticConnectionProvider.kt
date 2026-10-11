package net.ballmerlabs.uscatterbrain.network.meshtastic

import io.reactivex.Maybe
import io.reactivex.Single
import net.ballmerlabs.scatterbrainsdk.MeshtasticConnectionConfig

interface MeshtasticConnectionProvider {
    fun connectBinderAsync(config: MeshtasticConnectionConfig)
    fun getConnection(): Maybe<MeshtasticConnectionSubcomponent>
    fun awaitConnection(): Single<MeshtasticConnectionSubcomponent>
}