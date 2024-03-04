package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServerCallback
import android.util.Pair
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.ServerConfig
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerResponseTransaction
import java.util.UUID

class MockGattServerConnection(
    val mockState:  Observable<Pair<RxBleDevice, RxBleConnection.RxBleConnectionState>>,
    val mockEvents: Observable<ServerResponseTransaction>,
    override val gattServerCallback: BluetoothGattServerCallback
) : GattServerConnection {
    var disconnected = false
    val disp = CompositeDisposable()
    var mtu = mutableMapOf<String, Int>()
    val mtuChanged = BehaviorSubject.create<Int>()
    override fun initializeServer(config: ServerConfig): Completable {
        return Completable.complete()
    }

    override fun forceMtu(address: String, mtu: Int) {
        this.mtu[address] = mtu
    }

    override fun getOnConnectionStateChange(): Observable<Pair<RxBleDevice, RxBleConnection.RxBleConnectionState>> {
        return mockState
    }

    override fun blindAck(
        requestID: Int,
        status: Int,
        value: ByteArray,
        device: RxBleDevice
    ): Observable<Boolean> {
        return Observable.just(true)
    }

    override fun setupNotifications(
        characteristic: BluetoothGattCharacteristic,
        notifications: Flowable<ByteArray>,
        isIndication: Boolean,
        device: RxBleDevice
    ): Flowable<ByteArray> {
        return notifications
    }

    override fun setupNotifications(
        ch: UUID,
        notifications: Flowable<ByteArray>,
        device: RxBleDevice
    ): Completable {
        return notifications.ignoreElements()
    }

    override fun setupIndication(
        ch: UUID,
        indications: Flowable<ByteArray>,
        device: RxBleDevice
    ): Completable {
        return indications.ignoreElements()
    }

    override fun getEvents(): Observable<ServerResponseTransaction> {
        return mockEvents
    }

    override fun disconnect(device: RxBleDevice): Completable {
        return Completable.fromAction {
            disconnected = true
        }
    }

    override fun observeDisconnect(): Observable<RxBleDevice> {
        return mockState.filter { c ->
            c.second == RxBleConnection.RxBleConnectionState.DISCONNECTED ||
                    c.second == RxBleConnection.RxBleConnectionState.DISCONNECTED
        }.map { v -> v.first }
    }

    override fun observeConnect(): Observable<RxBleDevice> {
        return mockState.filter { c ->
            c.second == RxBleConnection.RxBleConnectionState.CONNECTED
        }.map { v -> v.first }
    }

    override fun setOnDisconnect(func: (device: RxBleDevice) -> Unit) {
        val d = observeDisconnect().subscribe { d -> func(d) }
        disp.add(d)
    }

    override fun setOnDisconnect(device: RxBleDevice, func: () -> Unit) {
        val d = observeDisconnect().takeUntil { d -> d.macAddress == device.macAddress }
            .subscribe {
                func()
            }

        disp.add(d)
    }

    override fun getMtu(address: String): Int {
        return mtu[address]!!
    }

    override fun resetMtu(address: String) {
        mtu.remove(address)
    }

    override fun setOnMtuChanged(device: BluetoothDevice, callback: (Int) -> Unit) {
        val d = mtuChanged.subscribe {v ->
            callback(v)
        }
        disp.add(d)
    }

    override fun observeOnMtuChanged(device: BluetoothDevice): Observable<Int> {
        return mtuChanged
    }

    override fun dispose() {
        disp.dispose()
    }

    override fun isDisposed(): Boolean {
        return disp.isDisposed
    }
}