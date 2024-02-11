package net.ballmerlabs.uscatterbrain

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import com.google.protobuf.ByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity
import net.ballmerlabs.uscatterbrain.db.sanitizeFilename
import net.ballmerlabs.uscatterbrain.network.IdentityPacket
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.TimeoutException
import kotlin.random.Random

@RunWith(AndroidJUnit4ClassRunner::class)
@SmallTest
class ApiTest: TestBase() {

    private lateinit var context: Context

    private fun syncSendMessage(message: ScatterMessage) {
        regularBinder.sendMessage(message)
    }

    private fun syncSendMesssages(messages: List<ScatterMessage>) {
        regularBinder.sendMessages(messages)
    }

    @ExperimentalCoroutinesApi
    @Before
    override fun init() {
        super.init()
        context = ApplicationProvider.getApplicationContext()
    }


    @Test
    @Throws(TimeoutException::class)
    fun startTest() {
        runBlocking { binder.startService() }
    }

    @Test
    @Throws(TimeoutException::class)
    fun sendMessage() {
        val message = ScatterMessage.Builder.newInstance(context, byteArrayOf(8))
                .setApplication("testing")
                .build()
        runBlocking { binder.sendMessage(message) }
    }

    @Test
    @Throws(TimeoutException::class)
    fun sendMessageSync() {
        val message = ScatterMessage.Builder.newInstance(context, byteArrayOf(2))
                .setApplication("testing")
                .build()

        runBlocking { syncSendMessage(message) }
    }

    @Test
    @Throws(TimeoutException::class)
    fun sendMessages() {
        val list = ArrayList<ScatterMessage>()
        for (x in 0..100) {
            val message = ScatterMessage.Builder.newInstance(context, Random(0).nextBytes(128))
                    .setApplication("testing")
                    .build()
            list.add(message)
        }
        runBlocking { binder.sendMessage(list) }
    }

    @Test
    @Throws(TimeoutException::class)
    fun sendMessagesSync() {
        val list = ArrayList<ScatterMessage>()
        for (x in 0..100) {
            val message = ScatterMessage.Builder.newInstance(context, Random(1).nextBytes(1280))
                    .setApplication("testing")
                    .build()
            list.add(message)
        }
        runBlocking { syncSendMesssages(list) }
    }


    @Test
    @Throws(TimeoutException::class)
    fun transactionBufferOverflow() {
        val size = 20
        val application = LibsodiumInterface.base64enc(Random.nextBytes(1024))
        val list = ArrayList<ScatterMessage>()
        for (x in 0..size) {
            val message = ScatterMessage.Builder.newInstance(context, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(x).array())
                .setApplication(application)
                .build()
            list.add(message)
        }

       Log.e("debug", "pre messagesize: ${list.size}")

        val messagesize = runBlocking {
            syncSendMesssages(list)
            val messages = binder.getScatterMessages(application).toList()
            messages.size
        }

        Log.e("debug", "messagesize: $messagesize")
        assert(messagesize == list.size)
    }

    @Test
    @Throws(TimeoutException::class)
    fun getPermissions() {
        runBlocking { binder.getPermissionStatus() }
    }

    @Test
    @Throws(TimeoutException::class)
    fun sendAndSignMessages() {
        val list = ArrayList<ScatterMessage>()
        for (x in 0..100) {
            val message = ScatterMessage.Builder.newInstance(context, Random(0).nextBytes(128))
                    .setApplication("testing")
                    .build()
            list.add(message)
        }
        runBlocking {
            val id = binder.generateIdentity("test")
            binder.sendMessage(list, id.fingerprint)
        }
    }

    @Test(expected = IllegalStateException::class)
    @Throws(TimeoutException::class)
    fun signMessageHandleErr() {
        val list = ArrayList<ScatterMessage>()
        for (x in 0..2) {
            val message = ScatterMessage.Builder.newInstance(context, Random(0).nextBytes(128))
                    .setApplication("testing")
                    .build()
            list.add(message)
        }
        runBlocking {
            binder.sendMessage(list, UUID.randomUUID())
        }
    }

    @Test
    @Throws(TimeoutException::class)
    fun preventSimpleDirectoryTraversalAttack() {
        val filename = "../fmef"
        try {
            assert(sanitizeFilename(filename) != filename)
            assert(false)
        } catch (exc: Exception) {
            assert(true)
        }

        try {
            assert(!sanitizeFilename(filename).contains(".."))
            assert(false)
        } catch (exc: Exception) {
            assert(true)
        }
    }

    @Test
    @Throws(TimeoutException::class)
    fun allowsNormalFilename() {
        val i = "fmef"
        val x = "fmef_text"
        assert(sanitizeFilename(i) == i)
        assert(sanitizeFilename(x) == x)
    }

    @Test
    fun identityVerification() {
        val identity: ApiIdentity = ApiIdentity.newBuilder()
                .setName("test")
                .sign(ApiIdentity.newPrivateKey())
                .build()

        val packet = IdentityPacket.newBuilder()
                .setName(identity.identity.name)
                .setSig(identity.identity.sig)
                .setScatterbrainPubkey(ByteString.copyFrom(identity.identity.publicKey))
                .build()

        assert(packet!!.verifyed25519(identity.identity.publicKey))
    }

    @Test
    @Throws(TimeoutException::class)
    fun signVerify() {
        runBlocking {
            val identity = binder.generateIdentity("fmef")
            val data = Random(0).nextBytes(128)
            val sig = binder.sign(identity, data)
            assert(binder.verify(identity, data, sig))
        }
    }

    @Test
    fun generateIdentity() {
        val name = "fmef"
        val id = runBlocking { binder.generateIdentity(name) }
        assert(id.name == name)
    }

    @Test
    fun getSingleIdentity() {
        val name = "femf"
        val id = runBlocking { binder.generateIdentity(name) }
        val newid = runBlocking { binder.getIdentity(id.fingerprint) }
        assert(newid != null)
        assert(newid!!.fingerprint == id.fingerprint)
    }

    @Test
    fun testAuthorizePackages() {
        runBlocking {
            val pkgname = "android"
            val id = binder.generateIdentity(pkgname)
            binder.authorizeIdentity(id, pkgname)
            val perms = binder.getPermissions(id)
            val pkg = perms.map { namePackage -> namePackage.info.packageName }
            assert(pkg.contains(pkgname))
        }
    }


}