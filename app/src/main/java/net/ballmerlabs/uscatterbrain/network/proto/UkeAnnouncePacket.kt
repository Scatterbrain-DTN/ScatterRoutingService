package net.ballmerlabs.uscatterbrain.network.proto


import net.ballmerlabs.sbproto.SbPacket
import proto.Scatterbrain.ExtraUke
import proto.Scatterbrain.JustUkes
import java.nio.ByteBuffer
import java.util.UUID
import net.ballmerlabs.scatterproto.*
import proto.Scatterbrain.MessageType

/**
 * wrapper class for ElectLeader protobuf message
 *
 * @property tieBreak tiebreak value
 * @property provides AdvertisePacket provides value
 * @property isHashed true if this packet only contains a hash value
 * @property hash hash value if this packet is hashed
 */
@SbPacket(messageType = MessageType.JUST_UKES)
class UkeAnnouncePacket(
    packet: JustUkes
) : ScatterSerializable<JustUkes>(packet, MessageType.JUST_UKES) {
    val tooSmall
        get() = packet.tooSmall
    val force: Map<UUID, net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket>
        get() = packet.ukesList.fold(HashMap()) { map, v ->
            map[v.luid.toUuid()] =
                net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket(v.upgrade)
            map
        }

    override fun validate(): Boolean {
        return force.size <= MAX_FORCE_UKE_SIZE && force.values.all { v -> v.validate() }
    }

    data class Builder(
        var forceUke: MutableMap<UUID, net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket> = mutableMapOf(),
    ) {

        fun setforceUke(force: Map<UUID, net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket>) = apply {
            this.forceUke.putAll(force)
        }

        fun build(): UkeAnnouncePacket {
            return UkeAnnouncePacket(
                JustUkes.newBuilder()
                    .setTooSmall(false)
                    //.setType(MessageType.JUST_UKES)
                    .addAllUkes(forceUke.map { (k, v) ->
                        ExtraUke.newBuilder()
                            .setLuid(k.toProto()).setUpgrade(v.packet).build()
                    }).build()
            )
        }
    }

    companion object {
        fun uuidToBytes(uuid: UUID): ByteArray {
            val uuidBuffer = ByteBuffer.allocate(16)
            uuidBuffer.putLong(uuid.mostSignificantBits)
            uuidBuffer.putLong(uuid.leastSignificantBits)
            return uuidBuffer.array()
        }

        fun newBuilder(): Builder {
            return Builder()
        }

        fun empty(): UkeAnnouncePacket {
            return UkeAnnouncePacket(JustUkes.newBuilder().setTooSmall(true).build())
        }
    }
}