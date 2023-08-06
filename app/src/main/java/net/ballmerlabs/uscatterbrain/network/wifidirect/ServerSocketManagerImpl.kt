package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Scheduler
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
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
    @Named(RoutingServiceComponent.NamedSchedulers.GLOBAL_IO) private val operationsScheduler: Scheduler
) : ServerSocketManager {
    private val LOG by scatterLog()
    private val serverSocket = AtomicReference(ServerSocket(0,32, InetAddress.getByName("0.0.0.0")))
    override fun getServerSocket(): Single<PortSocket> {
        return Single.fromCallable {
            LOG.v("called getServerSocket")
            PortSocket(
                socket = serverSocket.get(),
                scheduler = operationsScheduler
            )
        }
            .subscribeOn(operationsScheduler)
            .doOnError { err ->
                LOG.e("getServerSocket error $err")
                firebaseWrapper.recordException(err)
            }
    }

    override fun randomizePort() {
        serverSocket.set(ServerSocket(0,32, InetAddress.getByName("0.0.0.0")))
    }
}