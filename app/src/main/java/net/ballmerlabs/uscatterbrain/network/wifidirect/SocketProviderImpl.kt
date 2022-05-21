package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Scheduler
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import java.net.InetAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class SocketProviderImpl @Inject constructor(
        @Named(RoutingServiceComponent.NamedSchedulers.OPERATIONS) private val operationsScheduler: Scheduler
): SocketProvider {
    override fun getSocket(address: InetAddress, port: Int): Single<Socket> {
        return Single.fromCallable { Socket(address, port) }
                .subscribeOn(operationsScheduler)
    }
}