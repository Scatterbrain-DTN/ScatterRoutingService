package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import android.bluetooth.BluetoothGattCharacteristic
import com.polidea.rxandroidble2.RxBleDevice
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations.NotifyCharacteristicChangedOperation
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations.ServerDisconnectOperation
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations.ServerReplyOperation

interface GattServerConnectionOperationsProvider {
    fun provideReplyOperation(
            device: RxBleDevice,
            requestID: Int,
            status: Int,
            offset: Int,
            value: ByteArray?
    ): ServerReplyOperation

    fun provideDisconnectOperation(device: RxBleDevice): ServerDisconnectOperation

    fun provideNotifyOperation(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            isIndication: Boolean,
            device: RxBleDevice
    ): NotifyCharacteristicChangedOperation
}