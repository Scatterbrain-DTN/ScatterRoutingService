package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServerCallback
import android.util.Pair
import com.polidea.rxandroidble2.RxBleConnection.RxBleConnectionState
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.SingleSubject
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerResponseTransaction
import java.util.UUID

interface GattServerConnection: Disposable {

    val gattServerCallback: BluetoothGattServerCallback

    fun getNotificationPublishRelay(): Output<Pair<String, Int>>

    fun openLongWriteCharacteristicOutput(requestid: Int, characteristic: BluetoothGattCharacteristic): Output<ByteArray>

    fun openLongWriteDescriptorOutput(requestid: Int, descriptor: BluetoothGattDescriptor): Output<ByteArray>

    fun closeLongWriteCharacteristicOutput(requestid: Int): Single<ByteArray>

    fun closeLongWriteDescriptorOutput(requestid: Int): Single<ByteArray>

    fun resetDescriptorMap()

    fun resetCharacteristicMap()

    fun getOnNotification(mac: String): Observable<Int>
    
    fun getOnConnectionStateChange(): Observable<Pair<RxBleDevice, RxBleConnectionState>>

    fun initializeServer(config: ServerConfig): Completable

    fun blindAck(
            requestID: Int,
            status: Int,
            value: ByteArray,
            device: RxBleDevice
    ): Observable<Boolean>

    fun setupNotifications(
            characteristic: BluetoothGattCharacteristic,
            notifications: Flowable<ByteArray>,
            isIndication: Boolean,
            device: RxBleDevice
    ): Flowable<ByteArray>

    fun setupNotifications(ch: UUID, notifications: Flowable<ByteArray>, device: RxBleDevice): Completable

    fun setupIndication(ch: UUID, indications: Flowable<ByteArray>, device: RxBleDevice): Completable

    fun getEvents(): Observable<ServerResponseTransaction>

    fun disconnect(device: RxBleDevice): Completable

    fun observeDisconnect(): Observable<RxBleDevice>

    fun observeConnect(): Observable<RxBleDevice>

    fun setOnDisconnect(func: (device: RxBleDevice) -> Unit)

    fun getMtu(address: String): Int
    fun setOnDisconnect(device: RxBleDevice, func: () -> Unit)

    fun resetMtu(address: String)

    fun setOnMtuChanged(device: BluetoothDevice, callback: (Int)->Unit)

    fun observeOnMtuChanged(device: BluetoothDevice): Observable<Int>

    open class Output<T> {
        open val valueRelay: PublishSubject<T> = PublishSubject.create()
        val errorRelay: PublishSubject<Throwable> = PublishSubject.create()
        fun hasObservers(): Boolean {
            return valueRelay.hasObservers() || errorRelay.hasObservers()
        }

    }

    class LongWriteClosableOutput<T> : Output<T>() {
        override val valueRelay: PublishSubject<T> = PublishSubject.create()
        val out: SingleSubject<T> = SingleSubject.create()
        fun finalize() {
            valueRelay.onComplete()
        }

    }
    enum class Operation {
        CHARACTERISTIC_READ,
        CHARACTERISTIC_WRITE,
        DESCRIPTOR_READ,
        DESCRIPTOR_WRITE
    }

    companion object {
        val CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}