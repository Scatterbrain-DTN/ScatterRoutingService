package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LeState
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection

class DisconnectOperation(
    private val gattServerConnection: GattServerConnection,
    private val leState: LeState,
    timeoutConfiguration: TimeoutConfiguration,
    private val mac: BluetoothDevice,
    private val bluetoothManager: BluetoothManager
) : SingleResponseOperation<Unit>(
    gattServerConnection,
    GattServerOperationType.DISCONNECT,
    timeoutConfiguration,
    mac
) {

    private fun isDisconnected(device: BluetoothDevice): Boolean {
        return try {
            bluetoothManager.getConnectionState(
                device,
                BluetoothProfile.GATT
            ) == BluetoothProfile.STATE_DISCONNECTED
        } catch (exc: SecurityException) {
            true
        }
    }
    override fun getCallback(rxBleGattCallback: GattServerConnection): Single<Unit> {
        return Single.defer {
            if (isDisconnected(mac)) {
                Single.just(Unit)
            } else {
                gattServerConnection.observeDisconnect()
                    .mergeWith(leState.observeDisconnects())
                    .filter { d -> d.macAddress == mac.address }
                    .firstOrError()
                    .map { }
            }
        }

    }

    @Throws(SecurityException::class)
    override fun startOperation(bluetoothGatt: GattServerConnection): Boolean {
        gattServerConnection.server().cancelConnection(mac)
        return true
    }

    override fun toString(): String {
        return ("ServerDisconnectOperation{"
                + super.toString()
                + '}')
    }
}