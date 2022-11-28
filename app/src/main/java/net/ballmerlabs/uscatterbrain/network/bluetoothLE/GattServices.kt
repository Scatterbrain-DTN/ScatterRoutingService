package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.*

// scatterbrain gatt service object
val gattService =
    BluetoothGattService(BluetoothLERadioModuleImpl.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)


/**
 * shortcut to generate a characteristic with the required permissions
 * and properties and add it to our service.
 * We need PROPERTY_INDICATE to send large protobuf blobs and
 * READ and WRITE for timing / locking
 */
fun makeCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
    val characteristic = BluetoothGattCharacteristic(
        uuid,
        BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_INDICATE,
        BluetoothGattCharacteristic.PERMISSION_WRITE or
                BluetoothGattCharacteristic.PERMISSION_READ
    )
    gattService.addCharacteristic(characteristic)
    return characteristic
}