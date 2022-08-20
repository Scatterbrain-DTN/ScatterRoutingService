package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import android.os.DeadObjectException
import com.polidea.rxandroidble2.RxBleConnection.RxBleConnectionState
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.exceptions.BleException
import com.polidea.rxandroidble2.internal.QueueOperation
import com.polidea.rxandroidble2.internal.RxBleLog
import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration
import com.polidea.rxandroidble2.internal.serialization.QueueReleaseInterface
import com.polidea.rxandroidble2.internal.util.QueueReleasingEmitterWrapper
import io.reactivex.Completable
import io.reactivex.ObservableEmitter
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import java.lang.IllegalStateException

class NotifyCharacteristicChangedOperation(
        private val server: BluetoothGattServer,
                private val characteristic: BluetoothGattCharacteristic,
                private val timeoutConfiguration: TimeoutConfiguration,
                private val connection: GattServerConnection,
                private val value: ByteArray,
                private var isIndication: Boolean,
                private val device: RxBleDevice,
): QueueOperation<Int>() {
    @Throws(Throwable::class)
    override fun protectedRun(
            emitter: ObservableEmitter<Int>,
            queueReleaseInterface: QueueReleaseInterface?
    ) {
        val emitterWrapper = QueueReleasingEmitterWrapper(emitter, queueReleaseInterface)
            RxBleLog.d("running notifycharacteristic notification/indication operation device: ")
            getCompleted()
                    .toObservable()
                    .doOnComplete { RxBleLog.d("completed notifycharacteristic operation") }
                    .subscribe(emitterWrapper)
            getOnDisconnect()
                    .toSingleDefault(BluetoothGatt.GATT_FAILURE)
                    .toObservable()
                    .subscribe(emitterWrapper)
            characteristic.value = value
            try {
                if (if (Build.VERSION.SDK_INT >= 33) {
                        server.notifyCharacteristicChanged(device.bluetoothDevice, characteristic, isIndication, value) != BluetoothStatusCodes.SUCCESS
                    } else {
                        characteristic.value = value
                        server.notifyCharacteristicChanged(device.bluetoothDevice, characteristic, isIndication)
                    }
                ) {
                    emitterWrapper.onError(IllegalStateException("notify failed"))
                }
            } catch (exc: SecurityException) {
                emitterWrapper.onError(exc)
            }
    }

    private fun getOnDisconnect(): Completable {
        return connection.getOnConnectionStateChange()
                .takeUntil { pair ->
                    (pair.second == RxBleConnectionState.DISCONNECTING
                            || pair.second == RxBleConnectionState.DISCONNECTED)
                }
                .ignoreElements()
    }

    private fun getCompleted(): Single<Int> {
        return connection.getOnNotification()
                .firstOrError()
    }

    override fun provideException(deadObjectException: DeadObjectException?): BleException {
        return BleException()
    }

}