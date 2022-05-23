package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import io.reactivex.Single

interface GattServer {
    fun openServer(): Single<GattServerConnection>
}