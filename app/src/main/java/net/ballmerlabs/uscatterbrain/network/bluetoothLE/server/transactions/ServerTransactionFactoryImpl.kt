package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions

import android.bluetooth.BluetoothGattCharacteristic
import com.polidea.rxandroidble2.RxBleDevice
import net.ballmerlabs.uscatterbrain.GattServerConnectionScope
import net.ballmerlabs.uscatterbrain.ServerTransactionSubcomponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider

@GattServerConnectionScope
class ServerTransactionFactoryImpl @Inject constructor(
    val builder: Provider<ServerTransactionSubcomponent.Builder>
) : ServerTransactionFactory {

    override fun prepareCharacteristicTransaction(
        value: ByteArray?,
        requestID: Int,
        offset: Int,
        device: RxBleDevice,
        uuid: UUID,
        characteristic: BluetoothGattCharacteristic,
        operation: GattServerConnection.Operation
    ): ServerResponseTransaction {
        val config = ServerTransactionSubcomponent.TransactionConfig(
            value = value,
            requestID = requestID,
            offset = offset,
            operation = operation,
            characteristic = characteristic
        )
        return builder.get()
            .config(config)
            .characteristic(uuid)
            .device(device)
            .build()
            .serverResponseTransaction()
    }

    override fun prepareNotificationSetupTransaction(
        device: RxBleDevice,
        characteristic: UUID
    ): NotificationSetupTransaction {
        return builder.get()
            .device(device)
            .characteristic(characteristic)
            .build().notificationSetupTransaction()
    }
}