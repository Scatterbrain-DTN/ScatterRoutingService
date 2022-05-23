package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions

import android.bluetooth.BluetoothDevice
import com.polidea.rxandroidble2.RxBleDevice
import java.util.*

interface ServerTransactionFactory {
    fun prepareCharacteristicTransaction(
            value: ByteArray?,
            requestID: Int,
            offset: Int,
            device: BluetoothDevice,
            characteristic: UUID
    ): ServerResponseTransaction

    fun prepareNotificationSetupTransaction(
            device: BluetoothDevice,
            characteristic: UUID
    ): NotificationSetupTransaction

    fun prepareCharacteristicTransaction(
            value: ByteArray?,
            requestID: Int,
            offset: Int,
            device: RxBleDevice,
            characteristic: UUID
    ): ServerResponseTransaction

    fun prepareNotificationSetupTransaction(
            device: RxBleDevice,
            characteristic: UUID
    ): NotificationSetupTransaction
}