package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Single
import java.net.Socket
import java.util.UUID

interface ServerSocketManager {

    fun getServerSocket(luid: UUID): Single<Socket>

    companion object {
        const val SCATTERBRAIN_PORT = 7575
    }
}