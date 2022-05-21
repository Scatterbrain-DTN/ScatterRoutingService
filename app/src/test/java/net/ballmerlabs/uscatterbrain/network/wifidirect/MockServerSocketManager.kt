package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Single
import org.mockito.kotlin.mock
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockServerSocketManager @Inject constructor(): ServerSocketManager {
    override fun getServerSocket(): Single<Socket> {
        return Single.just(mock {  })
    }
}