package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.BroadcastReceiver
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket
import net.ballmerlabs.uscatterbrain.WifiGroupSubcomponent

import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBroadcastReceiver.P2pState
import org.mockito.internal.matchers.Not
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class MockWifiDirectBroadcastReceiver(private val broadcastReceiver: BroadcastReceiver): WifiDirectBroadcastReceiver {

    val p2pStateRelay = BehaviorRelay.create<P2pState>()
    val thisDeviceRelay = BehaviorRelay.create<WifiP2pDevice>()
    val connectionInfoRelay = BehaviorRelay.create<WifiDirectInfo>()
    val p2pDeviceListRelay = BehaviorRelay.create<WifiP2pDeviceList>()
    private val ignoreShutdown = AtomicBoolean(false)


    override fun createCurrentGroup(
        band: Int,
        groupInfo: WifiP2pGroup,
        connectionInfo: WifiDirectInfo,
        selfLuid: UUID
    ): Single<WifiGroupSubcomponent> {
        return Single.error(NotImplementedError())
    }

    override fun removeCurrentGroup(): Completable {
        return Completable.complete()
    }

    override fun <T> wrapConnection(connection: Maybe<T>): Maybe<T> {
        return connection
            .doOnSubscribe { ignoreShutdown.set(true) }
            .doFinally { ignoreShutdown.set(false) }
    }

    override fun <T> wrapConnection(connection: Observable<T>): Observable<T> {
        return connection
            .doOnSubscribe { ignoreShutdown.set(true) }
            .doFinally { ignoreShutdown.set(false) }
    }

    override fun <T> wrapConnection(connection: Single<T>): Single<T> {
        return connection
            .doOnSubscribe { ignoreShutdown.set(true) }
            .doFinally { ignoreShutdown.set(false) }
    }

    override fun wrapConnection(connection: Completable): Completable {
        return connection
            .doOnSubscribe { ignoreShutdown.set(true) }
            .doFinally { ignoreShutdown.set(false) }
    }


    override fun awaitCurrentGroup(): Single<WifiGroupSubcomponent> {
        TODO("Not yet implemented")
    }

    override fun getCurrentGroup(): Maybe<WifiGroupSubcomponent> {
        return Maybe.empty()
    }

    override fun createCurrentGroup(packet: UpgradePacket): Single<WifiGroupSubcomponent> {
        TODO("Not yet implemented")
    }

    override fun createCurrentGroup(packet: WifiDirectBootstrapRequest): Single<WifiGroupSubcomponent> {
        return Single.error(NotImplementedError())
    }

    override fun observeP2pState(): Observable<P2pState> {
        return p2pStateRelay
    }

    override fun observeThisDevice(): Observable<WifiP2pDevice> {
        return thisDeviceRelay
    }

    override fun observeConnectionInfo(): Observable<WifiDirectInfo> {
        return connectionInfoRelay
    }

    override fun observePeers(): Observable<WifiP2pDeviceList> {
        return p2pDeviceListRelay
    }

    override fun asReceiver(): BroadcastReceiver {
        return broadcastReceiver
    }

    override fun connectedDevices(): Collection<WifiP2pDevice> {
        return listOf()
    }

    override fun getCurrentGroup(timeout: Long): Maybe<WifiGroupSubcomponent> {
        return Maybe.empty()
    }
}