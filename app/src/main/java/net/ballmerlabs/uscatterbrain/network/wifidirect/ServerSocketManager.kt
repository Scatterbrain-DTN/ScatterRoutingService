package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Single
import java.net.Socket
import java.util.UUID

data class PortSocket(
    val port: Int,
    val socket: Single<Socket>
)

interface ServerSocketManager {

    fun getServerSocket(): Single<PortSocket>
}