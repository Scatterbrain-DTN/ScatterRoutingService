package net.ballmerlabs.uscatterbrain.network.meshtastic;

import android.os.Build
import net.ballmerlabs.scatterproto.toProto
import net.ballmerlabs.uscatterbrain.util.logger
import net.ballmerlabs.uscatterbrain.mock.util.mockLoggerGenerator
import net.ballmerlabs.uscatterbrain.network.meshtastic.proto.MeshtasticAnnounceAckPacket
import net.ballmerlabs.uscatterbrain.network.meshtastic.utils.fromMeshtastic
import net.ballmerlabs.uscatterbrain.network.meshtastic.utils.toMeshtastic
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import proto.Scatterbrain.MeshtasticAnnounceAck
import java.util.UUID

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
}