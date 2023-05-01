package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Observable
import io.reactivex.Observer
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.SingleObserver
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.ScatterProto.UUID
import net.ballmerlabs.uscatterbrain.network.protoUUIDtoUUID
import net.ballmerlabs.uscatterbrain.network.wifidirect.ServerSocketManager.Companion.SCATTERBRAIN_PORT
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.retryDelay
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

class ServerSocketSingle(private val socket: ServerSocket) :
    Observable<ServerSocketSingle.SocketConnection>() {
    private val LOG by scatterLog()

    class SocketConnection(val socket: Socket)

    /**
     * Accepts socket connections in a loop and returns an observable yielding
     * each accepted connection
     * @return observable returning each connection as a SocketConnection
     */
    private fun acceptLoop(): Observable<SocketConnection> {
        return Single.fromCallable {
            val sock = socket.accept()
            SocketConnection(socket = sock)
        }.repeat().toObservable()
            .doOnError { err -> LOG.e("error on socket accept: $err") }
    }

    override fun subscribeActual(observer: Observer<in SocketConnection>) {
        acceptLoop().subscribe(observer)
    }
}

data class LuidSocket(
    val luid: java.util.UUID,
    val socket: Socket
)

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
        }.subscribeOn(operationsScheduler).cache(),
        1
    ).doOnError { err -> firebaseWrapper.recordException(err) }

    private val socketSubject = BehaviorSubject.create<LuidSocket>()

    private val socketStream = serverSocket.flatMapObservable { socket ->
        ServerSocketSingle(socket)
            .subscribeOn(operationsScheduler)
            .map { conn -> conn.socket }
            .map { s ->
                LuidSocket(
                    luid = protoUUIDtoUUID(UUID.parseDelimitedFrom(s.getInputStream())),
                    socket = s
                )
            }
            .doOnError { err -> LOG.e("server socket error $err") }
            .repeat().retry()
    }

    override fun getServerSocket(luid: java.util.UUID): Single<Socket> {
        LOG.v("called getServerSocket")
        return socketSubject.filter { v -> v.luid == luid }
            .map { v -> v.socket }
            .firstOrError()
            .subscribeOn(operationsScheduler)
            .doOnSuccess { LOG.v("accepted server socket") }
    }

    init {
        socketStream.subscribe(socketSubject)
    }
}