package net.ballmerlabs.uscatterbrain

import com.polidea.rxandroidble2.RxBleDevice
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class ScatterbrainTransactionFactoryImpl @Inject constructor(
    private val transactionBuilder: Provider<ScatterbrainTransactionSubcomponent.Builder>
): ScatterbrainTransactionFactory {
    override fun transaction(device: RxBleDevice): ScatterbrainTransactionSubcomponent {
        return transactionBuilder.get().device(device).build()!!
    }
}