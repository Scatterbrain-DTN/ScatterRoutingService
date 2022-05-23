package net.ballmerlabs.uscatterbrain

import com.polidea.rxandroidble2.RxBleDevice
import dagger.Binds
import dagger.BindsInstance
import dagger.Module
import dagger.Subcomponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.NotificationSetupTransaction
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.NotificationSetupTransactionImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerResponseTransaction
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerResponseTransactionImpl
import java.util.*

@GattServerTransactionScope
@Subcomponent(modules = [ServerTransactionSubcomponent.ServerTransactionModule::class])
interface ServerTransactionSubcomponent {

    class TransactionConfig {
        var value: ByteArray? = null
        var requestID = 0
        var offset = 0
    }

    @Subcomponent.Builder
    interface Builder {
        @BindsInstance
        fun config(config: TransactionConfig): Builder

        @BindsInstance
        fun device(device: RxBleDevice): Builder

        @BindsInstance
        fun characteristic(uuid: UUID): Builder

        fun build(): ServerTransactionSubcomponent
    }

    @Module
    abstract class ServerTransactionModule {
        @Binds
        @GattServerTransactionScope
        abstract fun bindServerResponseTransaction(impl: ServerResponseTransactionImpl): ServerResponseTransaction

        @Binds
        @GattServerTransactionScope
        abstract fun bindNotificationSetupTransaction(impl: NotificationSetupTransactionImpl): NotificationSetupTransaction
    }
}