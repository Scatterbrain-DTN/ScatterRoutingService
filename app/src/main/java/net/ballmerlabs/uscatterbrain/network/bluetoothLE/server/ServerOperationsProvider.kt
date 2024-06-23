package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations.DisconnectOperation
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations.NotifyCharacteristicChangedOperation
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations.SendResponseOperation

interface ServerOperationsProvider {
    fun provideSendResponseOperation(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        data: ByteArray
    ): SendResponseOperation

    fun provideNotifyCharacteristicChangedOperation(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        isIndication: Boolean,
        data: ByteArray
    ): NotifyCharacteristicChangedOperation

    fun provideDisconnectOperation(
        device: BluetoothDevice
    ): DisconnectOperation
}