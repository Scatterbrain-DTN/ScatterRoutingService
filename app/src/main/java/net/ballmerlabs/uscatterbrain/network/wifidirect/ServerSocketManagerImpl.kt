package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.SingleObserver
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.wifidirect.ServerSocketManager.Companion.SCATTERBRAIN_PORT
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.retryDelay
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

class ServerSocketSingle(private val socket: ServerSocket): Single<ServerSocketSingle.SocketConnection>(){
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

/**
 * Accepts TCP sockets in a loop, relaying the accepted connections to an
 * observable for later use
 */
@Singleton
class ServerSocketManagerImpl @Inject constructor(
        private val firebaseWrapper: FirebaseWrapper,
        @Named(RoutingServiceComponent.NamedSchedulers.IO) private val operationsScheduler: Scheduler
        ) : ServerSocketManager {
    private val LOG by scatterLog()
    private val serverSocket = retryDelay(
            Single.fromCallable {
                ServerSocket(SCATTERBRAIN_PORT)
            }.cache(),
            1
    ).doOnError { err -> firebaseWrapper.recordException(err) }

    override fun getServerSocket(): Single<Socket> {
        return serverSocket.flatMap { socket ->
            ServerSocketSingle(socket)
                    .subscribeOn(operationsScheduler)
                    .map { conn -> conn.socket }
                    .doOnSuccess { LOG.v("accepted server socket") }
        }
    }
}