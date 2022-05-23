package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.util.Pair
import com.polidea.rxandroidble2.RxBleConnection.RxBleConnectionState
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.exceptions.BleGattServerException
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.SingleSubject
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.GattServerTransaction
import java.util.*

interface GattServerConnection: Disposable {
    val server: BluetoothGattServer
    fun getReadCharacteristicOutput(): Output<GattServerTransaction<UUID>>

    fun getWriteCharacteristicOutput(): Output<GattServerTransaction<UUID>>

    fun getReadDescriptorOutput(): Output<GattServerTransaction<BluetoothGattDescriptor>>

    fun getWriteDescriptorOutput(): Output<GattServerTransaction<BluetoothGattDescriptor>>

    fun getNotificationPublishRelay(): Output<Int>

    fun getChangedMtuOutput(): Output<Int>
    fun openLongWriteCharacteristicOutput(requestid: Int, characteristic: BluetoothGattCharacteristic): Output<ByteArray>

    fun openLongWriteDescriptorOutput(requestid: Int, descriptor: BluetoothGattDescriptor): Output<ByteArray>

    fun closeLongWriteCharacteristicOutput(requestid: Int): Single<ByteArray>

    fun closeLongWriteDescriptorOutput(requestid: Int): Single<ByteArray>

    fun resetDescriptorMap()

    fun resetCharacteristicMap()

    fun getOnNotification(): Observable<Int>
    
    fun getOnConnectionStateChange(): Observable<Pair<RxBleDevice, RxBleConnectionState>>


    fun prepareDescriptorTransaction(
            descriptor: BluetoothGattDescriptor,
            requestID: Int,
            offset: Int,
            device: RxBleDevice,
            valueRelay: PublishSubject<GattServerTransaction<BluetoothGattDescriptor>>,
            value: ByteArray?
    )

    fun prepareCharacteristicTransaction(
            descriptor: BluetoothGattCharacteristic,
            requestID: Int,
            offset: Int,
            device: RxBleDevice,
            valueRelay: PublishSubject<GattServerTransaction<UUID>>,
            value: ByteArray?
    )

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
    ): Completable

    fun setupNotifications(ch: UUID, notifications: Flowable<ByteArray>, device: RxBleDevice): Completable

    fun setupIndication(ch: UUID, indications: Flowable<ByteArray>, device: RxBleDevice): Completable

    open class Output<T> {
        open val valueRelay: PublishSubject<T> = PublishSubject.create()
        val errorRelay: PublishSubject<BleGattServerException> = PublishSubject.create()
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
}