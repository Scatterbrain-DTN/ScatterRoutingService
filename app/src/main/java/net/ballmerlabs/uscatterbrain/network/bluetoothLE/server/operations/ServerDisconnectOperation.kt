package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.DeadObjectException
import com.polidea.rxandroidble2.RxBleConnection.RxBleConnectionState
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.exceptions.BleDisconnectedException
import com.polidea.rxandroidble2.exceptions.BleException
import com.polidea.rxandroidble2.internal.QueueOperation
import com.polidea.rxandroidble2.internal.RxBleLog
import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration
import com.polidea.rxandroidble2.internal.serialization.QueueReleaseInterface
import io.reactivex.ObservableEmitter
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.SingleObserver
import io.reactivex.disposables.Disposable
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection

class ServerDisconnectOperation(
        private val server: BluetoothGattServer,
        private val device: RxBleDevice,
        private val callback: GattServerConnection,
        private val gattServerScheduler: Scheduler,
        private val bluetoothManager: BluetoothManager,
        private val timeoutConfiguration: TimeoutConfiguration
) :QueueOperation<Void>() {
    

    override fun protectedRun(emitter: ObservableEmitter<Void>, queueReleaseInterface: QueueReleaseInterface) {
        disconnectIfRequired()
                .timeout(
                        timeoutConfiguration.timeout,
                        timeoutConfiguration.timeoutTimeUnit,
                        timeoutConfiguration.timeoutScheduler
                )
                .observeOn(gattServerScheduler)
                .subscribe(object : SingleObserver<BluetoothGattServer> {
                    override fun onSubscribe(d: Disposable) {
                        //not used
                    }

                    override fun onSuccess(bluetoothGattServer: BluetoothGattServer) {
                        queueReleaseInterface.release()
                        emitter.onComplete()
                    }

                    override fun onError(e: Throwable) {
                        RxBleLog.w(
                                e,
                                "Server Disconnect Operation has been executed, but finished with error"
                        )
                        queueReleaseInterface.release()
                        emitter.onComplete()
                    }
                })
    }

    private fun disconnectIfRequired(): Single<BluetoothGattServer> {
        return if (isDisconnected()) Single.just(server) else disconnect()
    }

    private fun disconnect(): Single<BluetoothGattServer> {
        //TODO: handle timeout
        return DisconnectGattServerObservable(
                server,
                callback,
                gattServerScheduler,
                device
        )
    }

    @Throws(SecurityException::class)
    private fun isDisconnected(): Boolean {
        return bluetoothManager
                .getConnectionState(device.bluetoothDevice, BluetoothProfile.GATT) == BluetoothProfile.STATE_DISCONNECTED
    }

    override fun provideException(deadObjectException: DeadObjectException): BleException {
        return BleDisconnectedException(deadObjectException, device.macAddress, BleDisconnectedException.UNKNOWN_STATUS)
    }

    class DisconnectGattServerObservable(
            private val bluetoothGattServer: BluetoothGattServer,
            private val callback: GattServerConnection,
            private val disconnectScheduler: Scheduler,
            private val device: RxBleDevice
    ) : Single<BluetoothGattServer>() {
        override fun subscribeActual(observer: SingleObserver<in BluetoothGattServer>) {
            callback
                    .getOnConnectionStateChange()
                    .filter { pair -> pair.first.macAddress == device.macAddress && pair.second == RxBleConnectionState.DISCONNECTED }
                    .firstOrError()
                    .map { bluetoothGattServer }
                    .subscribe(observer)
            disconnectScheduler.createWorker().schedule {
                try {
                    bluetoothGattServer.cancelConnection(device.bluetoothDevice)
                } catch (exc: SecurityException) {
                    observer.onError(exc)
                }
            }
        }
    }
}