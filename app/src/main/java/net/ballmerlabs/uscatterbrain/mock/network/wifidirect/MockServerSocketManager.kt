package net.ballmerlabs.uscatterbrain.mock.network.wifidirect

import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.network.wifidirect.PortSocket
import net.ballmerlabs.uscatterbrain.network.wifidirect.ServerSocketManager
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockServerSocketManager @Inject constructor(
        private val stream: InputStream,
        private val output: OutputStream
): ServerSocketManager {
    override fun getServerSocket(): Single<PortSocket> {
        return Single.error(NotImplementedError())
    }

    override fun randomizePort() {

    }
}