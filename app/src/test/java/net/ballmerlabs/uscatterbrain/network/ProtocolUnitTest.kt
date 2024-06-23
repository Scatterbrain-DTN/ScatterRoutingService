package net.ballmerlabs.uscatterbrain.network

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.goterl.lazysodium.interfaces.GenericHash
import com.goterl.lazysodium.interfaces.Hash
import com.goterl.lazysodium.interfaces.Sign
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.network.proto.*

import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.uscatterbrain.ScatterbrainThreadFactory
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity
import net.ballmerlabs.uscatterbrain.network.desktop.PublicKeyPair
import net.ballmerlabs.uscatterbrain.network.proto.RoutingMetadataPacket
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket
import net.ballmerlabs.uscatterbrain.util.hashAsUUID
import net.ballmerlabs.uscatterbrain.util.logger
import net.ballmerlabs.uscatterbrain.util.mockLoggerGenerator
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import proto.Scatterbrain
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ProtocolUnitTest {
    private val scheduler = RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory("test"))
    private val writeScheduler = RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory("test2"))

    init {
        System.setProperty("jna.library.path", "/opt/homebrew/lib")
        logger = mockLoggerGenerator
    }


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
        val buf = InputStreamFlowableSubscriber(blocksize*8888)

            for (x in 1..blocksize) {
                val obs = input.writeToStream(x, writeScheduler).toSingle().flatMapPublisher { v -> v  }
                obs.subscribe(buf)
            }
        for (x in 1..blocksize) {
            val packet = ScatterSerializable.parseWrapperFromCRC(
                parser,
                buf,
                scheduler
            ).timeout(4, TimeUnit.SECONDS).blockingGet()
            onComplete(packet)
        }
        val stream = ByteArrayOutputStream()
        input.writeToStream(stream,writeScheduler).timeout(6, TimeUnit.SECONDS).toSingle()
            .flatMapCompletable { v -> v }.blockingAwait()
        val streamPacket = ScatterSerializable.parseWrapperFromCRC(
            parser,
            ByteArrayInputStream(stream.toByteArray()),
            scheduler
        )
            .timeout(5, TimeUnit.SECONDS)
            .blockingGet()
        onComplete(streamPacket)
    }

    @Test
    fun keyFingerprint() {
        val key = PublicKeyPair.create()
        val fingerprint = key.fingerprint()
        assert(fingerprint.size == GenericHash.BLAKE2B_BYTES_MIN)
    }

    @Test
    fun testCryptoMessage() {
        val ack = AckPacket.newBuilder(true)
            .setMessage("hi")
            .build()
        val key = LibsodiumInterface.secretboxKey()
        val crypto = CryptoMessage.fromMessage(key, ack)
        val np = crypto.decrypt(AckPacketParser.parser, key)
        assert(np.success)
    }

    @Test
    fun hashAsUuidTest() {
        val bytes = byteArrayOf( 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7, 0x8, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7, 0x8,
            0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7, 0x8, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7, 0x8)
        val uuid = hashAsUUID(bytes)
        println("got hashAsUuid $uuid")
    }

    /*
    @Test
    fun serializeToTmp() {
        val ack = AckPacket.newBuilder(true)
            .setMessage("hi")
            .build()
        File("sb-stream").apply {
            ack.writeToStream(outputStream(), scheduler).flatMapCompletable { v -> v }.blockingAwait()
        }
    }
     */

    @Test
    fun ackPacketWorks() {
        val ack = AckPacket.newBuilder(true)
            .build()

        testSerialize(AckPacketParser.parser, ack) { parsed ->
            assert(parsed.success)
        }
        val status = -100
        val message = "fmef"
        val ack2 = AckPacket.newBuilder(false)
            .setStatus(status)
            .setMessage(message)
            .build()

        testSerialize(AckPacketParser.parser, ack2) { parsed ->
            assert(!parsed.success)
            assert(parsed.message == message)
            assert(parsed.status == status)
        }
    }

    @Test
    fun advertisePacketWorks() {
        val provides = listOf(Provides.WIFIP2P)
        val packet = AdvertisePacket.newBuilder()
            .setProvides(provides)
            .build()
        assert(packet != null)
        testSerialize(AdvertisePacketParser.parser, packet!!) { parsed ->
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
                testSerialize(BlockHeaderPacketParser.parser, oldHeader) { header ->
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
                testSerialize(BlockSequencePacketParser.parser, blockSeq) { packet ->
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
        testSerialize(DeclareHashesPacketParser.parser, declareHashes) { packet ->
            assert(packet.hashes.size == hashes.size)
            assert(packet.hashes[0].contentEquals(hashes[0]))
        }

        val optOut = DeclareHashesPacket.newBuilder()
            .optOut()
            .build()

        testSerialize(DeclareHashesPacketParser.parser, optOut) { packet ->
            assert(packet.optout)
        }
    }

    @Test
    fun electLeaderPacketWorks() {
        val hash = ByteArray(Hash.BYTES)
        val provides = Provides.WIFIP2P
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

        testSerialize(ElectLeaderPacketParser.parser, electLeaderPacket) { packet ->
            assert(!packet.isHashed)
            assert(packet.provides == provides)
            assert(packet.tieBreak == tiebreaker)
            testSerialize(ElectLeaderPacketParser.parser, hashedPacket) { hpacket ->
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
        testSerialize(IdentityPacketParser.parser, identityPacket!!) { packet ->
            assert(!packet.isEnd)
            assert(packet.name == name)
            assert(packet.uuid != null)
            assert(packet.fingerprint != null)
            assert(packet.fingerprint!!.isNotEmpty())
            assert(packet.verifyed25519(keypair.publickey))
        }

        val endPacket = IdentityPacket.newBuilder().setEnd().build()
        assert(endPacket != null)
        testSerialize(IdentityPacketParser.parser, endPacket!!) { packet ->
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

        testSerialize(LuidPacketParser.parser, luidPacket) { packet ->
            assert(packet.luidVal == luid)
        }

        val hashedPacket = LuidPacket.newBuilder()
            .setLuid(luid)
            .enableHashing(protoVersion)
            .build()

        testSerialize(LuidPacketParser.parser, hashedPacket) { packet ->
            assert(packet.verifyHash(luidPacket))
            assert(packet.protoVersion == protoVersion)
        }
    }

    @Test
    fun routingMetadataPacketWorks() {
        //note we only need to support "empty" packets for now
        val emptyPacket = RoutingMetadataPacket.newBuilder().setEmpty().build()

        testSerialize(RoutingMetadataPacketParser.parser, emptyPacket) { packet ->
            assert(packet.isEmpty)
        }
    }

    @Test
    fun upgradePacketWorks() {
        val metadata = mapOf("fmef" to "fmefval")
        val provides = Provides.WIFIP2P
        val sessionid = 5
        val upgradePacket = UpgradePacket.newBuilder(Scatterbrain.Role.UKE)
            .setMetadata(metadata)
            .setProvides(provides)
            .setSessionID(sessionid)
            .setFrom(UUID.randomUUID())
            .build()
        assert(upgradePacket != null)
        testSerialize(UpgradePacketParser.parser, upgradePacket!!) { packet ->
            packet.metadata.forEach { (t, u) ->
                assert(metadata[t] == u)
            }
            assert(packet.provides == provides)
            assert(packet.sessionID == sessionid)
        }
    }
}