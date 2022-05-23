package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import java.util.*

enum class NotificationStatus {
    NOTIFICATIONS_INDICATIONS_DISABLED, NOTIFICATIONS_ENABLED, INDICATIONS_ENABLED
}

interface ServerState {
    fun getDescriptor(characteristic: UUID, uuid: UUID): BluetoothGattDescriptor
    fun getNotificationValue(uuid: UUID): ByteArray
    fun getNotificationStatus(characteristic: UUID): NotificationStatus
    fun setNotifications(characteristic: UUID, value: ByteArray)
    fun getIndications(uuid: UUID): Boolean
    fun getNotifications(uuid: UUID): Boolean
    fun enableNotifications(uuid: UUID)
    fun enableIndications(uuid: UUID)
    fun disableNotifications(uuid: UUID)
    fun getCharacteristic(uuid: UUID): BluetoothGattCharacteristic
    fun addCharacteristic(uuid: UUID, characteristic: BluetoothGattCharacteristic)
}