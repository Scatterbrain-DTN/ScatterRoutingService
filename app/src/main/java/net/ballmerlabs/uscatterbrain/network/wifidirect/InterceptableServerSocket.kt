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
 * @param port port to listen on
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

    /**
     * Accepts socket connections in a loop and returns an observable yielding
     * each accepted connection
     * @return observable returning each connection as a SocketConnection
     */
    fun acceptLoop(): Observable<SocketConnection> {
        return Observable.fromCallable { SocketConnection(accept()) }
                .doOnError { err: Throwable -> Log.e(WifiDirectRadioModule.TAG, "error on socket accept: $err") }
                .repeatUntil { closed }
    }

    /**
     * Accepts a single socket connection using a blocking call
     * @return socket
     */
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

    /**
     * Stops listening on the tcp port and calls onComplete on any observable returned
     * by acceptLoop()
     */
    @Throws(IOException::class)
    override fun close() {
        closed = true
        super.close()
    }

    /**
     * Returns an observable that yields any subsequent connections returned by
     * acceptLoop()
     *
     * @return observable returning SocketConnection
     */
    fun observeConnections(): Observable<SocketConnection> {
        return socketBehaviorSubject
                .doOnSubscribe { Log.v("debug", "subscribed to server sockets") }
    }
}