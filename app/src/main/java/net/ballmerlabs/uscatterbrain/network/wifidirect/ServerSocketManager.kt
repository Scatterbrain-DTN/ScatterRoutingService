package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Single
import java.net.Socket

interface ServerSocketManager {

    fun getServerSocket(): Single<Socket>

    companion object {
        const val SCATTERBRAIN_PORT = 7575
    }
}