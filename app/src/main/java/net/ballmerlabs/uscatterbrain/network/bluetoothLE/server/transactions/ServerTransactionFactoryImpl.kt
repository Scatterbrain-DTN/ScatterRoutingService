package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions

import android.bluetooth.BluetoothDevice
import com.polidea.rxandroidble2.*
import net.ballmerlabs.uscatterbrain.GattServerConnectionScope
import net.ballmerlabs.uscatterbrain.ServerTransactionSubcomponent
import java.util.*
import javax.inject.Inject
import javax.inject.Provider

@GattServerConnectionScope
class ServerTransactionFactoryImpl @Inject constructor(
        val builder: Provider<ServerTransactionSubcomponent.Builder>
): ServerTransactionFactory {
    override fun prepareCharacteristicTransaction(value: ByteArray?, requestID: Int, offset: Int, device: BluetoothDevice, characteristic: UUID): ServerResponseTransaction {
        TODO("Not yet implemented")
    }

    override fun prepareCharacteristicTransaction(value: ByteArray?, requestID: Int, offset: Int, device: RxBleDevice, characteristic: UUID): ServerResponseTransaction {
        TODO("Not yet implemented")
    }

    override fun prepareNotificationSetupTransaction(device: BluetoothDevice, characteristic: UUID): NotificationSetupTransaction {
        TODO("Not yet implemented")
    }

    override fun prepareNotificationSetupTransaction(device: RxBleDevice, characteristic: UUID): NotificationSetupTransaction {
        TODO("Not yet implemented")
    }


}