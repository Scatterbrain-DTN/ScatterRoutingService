package net.ballmerlabs.uscatterbrain.network

import io.reactivex.Observer
import io.reactivex.disposables.Disposable
import java.io.IOException

/**
 * serves as a bridge between an observable and an inputstream
 * using a static sized buffer to hold unread data
 */
class InputStreamObserver(capacity: Int) : InputStreamCallback(capacity), Observer<ByteArray> {

    override fun onSubscribe(d: Disposable) {

    }

    override fun onNext(bytes: ByteArray) {
        if (!closed) {
            acceptBytes(bytes)
        }
    }

    override fun onError(e: Throwable) {
        throwable = e
        try {
            close()
        } catch (ignored: IOException) {
        }
    }

    override fun onComplete() {
    }
}