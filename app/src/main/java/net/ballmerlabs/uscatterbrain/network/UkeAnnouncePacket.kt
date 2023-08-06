package net.ballmerlabs.uscatterbrain.network

import net.ballmerlabs.uscatterbrain.ScatterProto
import net.ballmerlabs.uscatterbrain.ScatterProto.ExtraUke
import net.ballmerlabs.uscatterbrain.ScatterProto.JustUkes
import java.nio.ByteBuffer
import java.util.UUID

/**
 * wrapper class for ElectLeader protobuf message
 *
 * @property tieBreak tiebreak value
 * @property provides AdvertisePacket provides value
 * @property isHashed true if this packet only contains a hash value
 * @property hash hash value if this packet is hashed
 */
class UkeAnnouncePacket(packet: JustUkes) : ScatterSerializable<JustUkes>(packet) {

    override val type: PacketType
        get() = PacketType.TYPE_UKE_ANNOUNCE

    val tooSmall
        get() = packet.tooSmall
    val force: Map<UUID, UpgradePacket>
        get() = packet.ukesList.fold(HashMap()) { map, v ->
            map[protoUUIDtoUUID(v.luid)] = UpgradePacket(v.upgrade)
            map
        }

    data class Builder(
        var forceUke: MutableMap<UUID, UpgradePacket> = mutableMapOf(),
    ) {

        fun setforceUke(force: Map<UUID, UpgradePacket>) = apply {
            this.forceUke.putAll(force)
        }

        fun build(): UkeAnnouncePacket {
            return UkeAnnouncePacket(
                JustUkes.newBuilder()
                    .setTooSmall(false)
                    .addAllUkes(forceUke.map { (k, v) ->
                        ExtraUke.newBuilder()
                            .setLuid(protoUUIDfromUUID(k)).setUpgrade(v.packet).build()
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

        class Parser :
            ScatterSerializable.Companion.Parser<JustUkes, UkeAnnouncePacket>(JustUkes.parser())

        fun parser(): Parser {
            return Parser()
        }

    }
}