package net.ballmerlabs.uscatterbrain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import com.google.firebase.FirebaseApp
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.goterl.lazysodium.interfaces.Hash
import com.goterl.lazysodium.interfaces.Sign
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity
import net.ballmerlabs.uscatterbrain.db.getGlobalHash
import net.ballmerlabs.uscatterbrain.network.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*

@RunWith(AndroidJUnit4ClassRunner::class)
class ProtocolTests {
    private val scheduler = RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory("test"))
    private val writeScheduler = RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory("test2"))

    @Before
    fun init() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        FirebaseApp.initializeApp(ctx)
    }

    private inline fun <reified T: ScatterSerializable<V>, reified V: MessageLite> testSerialize(
            parser: ScatterSerializable.Companion.Parser<V,T>,
            input: T,
            blocksize: Int = 20,
            onComplete: (packet: T) -> Unit
    ) {
        onComplete(input)
        val buf = InputStreamFlowableSubscriber(blocksize*1024)
        for (x in 1..blocksize) {
            val obs = input.writeToStream(x, writeScheduler)
            obs.subscribe(buf)
        }

        for (x in 1..blocksize) {
            val packet = ScatterSerializable.parseWrapperFromCRC(
                parser,
                buf,
                scheduler
            ).blockingGet()
            onComplete(packet)
        }
        val stream = ByteArrayOutputStream()
        input.writeToStream(stream, scheduler).blockingAwait()
        val streamPacket = ScatterSerializable.parseWrapperFromCRC(
                parser,
                ByteArrayInputStream(stream.toByteArray()),
                scheduler
        ).blockingGet()
        onComplete(streamPacket)
    }

    @Test
    fun ackPacketWorks() {
        val ack = AckPacket.newBuilder(true)
            .build()

        testSerialize(AckPacket.parser(), ack) { parsed ->
            assert(parsed.success)
        }
        val status = -100
        val message = "fmef"
        val ack2 = AckPacket.newBuilder(false)
            .setStatus(status)
            .setMessage(message)
            .build()

        testSerialize(AckPacket.parser(), ack2) { parsed ->
            assert(!parsed.success)
            assert(parsed.message == message)
            assert(parsed.status == status)
        }
    }

    @Test
    fun advertisePacketWorks() {
        val provides = listOf(AdvertisePacket.Provides.WIFIP2P)
        val packet = AdvertisePacket.newBuilder()
                .setProvides(provides)
                .build()
        assert(packet != null)
        testSerialize(AdvertisePacket.parser(), packet!!) { parsed ->
            assert(parsed.provides == provides)
        }
    }


    @Test
    fun blockHeaderPacketWorks() {

        for (toDisk in arrayOf(true, false)) {
            for (endOfStream in arrayOf(true, false)) {
                val toFingerprint = UUID.randomUUID()
                val fromFingerprint = UUID.randomUUID()
                val application = "fmef"
                val sig = ByteArray(Sign.ED25519_BYTES)
                val sessionId = 4
                val mime = "application/octet-stream"
                val extension = "exe"
                val hashes = listOf(ByteString.copyFrom(ByteArray(Hash.BYTES)))

                val oldHeader = BlockHeaderPacket.newBuilder()
                        .setToFingerprint(toFingerprint)
                        .setFromFingerprint(fromFingerprint)
                        .setApplication(application)
                        .setSig(sig)
                        .setToDisk(toDisk)
                        .setSessionID(sessionId)
                        .setMime(mime)
                        .setExtension(extension)
                        .setHashes(hashes)
                        .setEndOfStream(endOfStream)
                        .build()
                testSerialize(BlockHeaderPacket.parser(), oldHeader) { header ->
                    if (endOfStream) {
                        assert(header.isEndOfStream)
                    } else {
                        assert(header.toFingerprint[0] == toFingerprint)
                        assert(header.fromFingerprint[0] == fromFingerprint)
                        assert(header.application == application)
                        assert(header.autogenFilename.isNotEmpty())
                        assert(header.signature.contentEquals(sig))
                        assert(header.isFile == toDisk)
                        assert(header.mime == mime)
                        assert(header.extension == extension)
                        assert(header.isValidFilename)
                        assert(header.sessionID == sessionId)
                        header.hashes.contentEquals(oldHeader.hashes)
                    }

                }
            }
        }
    }

    @Test
    fun blockSequencePacketWorks() {
        val data = ByteArray(256)
        Random().nextBytes(data)
        for (end in arrayOf(false, true)) {
            for (x in 0..3) {
                val blockSeq = BlockSequencePacket.newBuilder()
                        .setData(ByteString.copyFrom(data))
                        .setSequenceNumber(x)
                        .setEnd(end)
                        .build()
                testSerialize(BlockSequencePacket.parser(), blockSeq) { packet ->
                    assert(packet.sequenceNum == x)
                    assert(packet.data.contentEquals(data))
                    assert(packet.calculateHash().isNotEmpty())
                    assert(packet.isEnd == end)
                }
            }
        }
    }

    @Test
    fun declareHashesPacketWorks() {
        val hashes = listOf(ByteArray(Hash.BYTES))
        Random().nextBytes(hashes[0])

        val declareHashes = DeclareHashesPacket.newBuilder()
                .setHashesByte(hashes)
                .build()
        testSerialize(DeclareHashesPacket.parser(), declareHashes) { packet ->
            assert(packet.hashes.size == hashes.size)
            assert(packet.hashes[0].contentEquals(hashes[0]))
        }

        val optOut = DeclareHashesPacket.newBuilder()
                .optOut()
                .build()

        testSerialize(DeclareHashesPacket.parser(), optOut) { packet ->
            assert(packet.optout)
        }
    }

    @Test
    fun electLeaderPacketWorks() {
        val hash = ByteArray(Hash.BYTES)
        val provides = AdvertisePacket.Provides.WIFIP2P
        val tiebreaker = UUID.randomUUID()
        Random().nextBytes(hash)


        val electLeaderPacket = ElectLeaderPacket.newBuilder(UUID.randomUUID())
                .setProvides(provides)
                .setTiebreaker(tiebreaker)
                .build()

        val hashedPacket = ElectLeaderPacket.newBuilder(UUID.randomUUID())
                .setProvides(provides)
                .setTiebreaker(tiebreaker)
                .enableHashing()
                .build()

        testSerialize(ElectLeaderPacket.parser(), electLeaderPacket) { packet ->
            assert(!packet.isHashed)
            assert(packet.provides == provides)
            assert(packet.tieBreak == tiebreaker)
            testSerialize(ElectLeaderPacket.parser(), hashedPacket) { hpacket ->
                assert(hpacket.isHashed)
                assert(hpacket.verifyHash(packet))
            }
        }
    }

    @Test
    fun identityPacketWorks() {
        val name = "mr. fmef"
        val keypair = ApiIdentity.newPrivateKey()
        val apiIdentity = ApiIdentity.newBuilder()
                .sign(keypair)
                .setName(name)
                .build()
        val identityPacket = IdentityPacket.newBuilder()
                .setScatterbrainPubkey(ByteString.copyFrom(keypair.publickey))
                .setName(name)
                .setSig(apiIdentity.identity.sig)
                .build()
        assert(identityPacket != null)
        testSerialize(IdentityPacket.parser(), identityPacket!!) { packet ->
            assert(!packet.isEnd)
            assert(packet.name == name)
            assert(packet.uuid != null)
            assert(packet.fingerprint != null)
            assert(packet.fingerprint!!.isNotEmpty())
            assert(packet.verifyed25519(keypair.publickey))
        }

        val endPacket = IdentityPacket.newBuilder().setEnd().build()
        assert(endPacket != null)
        testSerialize(IdentityPacket.parser(), endPacket!!) { packet ->
            assert(packet.isEnd)
        }
    }

    @Test
    fun luidPacketWorks() {
        val luid = UUID.randomUUID()
        val protoVersion = 5
        val luidPacket = LuidPacket.newBuilder()
                .setLuid(luid)
                .build()

        testSerialize(LuidPacket.parser(), luidPacket) { packet ->
            assert(packet.luidVal == luid)
        }

        val hashedPacket = LuidPacket.newBuilder()
                .setLuid(luid)
                .enableHashing(protoVersion)
                .build()

        testSerialize(LuidPacket.parser(), hashedPacket) { packet ->
            assert(packet.verifyHash(luidPacket))
            assert(packet.protoVersion == protoVersion)
        }
    }

    @Test
    fun routingMetadataPacketWorks() {
        //note we only need to support "empty" packets for now
        val emptyPacket = RoutingMetadataPacket.newBuilder().setEmpty().build()

        testSerialize(RoutingMetadataPacket.parser(), emptyPacket) { packet ->
            assert(packet.isEmpty)
        }
    }

    @Test
    fun upgradePacketWorks() {
        val metadata = mapOf("fmef" to "fmefval")
        val provides = AdvertisePacket.Provides.WIFIP2P
        val sessionid = 5
        val upgradePacket = UpgradePacket.newBuilder()
                .setMetadata(metadata)
                .setProvides(provides)
                .setSessionID(sessionid)
                .build()
        assert(upgradePacket != null)
        testSerialize(UpgradePacket.parser(), upgradePacket!!) { packet ->
            packet.metadata.forEach { (t, u) ->
                assert(metadata[t] == u)
            }
            assert(packet.provides == provides)
            assert(packet.sessionID == sessionid)
        }
    }
}