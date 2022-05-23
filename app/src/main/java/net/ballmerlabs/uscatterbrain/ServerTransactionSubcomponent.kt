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

    data class TransactionConfig(
        var value: ByteArray? = null,
        var requestID: Int,
        var offset: Int
        ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as TransactionConfig

            if (value != null) {
                if (other.value == null) return false
                if (!value.contentEquals(other.value)) return false
            } else if (other.value != null) return false
            if (requestID != other.requestID) return false
            if (offset != other.offset) return false

            return true
        }

        override fun hashCode(): Int {
            var result = value?.contentHashCode() ?: 0
            result = 31 * result + requestID
            result = 31 * result + offset
            return result
        }
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

    fun serverResponseTransaction(): ServerResponseTransaction

    fun notificationSetupTransaction(): NotificationSetupTransaction
}