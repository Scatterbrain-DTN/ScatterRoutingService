package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Completable
import io.reactivex.Maybe

interface ManagedGattServer {
    fun disconnect(device: RxBleDevice?)
    fun startServer(): Completable
    fun stopServer()
    fun getServer(): Maybe<CachedLEServerConnection>
}