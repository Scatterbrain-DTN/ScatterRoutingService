package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import com.polidea.rxandroidble2.RxBleClient.NotificationStatus
import net.ballmerlabs.uscatterbrain.GattServerConnectionScope
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@GattServerConnectionScope
class ServerStateImpl @Inject constructor(): ServerState {
    private val notificationState = ConcurrentHashMap<UUID, NotificationStatus>()
    private val characteristicMap = ConcurrentHashMap<UUID, BluetoothGattCharacteristic>()
    override fun getDescriptor(characteristic: UUID, uuid: UUID): BluetoothGattDescriptor {
        TODO("Not yet implemented")
    }

    override fun getNotificationValue(uuid: UUID): ByteArray {
        val status = notificationState[uuid]
        return if (status != null) {
            when (status) {
                NotificationStatus.INDICATIONS_ENABLED -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                NotificationStatus.NOTIFICATIONS_ENABLED -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
    }

    override fun getNotificationStatus(characteristic: UUID): NotificationStatus {
        return notificationState[characteristic]!!
    }

    override fun setNotifications(characteristic: UUID, value: ByteArray) {
        if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
            enableNotifications(characteristic)
        } else if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
            enableIndications(characteristic)
        } else {
            disableNotifications(characteristic)
        }
    }

    override fun getIndications(uuid: UUID): Boolean {
        val status = notificationState[uuid]
        return status == NotificationStatus.INDICATIONS_ENABLED
    }

    override fun getNotifications(uuid: UUID): Boolean {
        val status = notificationState[uuid]
        return status == NotificationStatus.NOTIFICATIONS_ENABLED
    }

    override fun enableNotifications(uuid: UUID) {
        notificationState[uuid] = NotificationStatus.NOTIFICATIONS_ENABLED
    }

    override fun enableIndications(uuid: UUID) {
        notificationState[uuid] = NotificationStatus.INDICATIONS_ENABLED
    }

    override fun disableNotifications(uuid: UUID) {
        notificationState[uuid] = NotificationStatus.NOTIFICATIONS_INDICATIONS_DISABLED
    }

    override fun getCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
        return characteristicMap[uuid]!!
    }

    override fun addCharacteristic(uuid: UUID, characteristic: BluetoothGattCharacteristic) {
        characteristicMap[uuid] = characteristic
    }


}