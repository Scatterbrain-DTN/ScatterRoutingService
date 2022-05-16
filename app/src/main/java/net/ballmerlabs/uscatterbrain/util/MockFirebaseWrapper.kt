package net.ballmerlabs.uscatterbrain.util

class MockFirebaseWrapper: FirebaseWrapper {
    override fun recordException(exception: Throwable) {
        //donothing
    }
}