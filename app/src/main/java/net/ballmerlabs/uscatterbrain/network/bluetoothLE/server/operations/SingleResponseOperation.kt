package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.os.DeadObjectException
import com.polidea.rxandroidble2.exceptions.BleDisconnectedException
import com.polidea.rxandroidble2.exceptions.BleException
import com.polidea.rxandroidble2.exceptions.BleGattCallbackTimeoutException
import com.polidea.rxandroidble2.internal.connection.RxBleGattCallback
import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration
import io.reactivex.ObservableEmitter
import io.reactivex.Scheduler
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import java.util.concurrent.TimeoutException

abstract class SingleResponseOperation<T: Any>(
    private val gattServerConnection: GattServerConnection,
    private val operationType: GattServerOperationType,
    private val timeoutConfiguration: TimeoutConfiguration,
    private val macAddr: BluetoothDevice
) : QueueOperation<T>() {
    override fun protectedRun(
        emitter: ObservableEmitter<T>,
        queueReleaseInterface: QueueReleaseInterface
    ) {
        val emitterWrapper = QueueReleasingEmitterWrapper(emitter, queueReleaseInterface)
        getCallback(gattServerConnection)
            .timeout(
                timeoutConfiguration.timeout,
                timeoutConfiguration.timeoutTimeUnit,
                timeoutConfiguration.timeoutScheduler,
                timeoutFallbackProcedure(
                    gattServerConnection,
                    timeoutConfiguration.timeoutScheduler
                )
            )
            .toObservable()
            .subscribe(emitterWrapper)

        if (!startOperation(gattServerConnection)) {
            emitterWrapper.cancel()
            emitterWrapper.onError(IllegalStateException(operationType.toString()))
        }
    }

    /**
     * A function that should return [Observable] derived from the passed [RxBleGattCallback].
     * The returned [Observable] will be automatically unsubscribed after the first emission.
     * The returned [Observable] is a subject to
     * [Observable.timeout]
     * and by default it will throw [BleGattCallbackTimeoutException]. This behaviour can be overridden by overriding
     * [.timeoutFallbackProcedure].
     *
     * @param rxBleGattCallback the [RxBleGattCallback] to use
     * @return the Observable
     */
    protected abstract fun getCallback(rxBleGattCallback: GattServerConnection): Single<T>

    /**
     * A function that should call the passed [BluetoothGatt] and return `true` if the call has succeeded.
     *
     * @param bluetoothGatt the [BluetoothGatt] to use
     * @return `true` if success, `false` otherwise
     */
    protected abstract fun startOperation(bluetoothGatt: GattServerConnection): Boolean

    @Suppress("unused")
    protected open fun timeoutFallbackProcedure(
        rxBleGattCallback: GattServerConnection,
        timeoutScheduler: Scheduler?
    ): Single<T>? {
        return Single.error(TimeoutException("server operation timeout: $operationType"))
    }

    @SuppressLint("RestrictedApi")
    override fun provideException(deadObjectException: DeadObjectException): BleException {
        return BleDisconnectedException(
            deadObjectException, "ff:ff:ff:ff:ff:ff",
            BleDisconnectedException.UNKNOWN_STATUS
        )
    }

    override fun toString(): String {
        return "SingleResponseOperation"
    }
}
