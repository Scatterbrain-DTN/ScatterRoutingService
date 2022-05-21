package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Single
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockServerSocketManager @Inject constructor(
        private val stream: InputStream,
        private val output: OutputStream
): ServerSocketManager {
    override fun getServerSocket(): Single<Socket> {
        return Single.just(mock {
            on { getInputStream() } doReturn stream
            on { getOutputStream() } doReturn output
        })
    }
}