package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import io.reactivex.Observable
import io.reactivex.Single

interface GattServer {
    fun openServer(config: ServerConfig): Observable<GattServerConnection>
}