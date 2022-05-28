package net.ballmerlabs.uscatterbrain

import dagger.Binds
import dagger.Module
import dagger.Subcomponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.NotificationSetupTransaction
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.NotificationSetupTransactionImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerResponseTransaction
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerResponseTransactionImpl

@GattServerTransactionScope
@Subcomponent(modules = [FakeServerTransactionSubcomponent.ServerTransactionModule::class])
interface FakeServerTransactionSubcomponent: ServerTransactionSubcomponent {
    @Subcomponent.Builder
    interface Builder: ServerTransactionSubcomponent.Builder {
        override fun build(): ServerTransactionSubcomponent
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

    override fun serverResponseTransaction(): ServerResponseTransaction

    override fun notificationSetupTransaction(): NotificationSetupTransaction
}