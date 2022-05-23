package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions

import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.exceptions.BleException
import com.polidea.rxandroidble2.internal.serialization.ServerOperationQueue
import io.reactivex.Completable
import net.ballmerlabs.uscatterbrain.GattServerTransactionScope
import net.ballmerlabs.uscatterbrain.ServerTransactionSubcomponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnectionOperationsProvider
import javax.inject.Inject

@GattServerTransactionScope
class ServerResponseTransactionImpl @Inject constructor(
        private val operationQueue: ServerOperationQueue,
        val device: RxBleDevice,
        private val config: ServerTransactionSubcomponent.TransactionConfig,
        private val operationsProvider: GattServerConnectionOperationsProvider
): ServerResponseTransaction {
    override val requestID: Int
        get() = config.requestID
    override val offset: Int
        get() = config.offset
    override val value: ByteArray?
        get() = config.value


    override fun sendReply(value: ByteArray?, status: Int): Completable {
        return operationQueue.queue(operationsProvider.provideReplyOperation(
                device,
                config.requestID,
                status,
                config.offset,
                value
        )).flatMapCompletable { v ->
            if(v) Completable.complete() else Completable.error(BleException("reply failed"))
        }
    }
}