package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import android.bluetooth.BluetoothGattServer
import android.os.DeadObjectException
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.exceptions.BleException
import com.polidea.rxandroidble2.exceptions.BleGattServerException
import com.polidea.rxandroidble2.exceptions.BleGattServerOperationType
import com.polidea.rxandroidble2.internal.QueueOperation
import com.polidea.rxandroidble2.internal.serialization.QueueReleaseInterface
import com.polidea.rxandroidble2.internal.util.QueueReleasingEmitterWrapper
import io.reactivex.Observable
import io.reactivex.ObservableEmitter

class ServerReplyOperation(
        private val bluetoothGattServer: BluetoothGattServer,
                private val requestID: Int,
                private val offset: Int,
                private val value: ByteArray?,
                private val status: Int,
                private val device: RxBleDevice
): QueueOperation<Boolean>() {
   

    override fun protectedRun(emitter: ObservableEmitter<Boolean>, queueReleaseInterface: QueueReleaseInterface) {
        val emitterWrapper = QueueReleasingEmitterWrapper(emitter, queueReleaseInterface)
        Observable.fromCallable {
            try {
                bluetoothGattServer.sendResponse(device.bluetoothDevice, requestID, status, offset, value)
            } catch (exc: SecurityException) {
                throw exc
            }
        }.subscribe(emitterWrapper)
    }

    override fun provideException(deadObjectException: DeadObjectException): BleException {
        return BleGattServerException(BleGattServerOperationType.REPLY, "ServerReplyOperation failed")
    }
}