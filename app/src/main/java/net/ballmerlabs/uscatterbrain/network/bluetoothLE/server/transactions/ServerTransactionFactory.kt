package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions

import android.bluetooth.BluetoothGattCharacteristic
import com.polidea.rxandroidble2.RxBleDevice
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import java.util.UUID

interface ServerTransactionFactory {
    fun prepareCharacteristicTransaction(
            value: ByteArray?,
            requestID: Int,
            offset: Int,
            device: RxBleDevice,
            uuid: UUID,
            characteristic: BluetoothGattCharacteristic,
            operation: GattServerConnection.Operation
    ): ServerResponseTransaction

    fun prepareNotificationSetupTransaction(
            device: RxBleDevice,
            characteristic: UUID
    ): NotificationSetupTransaction
}