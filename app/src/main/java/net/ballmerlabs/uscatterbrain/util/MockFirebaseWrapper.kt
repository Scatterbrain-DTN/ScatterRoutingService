package net.ballmerlabs.uscatterbrain.util

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockFirebaseWrapper @Inject constructor(): FirebaseWrapper {
    override fun recordException(exception: Throwable) {
        //donothing
    }
}