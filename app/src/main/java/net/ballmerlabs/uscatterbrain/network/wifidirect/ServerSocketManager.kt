package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Scheduler
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.net.ServerSocket
import java.net.Socket

data class DisposableSocket(
    val socket: Socket,
    val serverSocket: ServerSocket,
) {
    val LOG by scatterLog()
    fun dispose() {
        socket.close()
    }

    fun isDisposed(): Boolean {
        return socket.isClosed && serverSocket.isClosed
    }
}
class PortSocket(
    val socket: ServerSocket,
) {
    private val LOG by scatterLog()
    fun accept(scheduler: Scheduler): Flowable<DisposableSocket> {
        return Flowable.create<DisposableSocket?>( { obs ->
            while (!socket.isClosed) {
                try {
                    obs.onNext(
                        DisposableSocket(
                            socket = socket.accept(),
                            serverSocket = socket
                        )
                    )
                } catch (exc: Exception) {
                    LOG.w("serverSocket exception $exc")
                    obs.onError(exc)
                }
            }
            obs.onComplete()
        }, BackpressureStrategy.BUFFER)
            .doFinally {
                LOG.w("serverSocket completed!")
                close()
            }
            .subscribeOn(scheduler)

    }

    fun close() {
        LOG.w("serverSocket close called")
        try {
            socket.close()
        } catch (exc: Exception) {
            LOG.w("PortSocket already closed: $exc")
        }
    }
}

interface ServerSocketManager {

    fun getServerSocket(): Single<PortSocket>

    fun randomizePort()
}