package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration
import net.ballmerlabs.uscatterbrain.GattServerConnectionScope
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LeState
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.ServerOperationsProvider
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.ServerState
import javax.inject.Inject
import javax.inject.Provider

@GattServerConnectionScope
class ServerOperationsProviderImpl @Inject constructor(
    private val gattServerConnection: GattServerConnection,
    private val timeoutConfiguration: TimeoutConfiguration,
    private val serverState: ServerState,
    private val leState: Provider<LeState>,
    private val manager: BluetoothManager
) : ServerOperationsProvider {

    override fun provideSendResponseOperation(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        data: ByteArray
    ): SendResponseOperation {
        return SendResponseOperation(
            gattServerConnection,
            timeoutConfiguration,
            device,
            data,
            status,
            requestId
        )
    }

    override fun provideNotifyCharacteristicChangedOperation(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        isIndication: Boolean,
        data: ByteArray
    ): NotifyCharacteristicChangedOperation {
        return NotifyCharacteristicChangedOperation(
            gattServerConnection,
            timeoutConfiguration,
            serverState,
            device,
            data,
            characteristic,
            isIndication
        )
    }

    override fun provideDisconnectOperation(device: BluetoothDevice): DisconnectOperation {
        return DisconnectOperation(
            gattServerConnection,
            leState.get(),
            timeoutConfiguration,
            device,
            manager
        )
    }
}