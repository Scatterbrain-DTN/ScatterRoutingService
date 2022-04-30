package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Single
import io.reactivex.SingleObserver
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.net.ServerSocket
import java.net.Socket

/**
 * Accepts TCP sockets in a loop, relaying the accepted connections to an
 * observable for later use
 * @param socket serversocket to listen with
 */
class SingleServerSocket(private val socket: ServerSocket) : Single<SingleServerSocket.SocketConnection>() {
    private val LOG by scatterLog()
    class SocketConnection(val socket: Socket)

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
            .doOnError { err -> LOG.e("error on socket accept: $err") }
    }

    override fun subscribeActual(observer: SingleObserver<in SocketConnection>) {
        acceptLoop().subscribe(observer)
    }
}