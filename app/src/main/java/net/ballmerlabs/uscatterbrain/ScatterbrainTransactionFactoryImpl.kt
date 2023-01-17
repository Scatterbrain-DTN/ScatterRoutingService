package net.ballmerlabs.uscatterbrain

import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class ScatterbrainTransactionFactoryImpl @Inject constructor(
    private val transactionBuilder: Provider<ScatterbrainTransactionSubcomponent.Builder>
): ScatterbrainTransactionFactory {
    override fun transaction(): ScatterbrainTransactionSubcomponent {
        return transactionBuilder.get().build()!!
    }
}