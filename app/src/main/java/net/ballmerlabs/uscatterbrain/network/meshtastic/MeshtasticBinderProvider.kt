package net.ballmerlabs.uscatterbrain.network.meshtastic

import com.geeksville.mesh.IMeshService
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single

interface MeshtasticBinderProvider {
    fun connectBinder(): Observable<IMeshService>
}