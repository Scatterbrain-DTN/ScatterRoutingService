package net.ballmerlabs.uscatterbrain.util

import com.google.firebase.crashlytics.FirebaseCrashlytics
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseWrapperImpl @Inject constructor(): FirebaseWrapper {
    override fun recordException(exception: Throwable) {
        FirebaseCrashlytics.getInstance().recordException(exception)
    }
}