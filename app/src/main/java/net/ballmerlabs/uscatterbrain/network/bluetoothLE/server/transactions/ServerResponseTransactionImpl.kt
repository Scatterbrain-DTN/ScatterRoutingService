package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Completable
import net.ballmerlabs.uscatterbrain.GattServerTransactionScope
import net.ballmerlabs.uscatterbrain.ServerTransactionSubcomponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.ServerOperationsProvider
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations.ServerConnectionOperationQueue
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider

@GattServerTransactionScope
class ServerResponseTransactionImpl @Inject constructor(
    override val remoteDevice: RxBleDevice,
    private val config: ServerTransactionSubcomponent.TransactionConfig,
    private val server: Provider<BluetoothGattServer>,
    private val operationQueue: ServerConnectionOperationQueue,
    private val operationsProvider: ServerOperationsProvider,
    override val uuid: UUID,
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
        return Completable.defer {
            try {
                operationQueue.queue(operationsProvider.provideSendResponseOperation(
                    remoteDevice.bluetoothDevice,
                    config.requestID,
                    status,
                    value?: byteArrayOf()
                )).ignoreElements()
            } catch (exc: SecurityException) {
                throw exc
            }
        }
    }
}