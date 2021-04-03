package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.util.Log
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.BehaviorSubject
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Accepts TCP sockets in a loop, relaying the accepted connections to an
 * observable for later use
 */
class InterceptableServerSocket(port: Int) : ServerSocket(port) {
    private var closed = false

    class SocketConnection(val socket: Socket)

    class InterceptableServerSocketFactory {
        private var serverSocket: InterceptableServerSocket? = null
        fun create(port: Int): Single<InterceptableServerSocket> {
            return Single.fromCallable {
                if (serverSocket == null) {
                    serverSocket = InterceptableServerSocket(port)
                }
                Log.v(WifiDirectRadioModule.TAG, "creating server socket")
                serverSocket
            }
        }
    }

    private val socketSet = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val socketBehaviorSubject = BehaviorSubject.create<SocketConnection>()

    fun acceptLoop(): Observable<SocketConnection> {
        return Observable.fromCallable { SocketConnection(accept()) }
                .doOnError { err: Throwable -> Log.e(WifiDirectRadioModule.TAG, "error on socket accept: $err") }
                .repeatUntil { closed }
    }

    @Throws(IOException::class)
    override fun accept(): Socket {
        return try {
            val socket = super.accept()
            socketBehaviorSubject.onNext(SocketConnection(socket))
            socketSet.add(socket)
            socket
        } catch (e: IOException) {
            throw e
        }
    }

    @Throws(IOException::class)
    override fun close() {
        closed = true
        super.close()
    }

    fun observeConnections(): Observable<SocketConnection> {
        return socketBehaviorSubject
                .doOnSubscribe { Log.v("debug", "subscribed to server sockets") }
    }
}