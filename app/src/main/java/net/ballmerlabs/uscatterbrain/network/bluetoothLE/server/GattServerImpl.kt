package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.GattServerConnectionSubcomponent
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class GattServerImpl @Inject constructor(
        private val connectionBuilder: Provider<GattServerConnectionSubcomponent.Builder>
): GattServer {
    override fun openServer(): Single<GattServerConnection> {
        return Single.fromCallable {
            connectionBuilder.get().build()!!.connection()
        }
    }
}