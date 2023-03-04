package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions

import android.bluetooth.BluetoothGattServer
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Completable
import io.reactivex.Scheduler
import net.ballmerlabs.uscatterbrain.GattServerTransactionScope
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.ServerTransactionSubcomponent
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

@GattServerTransactionScope
class ServerResponseTransactionImpl @Inject constructor(
        override val remoteDevice: RxBleDevice,
        private val config: ServerTransactionSubcomponent.TransactionConfig,
        private val server: Provider<BluetoothGattServer>,
        @Named(RoutingServiceComponent.NamedSchedulers.BLE_SERVER) private val scheduler: Scheduler
): ServerResponseTransaction {
    override val requestID: Int
        get() = config.requestID
    override val offset: Int
        get() = config.offset
    override val value: ByteArray?
        get() = config.value


    override fun sendReply(value: ByteArray?, status: Int): Completable {
        return Completable.fromAction {
            try {
                server.get().sendResponse(
                    remoteDevice.bluetoothDevice,
                    config.requestID,
                    status,
                    config.offset,
                    value
                )
            } catch (exc: SecurityException) {
                throw exc
            }
        }.subscribeOn(scheduler)
    }
}