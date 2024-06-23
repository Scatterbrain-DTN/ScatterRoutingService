package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Scheduler
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class SocketProviderImpl @Inject constructor(
        @Named(RoutingServiceComponent.NamedSchedulers.WIFI_CONNECT) private val operationsScheduler: Scheduler
): SocketProvider {
    private val LOG by scatterLog()
    override fun getSocket(address: InetAddress, port: Int, luid: UUID): Single<SocketProvider.DisposableClientSocket> {
        return Single.defer {
            Single.fromCallable {
                LOG.v("getSocket $address, $port")
                SocketProvider.DisposableClientSocket(Socket(address, port))
            }
                .subscribeOn(operationsScheduler)
                .doOnError { err -> LOG.e("getSocket error $err") }
        }
    }
}