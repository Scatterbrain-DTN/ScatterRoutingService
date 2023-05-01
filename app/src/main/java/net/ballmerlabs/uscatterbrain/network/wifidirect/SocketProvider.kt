package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Single
import java.net.InetAddress
import java.net.Socket
import java.util.UUID

interface SocketProvider {
    fun getSocket(address: InetAddress, port: Int, luid: UUID): Single<Socket>
}