package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions

import android.bluetooth.BluetoothGattCharacteristic
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Completable
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import java.util.UUID

interface ServerResponseTransaction {
    val requestID: Int
    val offset: Int
    val value: ByteArray?
    val characteristic: BluetoothGattCharacteristic
    val remoteDevice: RxBleDevice
    val operation: GattServerConnection.Operation
    val uuid: UUID
    fun sendReply(value: ByteArray?, status: Int): Completable
}