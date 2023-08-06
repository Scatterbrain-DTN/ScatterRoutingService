package net.ballmerlabs.uscatterbrain.network.wifidirect

import io.reactivex.Single
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockSocketProvider @Inject constructor(
    private val outputStream: OutputStream,
    private val inputStream: InputStream
): SocketProvider {
    override fun getSocket(address: InetAddress, port: Int, luid: UUID): Single<Socket> {
        return Single.just(mock {
            on { getOutputStream() } doReturn outputStream
            on { getInputStream() } doReturn inputStream
        })
    }
}