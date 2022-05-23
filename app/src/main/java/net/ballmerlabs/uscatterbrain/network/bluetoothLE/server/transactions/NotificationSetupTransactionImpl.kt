package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions

import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Completable
import io.reactivex.Flowable
import net.ballmerlabs.uscatterbrain.GattServerTransactionScope
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import java.util.*
import javax.inject.Inject

@GattServerTransactionScope
class NotificationSetupTransactionImpl @Inject constructor(
        override val device: RxBleDevice,
        val connection: GattServerConnection,
        val characteristic: UUID
): NotificationSetupTransaction {
    override fun notify(flowable: Flowable<ByteArray>): Completable {
        return connection.setupNotifications(characteristic, flowable, device)
    }

    override fun indicate(flowable: Flowable<ByteArray>): Completable {
        return connection.setupIndication(characteristic, flowable, device)
    }
}