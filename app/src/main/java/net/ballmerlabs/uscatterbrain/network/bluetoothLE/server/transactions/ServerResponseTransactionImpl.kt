package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Completable
import io.reactivex.Scheduler
import net.ballmerlabs.uscatterbrain.GattServerTransactionScope
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.ServerTransactionSubcomponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

@GattServerTransactionScope
class ServerResponseTransactionImpl @Inject constructor(
        override val remoteDevice: RxBleDevice,
        private val config: ServerTransactionSubcomponent.TransactionConfig,
        private val server: Provider<BluetoothGattServer>,
        override val uuid: UUID,
        @Named(RoutingServiceComponent.NamedSchedulers.BLE_SERVER) private val scheduler: Scheduler
): ServerResponseTransaction {
    override val requestID: Int
        get() = config.requestID
    override val offset: Int
        get() = config.offset
    override val value: ByteArray?
        get() = config.value
    override val operation: GattServerConnection.Operation
        get() = config.operation
    override val characteristic: BluetoothGattCharacteristic
        get() = config.characteristic

    override fun sendReply(value: ByteArray?, status: Int): Completable {
        return Completable.fromAction {
            try {
                val res = server.get().sendResponse(
                    remoteDevice.bluetoothDevice,
                    config.requestID,
                    status,
                    config.offset,
                    value
                )
                if (!res) {
                    throw IllegalStateException("sendResponse returned false")
                }
            } catch (exc: SecurityException) {
                throw exc
            }
        }.subscribeOn(scheduler)
    }
}