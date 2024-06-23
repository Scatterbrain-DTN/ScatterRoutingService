package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Single
import java.net.InetAddress
import java.net.Socket
import java.util.UUID

interface SocketProvider {
    data class DisposableClientSocket(val socket: Socket) {
        protected fun finalize() {
          //  socket.close()
        }
    }
    fun getSocket(address: InetAddress, port: Int, luid: UUID): Single<DisposableClientSocket>
}