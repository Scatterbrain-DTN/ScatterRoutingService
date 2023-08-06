package net.ballmerlabs.uscatterbrain

import com.polidea.rxandroidble2.RxBleDevice
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class ScatterbrainTransactionFactoryImpl @Inject constructor(
    private val transactionBuilder: Provider<ScatterbrainTransactionSubcomponent.Builder>
): ScatterbrainTransactionFactory {
    override fun transaction(device: RxBleDevice, luid: UUID): ScatterbrainTransactionSubcomponent {
        return transactionBuilder.get().luid(luid).device(device).build()!!
    }
}