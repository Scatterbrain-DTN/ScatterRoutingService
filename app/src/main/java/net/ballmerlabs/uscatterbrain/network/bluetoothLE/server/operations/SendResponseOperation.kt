package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import android.bluetooth.BluetoothDevice
import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection

class SendResponseOperation(
    gattServerConnection: GattServerConnection,
    timeoutConfiguration: TimeoutConfiguration,
    private val mac: BluetoothDevice,
    private val data: ByteArray,
    private val status: Int,
    private val requestId: Int
) : SingleResponseOperation<ByteArray>(
    gattServerConnection,
    GattServerOperationType.SEND_RESPONSE,
    timeoutConfiguration,
    mac
) {
    override fun getCallback(rxBleGattCallback: GattServerConnection): Single<ByteArray> {
        return Single.just(data)
    }

    override fun startOperation(bluetoothGatt: GattServerConnection): Boolean {
        return bluetoothGatt.server().sendResponse(mac, requestId,  status, 0, data)
    }

    override fun toString(): String {
        return ("SendReplyOperation{"
                + super.toString()
                + '}')
    }
}
