package net.ballmerlabs.uscatterbrain.network.bluetoothLE


import net.ballmerlabs.scatterproto.Provides
import net.ballmerlabs.uscatterbrain.network.proto.AdvertisePacket
import scatterbrain.Merkle.DeclareHashesMode
import java.util.concurrent.atomic.AtomicReference

/**
 * state for bluetoothLE advertise stage
 * used in state machine implementation
 */
class AdvertiseStage : LeDeviceSession.Stage {
    private val packet = AtomicReference<AdvertisePacket>()
    var declareHashesMode = DeclareHashesMode.MERKLEPROOF
    fun addPacket(packet: AdvertisePacket) {
        declareHashesMode = packet.mode
        this.packet.set(packet)
    }

    override fun reset() {
        packet.set(null)
    }
    
    companion object {
        private val provides: ArrayList<Provides> = object : ArrayList<Provides>() {
            init {
                add(Provides.BLE)
                add(Provides.WIFIP2P)
            }
        }
        fun self(busy: Boolean = false): AdvertisePacket  {
            return AdvertisePacket.newBuilder()
                .setBusy(busy)
                .setProvides(provides)
                .build()!!
        }

    }
}