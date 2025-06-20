package net.ballmerlabs.uscatterbrain.network.meshtastic

import io.reactivex.Maybe
import io.reactivex.Single

interface MeshtasticConnectionProvider {
    fun connectBinderAsync()
    fun getConnection(): Maybe<MeshtasticConnectionSubcomponent>
    fun awaitConnection(): Single<MeshtasticConnectionSubcomponent>
}