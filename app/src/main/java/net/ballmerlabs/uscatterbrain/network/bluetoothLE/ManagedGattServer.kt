package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import io.reactivex.Completable
import io.reactivex.Maybe

interface ManagedGattServer {
    fun startServer(): Completable
    fun stopServer()
    fun getServer(): Maybe<CachedLEServerConnection>
}