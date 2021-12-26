package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.util.Log
import io.reactivex.Observable
import io.reactivex.Observer
import java.net.ServerSocket
import java.net.Socket

/**
 * Accepts TCP sockets in a loop, relaying the accepted connections to an
 * observable for later use
 * @param port port to listen on
 */
class ObservableServerSocket(port: Int) : Observable<ObservableServerSocket.SocketConnection>() {
    private var closed = false
    private val socket = ServerSocket(port)
    class SocketConnection(val socket: Socket)

    /**
     * Accepts socket connections in a loop and returns an observable yielding
     * each accepted connection
     * @return observable returning each connection as a SocketConnection
     */
    private fun acceptLoop(): Observable<SocketConnection> {
        return fromCallable { SocketConnection(socket = socket.accept()) }
            .doOnError { err -> Log.e(WifiDirectRadioModule.TAG, "error on socket accept: $err") }
            .doFinally { socket.close() }
            .repeatUntil { closed }
    }

    override fun subscribeActual(observer: Observer<in SocketConnection>) {
        acceptLoop().subscribe(observer)
    }
}