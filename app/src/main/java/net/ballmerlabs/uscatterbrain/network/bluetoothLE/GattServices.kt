package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.util.Log
import net.ballmerlabs.scatterproto.incrementUUID
import java.util.UUID

// scatterbrain gatt service object
val gattService =
    BluetoothGattService(BluetoothLERadioModuleImpl.SERVICE_UUID_LEGACY, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        makeCharacteristic(this, BluetoothLERadioModuleImpl.UUID_SEMAPHOR)
        makeCharacteristic(this, BluetoothLERadioModuleImpl.UUID_HELLO)
        makeCharacteristic(this, BluetoothLERadioModuleImpl.UUID_FORGET)
        makeCharacteristic(this, BluetoothLERadioModuleImpl.UUID_REVERSE)
        makeChannels(this)
    }

/**
 * shortcut to generate a characteristic with the required permissions
 * and properties and add it to our service.
 * We need PROPERTY_INDICATE to send large protobuf blobs and
 * READ and WRITE for timing / locking
 */
fun makeCharacteristic(gattService: BluetoothGattService, uuid: UUID): BluetoothGattCharacteristic {
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

fun makeChannels(gattService: BluetoothGattService) {
    for (i in 0 until BluetoothLERadioModuleImpl.NUM_CHANNELS) {
        val channel = incrementUUID(
            BluetoothLERadioModuleImpl.SERVICE_UUID_LEGACY,
            i + 1
        )
        Log.w("debug", "makeChannels $channel")
        makeCharacteristic(gattService,channel)
    }
}
