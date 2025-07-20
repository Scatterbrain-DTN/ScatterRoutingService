package net.ballmerlabs.uscatterbrain.network.meshtastic

import com.geeksville.mesh.IMeshService
import io.reactivex.Observable

interface MeshtasticBinderProvider {
    fun connectBinder(): Observable<IMeshService>
}