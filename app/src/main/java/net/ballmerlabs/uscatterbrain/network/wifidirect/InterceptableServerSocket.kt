package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.util.Log
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.BehaviorSubject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class InterceptableServerSocket : ServerSocket {
    private var closed = false

    class SocketConnection(val socket: Socket)

    class InterceptableServerSocketFactory {
        private var serverSocket: InterceptableServerSocket? = null
        fun create(port: Int): Single<InterceptableServerSocket?> {
            return Single.fromCallable {
                if (serverSocket == null) {
                    serverSocket = InterceptableServerSocket(port)
                }
                Log.v(WifiDirectRadioModule.Companion.TAG, "creating server socket")
                serverSocket
            }
        }
    }

    private val socketSet = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val socketBehaviorSubject = BehaviorSubject.create<SocketConnection>()

    constructor() {}
    constructor(port: Int) : super(port) {}
    constructor(port: Int, backlog: Int) : super(port, backlog) {}
    constructor(port: Int, backlog: Int, bindAddr: InetAddress?) : super(port, backlog, bindAddr) {}

    fun acceptLoop(): Observable<SocketConnection> {
        return Observable.fromCallable { SocketConnection(accept()) }
                .doOnError { err: Throwable -> Log.e(WifiDirectRadioModule.Companion.TAG, "error on socket accept: $err") }
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

    val sockets: Set<Socket>
        get() {
            socketSet.removeIf { socket: Socket? -> isClosed }
            return socketSet
        }

    fun observeConnections(): Observable<SocketConnection> {
        return socketBehaviorSubject
                .doOnSubscribe { disp: Disposable? -> Log.v("debug", "subscribed to server sockets") }
    }
}