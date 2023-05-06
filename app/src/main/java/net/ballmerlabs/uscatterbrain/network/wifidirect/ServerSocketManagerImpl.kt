package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Scheduler
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.net.ServerSocket
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

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
    override fun getServerSocket(): Single<PortSocket> {
        return Single.fromCallable {
            LOG.v("called getServerSocket")
            val socket = ServerSocket(0)
            PortSocket(
                socket = Single.fromCallable { socket.accept() }
                    .doFinally { socket.close() }
                    .subscribeOn(operationsScheduler),
                port = socket.localPort
            )
        }
            .subscribeOn(operationsScheduler)
            .doOnSuccess { LOG.v("accepted server socket") }
            .doOnError { err ->
                LOG.e("getServerSocket error $err")
                firebaseWrapper.recordException(err)
            }
    }
}