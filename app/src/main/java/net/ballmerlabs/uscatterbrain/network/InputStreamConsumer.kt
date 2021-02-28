package net.ballmerlabs.uscatterbrain.network

import io.reactivex.functions.Consumer
import kotlin.jvm.Throws

class InputStreamConsumer(capacity: Int) : InputStreamCallback(capacity), Consumer<ByteArray?> {
    @Throws(Exception::class)
    override fun accept(bytes: ByteArray?) {
        if (!closed) {
            acceptBytes(bytes!!)
        }
    }
}