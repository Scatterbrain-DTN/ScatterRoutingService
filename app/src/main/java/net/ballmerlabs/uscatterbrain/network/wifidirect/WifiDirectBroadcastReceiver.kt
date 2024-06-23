package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.BroadcastReceiver
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.WifiGroupSubcomponent
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket
import java.util.UUID

/**
 * dagger2 interface for WifiDirectBroadcastReceiver
 */
interface WifiDirectBroadcastReceiver {

    enum class P2pState {
        STATE_DISABLED, STATE_ENABLED
    }

    /**
     * observe wifi p2p state
     * @return Observable emitting P2pState objects
     */
    fun observeP2pState(): Observable<P2pState>

    /**
     * @return Observable emitting WifiP2pDevice
     */
    fun observeThisDevice(): Observable<WifiP2pDevice>

    /**
     * @return Observable emitting WifiP2pInfo
     */
    fun observeConnectionInfo(): Observable<WifiDirectInfo>

    /**
     * Observe connected devices
     * @return Observable emitting WifiP2pDeviceList
     */
    fun observePeers(): Observable<WifiP2pDeviceList>

    /**
     * Perform an explicit cast from this object to a regular BroadcastReceiver
     */
    fun asReceiver(): BroadcastReceiver

    /**
     * Returns a list of currently connected wifi devices
     *
     * @return kotlin collection of WifiP2pDevice
     */
    fun connectedDevices(): Collection<WifiP2pDevice>

    /**
     * Gets the current wifi direct group subcomponent, including the GroupHandle object used for
     * running transactions. Returns empty maybe if the group is not created or connected. This
     * function's Maybe will not complete until either a group is created or the timeout is reached.
     *
     * @param timeout: after this timeout gives up and returns empty maybe
     */
    fun getCurrentGroup(timeout: Long): Maybe<WifiGroupSubcomponent>

    /**
     * Gets the current wifi direct group subcomponent, including the GroupHandle object used for
     * running transactions. Returns empty maybe if the group is not created or connected
     */
    fun getCurrentGroup(): Maybe<WifiGroupSubcomponent>

    /**
     * Create a group subcomponent using an UpgradePacket. This does not physically create or connect to a
     * group, this must be done separately.
     *
     * @param packet: UpgradePacket sent by the remote peer, or created during group creation
     *
     * @return Single returning the group handle
     */
    fun createCurrentGroup(packet: UpgradePacket): Single<WifiGroupSubcomponent>


    /**
     * Create a group subcomponent using an UpgradePacket. This does not physically create or connect to a
     * group, this must be done separately.
     *
     * @param band the wifi band (from FakeWifiP2pConfig) that this group is created on
     * @param groupInfo: WifiP2pGroup from the created group
     * @param connectionInfo: WifiDirectInfo from the created group's connection
     * @param selfLuid: Our own hashed luid. This remains the same even after our luid changes
     *
     * @return Single returning the group handle
     */
    fun createCurrentGroup(
        band: Int, groupInfo: WifiP2pGroup, connectionInfo: WifiDirectInfo, selfLuid: UUID
    ): Single<WifiGroupSubcomponent>

    /**
    * Create a group subcomponent using an UpgradePacket. This does not physically create or connect to a
    * group, this must be done separately.
    *
     * @param packet bootstrap request sent by the remote peer
     *
     * @return Single returning the group handle
    */
    fun createCurrentGroup(packet: WifiDirectBootstrapRequest): Single<WifiGroupSubcomponent>

    /**
     * Removes the current group handle, disconnecting from the physical wifi connection and
     * disposing of all server handling threads/observables
     *
     * @return completable completing when group is disconnected
     */
    fun removeCurrentGroup(): Completable

    /**
     * Waits forever until a group is available, returning the group handle when the group is created
     *
     * @return single returning group handle
     */
    fun awaitCurrentGroup(): Single<WifiGroupSubcomponent>

    /**
     * Wraps an observable, ignoring wifi direct events while the observable is not completed.
     * This is so the wifi direct group won't be automatically removed while a connection or other
     * operation is in progress
     *
     * @param connection observable to wrap
     *
     * @return the same observable as parameter
     */
    fun <T> wrapConnection(connection: Observable<T>): Observable<T>

    /**
     * Wraps an single, ignoring wifi direct events while the single is not completed.
     * This is so the wifi direct group won't be automatically removed while a connection or other
     * operation is in progress
     *
     * @param connection single to wrap
     *
     * @return the same single as parameter
     */
    fun <T> wrapConnection(connection: Single<T>): Single<T>

    /**
     * Wraps an maybe, ignoring wifi direct events while the maybe is not completed.
     * This is so the wifi direct group won't be automatically removed while a connection or other
     * operation is in progress
     *
     * @param connection maybe to wrap
     *
     * @return the same maybe as parameter
     */
    fun <T> wrapConnection(connection: Maybe<T>): Maybe<T>

    /**
     * Wraps an completable, ignoring wifi direct events while the completable is not completed.
     * This is so the wifi direct group won't be automatically removed while a connection or other
     * operation is in progress
     *
     * @param connection completable to wrap
     *
     * @return the same completable as parameter
     */
    fun wrapConnection(connection: Completable): Completable
}