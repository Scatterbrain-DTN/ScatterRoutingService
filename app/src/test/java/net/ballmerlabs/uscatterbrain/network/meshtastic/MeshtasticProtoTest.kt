package net.ballmerlabs.uscatterbrain.network.meshtastic

import android.os.Build
import io.reactivex.Completable
import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.toProto
import net.ballmerlabs.uscatterbrain.util.logger
import net.ballmerlabs.uscatterbrain.mock.util.mockLoggerGenerator
import net.ballmerlabs.uscatterbrain.network.meshtastic.proto.MeshtasticAnnounceAckPacket
import net.ballmerlabs.uscatterbrain.network.meshtastic.utils.MeshtasticPacketStream
import net.ballmerlabs.uscatterbrain.network.meshtastic.utils.SeqLike
import net.ballmerlabs.uscatterbrain.network.meshtastic.utils.fromMeshtastic
import net.ballmerlabs.uscatterbrain.network.meshtastic.utils.toMeshtastic
import net.ballmerlabs.uscatterbrain.network.proto.AckPacket
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import scatterbrain.Scatterbrain.Ack
import scatterbrain.Meshtastic.MeshtasticAnnounceAck
import scatterbrain.Scatterbrain.MessageType
import java.util.UUID

@SbPacket(messageType = MessageType.ACK)
class DummySeq(override val seq: Int, override val end: Boolean = false) : SeqLike<Ack>(AckPacket.newBuilder(true).build().packet, MessageType.ACK) {
    override fun validate(): Boolean {
        return true
    }
}


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class MeshtasticProtoTest {

    init {
        logger = mockLoggerGenerator
    }

    @Before
    fun init() {
        MockitoAnnotations.openMocks(this)
    }

    @After
    fun cleanup() {
        Mockito.validateMockitoUsage()
    }

    @Test
    fun noCrcSerialize() {
        val uuid = UUID.randomUUID()
        val packet = MeshtasticAnnounceAckPacket(
            MeshtasticAnnounceAck.newBuilder()
                .setLuid(uuid.toProto())
                .build()
        )

        val bytes = packet.toMeshtastic()
        val out = bytes.fromMeshtastic()
        val t: MeshtasticAnnounceAckPacket = out.get()
        assert(t.remoteLuid == packet.remoteLuid)
    }


    @Test
    fun seqStream() {
        val stream = MeshtasticPacketStream(DummySeqParser.parser)

        val out = stream.mergeWith(Completable.fromAction {
                for (x in 0..<10) {
                    stream.onPacket(DummySeq(x))
                }
                stream.close()
            }).toList().blockingGet()

        println("out ${out.size}")
        assert(out.size == 10)
    }

    @Test
    fun streamAsync() {
        val stream = MeshtasticPacketStream(DummySeqParser.parser)
        for (x in 0..<10) {
            stream.onPacket(DummySeq(x))
        }
        stream.onPacket(DummySeq(seq = 10, end = true))
        val out = stream.toList().blockingGet()

        println("out ${out.size}")
        assert(out.size == 11)
    }

    private fun testOutOfOrder(test: Array<Int>) {
        val stream = MeshtasticPacketStream(DummySeqParser.parser)

        val out = stream.mergeWith(Completable.fromAction {
            for (x in test) {
                stream.onPacket(DummySeq(x))
            }
            stream.close()
        }).toList().blockingGet()

        println("out ${out.size}")
        val array = out.map { v-> v.seq }
        println(array)
        assert(out.size == 10)
        assert(array.toTypedArray().contentEquals(test.sortedArray()))
    }

    @Test
    fun seqStreamOutOfOrder() {
        testOutOfOrder(arrayOf(0, 1, 2, 4, 5, 3, 6 ,7, 8, 9))
        testOutOfOrder(arrayOf(0, 1, 6, 2, 4, 5, 3, 7, 8, 9))
    }
}