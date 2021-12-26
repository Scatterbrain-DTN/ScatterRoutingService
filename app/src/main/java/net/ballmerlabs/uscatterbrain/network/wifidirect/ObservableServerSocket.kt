package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.util.Log
import io.reactivex.Observable
import io.reactivex.Single
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * Accepts TCP sockets in a loop, relaying the accepted connections to an
 * observable for later use
 * @param port port to listen on
 */
class ObservableServerSocket(port: Int) : ServerSocket(port) {
    private var closed = false

    class SocketConnection(val socket: Socket)

    class InterceptableServerSocketFactory {
        private var serverSocket: ObservableServerSocket? = null
        fun create(port: Int): Single<ObservableServerSocket> {
            return Single.fromCallable {
                if (serverSocket == null) {
                    serverSocket = ObservableServerSocket(port)
                }
                Log.v(WifiDirectRadioModule.TAG, "creating server socket")
                serverSocket
            }
        }
    }

    /**
     * Accepts socket connections in a loop and returns an observable yielding
     * each accepted connection
     * @return observable returning each connection as a SocketConnection
     */
    fun acceptLoop(): Observable<SocketConnection> {
        return Observable.fromCallable { SocketConnection(accept()) }
            .doOnError { err -> Log.e(WifiDirectRadioModule.TAG, "error on socket accept: $err") }
            .doFinally { close() }
            .repeatUntil { closed }
    }

    /**
     * Accepts a single socket connection using a blocking call
     * @return socket
     */
    @Throws(IOException::class)
    override fun accept(): Socket {
        return try {
            val socket = super.accept()
            socket
        } catch (e: IOException) {
            throw e
        }
    }

    /**
     * Stops listening on the tcp port and calls onComplete on any observable returned
     * by acceptLoop()
     */
    @Throws(IOException::class)
    override fun close() {
        closed = true
        super.close()
    }
}