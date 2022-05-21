package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Single
import java.net.InetAddress
import java.net.Socket

interface SocketProvider {
    fun getSocket(address: InetAddress, port: Int): Single<Socket>
}