package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.GattServerConnectionSubcomponent

interface GattServer {
    fun openServer(config: ServerConfig): Single<GattServerConnectionSubcomponent>
}