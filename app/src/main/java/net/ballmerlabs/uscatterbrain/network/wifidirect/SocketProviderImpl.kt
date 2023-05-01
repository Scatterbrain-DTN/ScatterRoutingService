package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Scheduler
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.protoUUIDfromUUID
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class SocketProviderImpl @Inject constructor(
        @Named(RoutingServiceComponent.NamedSchedulers.IO) private val operationsScheduler: Scheduler
): SocketProvider {
    override fun getSocket(address: InetAddress, port: Int, luid: UUID): Single<Socket> {
        return Single.fromCallable { Socket(address, port) }
            .map { s ->
                val l = protoUUIDfromUUID(luid)
                l.writeDelimitedTo(s.getOutputStream())
                s
            }
                .subscribeOn(operationsScheduler)
    }
}