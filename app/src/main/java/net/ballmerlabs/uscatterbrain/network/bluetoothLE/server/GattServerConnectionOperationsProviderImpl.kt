package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration
import io.reactivex.Scheduler
import net.ballmerlabs.uscatterbrain.GattServerConnectionScope
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations.NotifyCharacteristicChangedOperation
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations.ServerDisconnectOperation
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations.ServerReplyOperation
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

@GattServerConnectionScope
class GattServerConnectionOperationsProviderImpl @Inject constructor(
        private val server: Provider<BluetoothGattServer>,
        private val timeoutConfiguration: TimeoutConfiguration,
        @Named(RoutingServiceComponent.NamedSchedulers.BLE_SERVER) private val scheduler: Scheduler,
        private val connection: Provider<GattServerConnection>,
        private val manager: BluetoothManager

): GattServerConnectionOperationsProvider {
    override fun provideReplyOperation(device: RxBleDevice, requestID: Int, status: Int, offset: Int, value: ByteArray?): ServerReplyOperation {
        return ServerReplyOperation(
                bluetoothGattServer = server.get(),
                requestID = requestID,
                offset = offset,
                value = value,
                device = device,
                status = status
        )
    }

    override fun provideDisconnectOperation(device: RxBleDevice): ServerDisconnectOperation {
        return ServerDisconnectOperation(
                device = device,
                server = server.get(),
                timeoutConfiguration = timeoutConfiguration,
                callback = connection.get(),
                gattServerScheduler = scheduler,
                bluetoothManager = manager
        )
    }

    override fun provideNotifyOperation(characteristic: BluetoothGattCharacteristic, value: ByteArray?, isIndication: Boolean, device: RxBleDevice): NotifyCharacteristicChangedOperation {
        return NotifyCharacteristicChangedOperation(
                server = server.get(),
                characteristic = characteristic,
                timeoutConfiguration = timeoutConfiguration,
                connection = connection.get(),
                value = value,
                isIndication = isIndication,
                device = device
        )
    }
}