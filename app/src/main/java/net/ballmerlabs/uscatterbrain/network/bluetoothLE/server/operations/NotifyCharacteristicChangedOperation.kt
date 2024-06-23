package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.ServerState

class NotifyCharacteristicChangedOperation(
    private val gattServerConnection: GattServerConnection,
    timeoutConfiguration: TimeoutConfiguration,
    private val serverState: ServerState,
    private val mac: BluetoothDevice,
    private val data: ByteArray,
    private val characteristic: BluetoothGattCharacteristic,
    private val isIndication: Boolean
) : SingleResponseOperation<Int>(
    gattServerConnection,
    GattServerOperationType.NOTIFY,
    timeoutConfiguration,
    mac
) {
    fun tryNotify(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        isIndication: Boolean,
        bytes: ByteArray
    ): Boolean {
        return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gattServerConnection.server().notifyCharacteristicChanged(
                        device,
                        characteristic,
                        isIndication,
                        bytes
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    characteristic.value = bytes
                    serverState.getCharacteristic(characteristic.uuid).value =
                        bytes
                    gattServerConnection.server().notifyCharacteristicChanged(
                        device,
                        characteristic,
                        isIndication
                    )
                }
        } catch (sec: SecurityException) {
            throw sec
        }
    }

    override fun getCallback(rxBleGattCallback: GattServerConnection): Single<Int> {
        return gattServerConnection.getOnNotification(mac.address)
            .firstOrError()

    }

    override fun startOperation(bluetoothGatt: GattServerConnection): Boolean {
        return tryNotify(mac, characteristic, isIndication, data)
    }

    override fun toString(): String {
        return ("SendReplyOperation{"
                + super.toString()
                + '}')
    }
}