package net.ballmerlabs.uscatterbrain.network.meshtastic

import io.reactivex.Observable
import org.meshtastic.core.service.IMeshService

interface MeshtasticBinderProvider {
    fun connectBinder(): Observable<IMeshService>
}