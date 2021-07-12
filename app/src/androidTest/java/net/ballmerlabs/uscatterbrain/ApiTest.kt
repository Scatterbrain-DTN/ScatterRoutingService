package net.ballmerlabs.uscatterbrain

import android.os.ParcelUuid
import androidx.test.filters.SmallTest
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import com.google.protobuf.ByteString
import kotlinx.coroutines.runBlocking
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity
import net.ballmerlabs.uscatterbrain.db.sanitizeFilename
import net.ballmerlabs.uscatterbrain.db.verifyed25519
import net.ballmerlabs.uscatterbrain.network.IdentityPacket
import org.junit.Test
import org.junit.runner.RunWith
import java.util.*
import java.util.concurrent.TimeoutException
import kotlin.collections.ArrayList
import kotlin.random.Random

@RunWith(AndroidJUnit4ClassRunner::class)
@SmallTest
class ApiTest: TestBase() {
    fun syncSendMessage(message: ScatterMessage) {
        regularBinder.sendMessage(message)
    }

    fun syncSendMesssages(messages: List<ScatterMessage>) {
        regularBinder.sendMessages(messages)
    }


    @Test
    @Throws(TimeoutException::class)
    fun startTest() {
        runBlocking { binder.startService() }
    }

    @Test
    @Throws(TimeoutException::class)
    fun sendMessage() {
        val message = ScatterMessage.newBuilder()
                .setApplication("testing")
                .setBody(byteArrayOf(0))
                .build()
        runBlocking { binder.sendMessage(message) }
    }

    @Test
    @Throws(TimeoutException::class)
    fun sendMessageSync() {
        val message = ScatterMessage.newBuilder()
                .setApplication("testing")
                .setBody(byteArrayOf(0))
                .build()

        runBlocking { syncSendMessage(message) }
    }

    @Test
    @Throws(TimeoutException::class)
    fun sendMessages() {
        val list = ArrayList<ScatterMessage>()
        for (x in 0..1000) {
            val message = ScatterMessage.newBuilder()
                    .setApplication("testing")
                    .setBody(Random(0).nextBytes(128))
                    .build()
            list.add(message)
        }
        runBlocking { binder.sendMessage(list) }
    }

    @Test
    @Throws(TimeoutException::class)
    fun sendMessagesSync() {
        val list = ArrayList<ScatterMessage>()
        for (x in 0..1000) {
            val message = ScatterMessage.newBuilder()
                    .setApplication("testing")
                    .setBody(Random(0).nextBytes(128))
                    .build()
            list.add(message)
        }
        runBlocking { syncSendMesssages(list) }
    }

    @Test
    @Throws(TimeoutException::class)
    fun sendAndSignMessages() {
        val list = ArrayList<ScatterMessage>()
        for (x in 0..1000) {
            val message = ScatterMessage.newBuilder()
                    .setApplication("testing")
                    .setBody(Random(0).nextBytes(128))
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
            val message = ScatterMessage.newBuilder()
                    .setApplication("testing")
                    .setBody(Random(0).nextBytes(128))
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
        assert(sanitizeFilename(filename) != filename)
        assert(!sanitizeFilename(filename).contains(".."))
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
                .setName(identity.givenname)
                .setSig(identity.sig)
                .setScatterbrainPubkey(ByteString.copyFrom(identity.getmScatterbrainPubKey()))
                .build()

        assert(packet!!.verifyed25519(identity.getmScatterbrainPubKey()))
    }

    @Test
    fun generateIdentity() {
        val name = "fmef"
        val id = runBlocking { binder.generateIdentity(name) }
        assert(id.givenname == name)
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