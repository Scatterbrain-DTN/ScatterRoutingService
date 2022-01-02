package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.util.Log
import io.reactivex.Single
import io.reactivex.SingleObserver
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * Accepts TCP sockets in a loop, relaying the accepted connections to an
 * observable for later use
 * @param port port to listen on
 */
class SingleServerSocket(private val socket: ServerSocket) : Single<SingleServerSocket.SocketConnection>() {
    private var connection = AtomicReference<Socket?>(null)
    class SocketConnection(val socket: Socket)

    private fun cleanup(socket: Socket?) {
        socket?.getOutputStream()?.flush()
        socket?.shutdownOutput()
        socket?.close()
    }

    /**
     * Accepts socket connections in a loop and returns an observable yielding
     * each accepted connection
     * @return observable returning each connection as a SocketConnection
     */
    private fun acceptLoop(): Single<SocketConnection> {
        return fromCallable {
            val sock = socket.accept()
            SocketConnection(socket = sock)
        }
            .doOnError { err -> Log.e(WifiDirectRadioModule.TAG, "error on socket accept: $err") }
            .doFinally {
                Log.e("debug", "socket cleanup")
                cleanup(connection.get())
            }
    }

    override fun subscribeActual(observer: SingleObserver<in SocketConnection>) {
        acceptLoop().subscribe(observer)
    }
}