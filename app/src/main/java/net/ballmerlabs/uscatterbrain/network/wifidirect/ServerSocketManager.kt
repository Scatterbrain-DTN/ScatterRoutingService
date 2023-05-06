package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Single
import io.reactivex.disposables.Disposable
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

data class DisposableSocket(
    val socket: Socket,
    val serverSocket: ServerSocket
): Disposable {
    override fun dispose() {
        socket.close()
        serverSocket.close()
    }

    override fun isDisposed(): Boolean {
        return socket.isClosed && serverSocket.isClosed
    }
}
data class PortSocket(
    val port: Int,
    val socket: Single<DisposableSocket>
)

interface ServerSocketManager {

    fun getServerSocket(): Single<PortSocket>
}