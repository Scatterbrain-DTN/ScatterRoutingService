package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import net.ballmerlabs.scatterbrainsdk.HandshakeResult

interface BluetoothLEModule {
    fun startAdvertise()
    fun stopAdvertise()
    fun startDiscover(options: discoveryOptions): Disposable
    fun stopDiscover()
    fun startServer(): Boolean
    fun stopServer()
    fun awaitTransaction(): Completable
    fun observeTransactions(): Observable<HandshakeResult>
    fun discoverWithTimeout(timeout: Int): Completable
    fun discoverForever(): Observable<HandshakeResult>
    enum class discoveryOptions {
        OPT_DISCOVER_ONCE, OPT_DISCOVER_FOREVER
    }

    enum class ConnectionRole {
        ROLE_UKE, ROLE_SEME
    }

    companion object {
        const val TIMEOUT = 2
    }
}