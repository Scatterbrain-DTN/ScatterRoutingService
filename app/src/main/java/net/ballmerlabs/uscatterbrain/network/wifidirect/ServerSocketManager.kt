package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit

data class DisposableSocket(
    val socket: Socket,
    val serverSocket: ServerSocket,
): Disposable {
    override fun dispose() {
        socket.close()
        serverSocket.close()
    }

    override fun isDisposed(): Boolean {
        return socket.isClosed && serverSocket.isClosed
    }
}
class PortSocket(
    val socket: ServerSocket,
    private val scheduler: Scheduler
) {
    fun accept(): Single<DisposableSocket> {
        return Single.fromCallable {
            DisposableSocket(
                socket = socket.accept(),
                serverSocket = socket
            )
        }.subscribeOn(scheduler)
    }
}

interface ServerSocketManager {

    fun getServerSocket(): Single<PortSocket>

    fun randomizePort()
}