package net.ballmerlabs.uscatterbrain.network

import com.google.protobuf.ByteString
import com.goterl.lazysodium.interfaces.GenericHash
import net.ballmerlabs.uscatterbrain.ScatterProto
import net.ballmerlabs.uscatterbrain.ScatterProto.ElectLeader
import net.ballmerlabs.uscatterbrain.ScatterProto.Role
import net.ballmerlabs.uscatterbrain.network.wifidirect.FakeWifiP2pConfig
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
class ElectLeaderPacket(packet: ElectLeader) : ScatterSerializable<ElectLeader>(packet) {
    private fun hashFromPacket(): ByteArray {
        return if (isHashed) {
            packet.valHash.toByteArray()
        } else {
            val hashbytes = ByteArray(GenericHash.BYTES)
            var bytes = ByteString.EMPTY
            bytes.concat(packet.valBody.salt)
            bytes = bytes.concat(ByteString.copyFrom(uuidToBytes(tieBreak)))
            val buffer = ByteBuffer.allocate(Integer.SIZE)
            buffer.putInt(packet.valBody.provides)
            bytes.concat(ByteString.copyFrom(buffer.array()))
            LibsodiumInterface.sodium.crypto_generichash(
                hashbytes,
                hashbytes.size,
                bytes.toByteArray(),
                bytes.toByteArray().size.toLong(),
                null,
                0
            )
            hashbytes
        }
    }

    fun verifyHash(remote: ElectLeaderPacket): Boolean {
        return when {
            remote.isHashed == isHashed -> {
                false
            }

            isHashed -> {
                val hash = remote.hashFromPacket()
                hash.contentEquals(hash)
            }

            else -> {
                val hash = hashFromPacket()
                hash.contentEquals(remote.hash)
            }
        }
    }

    val from: UUID
        get() = protoUUIDtoUUID(packet.sender)
    val tieBreak: UUID
        get() = UUID(
            packet.valBody.tiebreakerVal.upper,
            packet.valBody.tiebreakerVal.lower
        )

    val band: Int
        get() = packet.valBody.band

    val provides: AdvertisePacket.Provides
        get() = AdvertisePacket.valToProvides(packet.valBody.provides)

    val role: Role
        get() = packet.valBody.role

    override val type: PacketType
        get() = PacketType.TYPE_ELECT_LEADER

    val isHashed: Boolean
        get() = packet.valCase == ElectLeader.ValCase.VAL_HASH

    val hash: ByteArray
        get() = packet.valHash.toByteArray()

    val force: Map<UUID, UpgradePacket>
        get() = packet.valBody.forceLuidList.fold(HashMap()) { map, v ->
            map[protoUUIDtoUUID(v.luid)] = UpgradePacket(v.upgrade)
            map
        }


    val remove: List<UUID>
        get() = packet.valBody.removeLuidList.map { v -> protoUUIDtoUUID(v) }

    data class Builder(
        val sender: UUID,
        var role: Role = Role.UNRECOGNIZED,
        var enableHashing: Boolean = false,
        var hashVal: ByteString? = null,
        var provides: AdvertisePacket.Provides? = null,
        var tiebreaker: UUID? = null,
        var forceUke: MutableMap<UUID, UpgradePacket> = mutableMapOf(),
        var remove: MutableList<UUID> = mutableListOf(),
        var band: Int = FakeWifiP2pConfig.GROUP_OWNER_BAND_AUTO
    ) {
        private val salt: ByteArray = ByteArray(GenericHash.BYTES)


        private fun hashFromBuilder(): ByteString {
            val hashbytes = ByteArray(GenericHash.BYTES)
            var bytes = ByteString.EMPTY
            bytes.concat(ByteString.copyFrom(salt))
            bytes = bytes.concat(ByteString.copyFrom(uuidToBytes(tiebreaker!!)))
            val buffer = ByteBuffer.allocate(Integer.SIZE)
            buffer.putInt(provides!!.`val`)
            bytes.concat(ByteString.copyFrom(buffer.array()))
            LibsodiumInterface.sodium.crypto_generichash(
                hashbytes,
                hashbytes.size,
                bytes.toByteArray(),
                bytes.toByteArray().size.toLong(),
                null,
                0
            )
            return ByteString.copyFrom(hashbytes)
        }

        fun enableHashing() = apply {
            enableHashing = true
        }

        fun setProvides(provides: AdvertisePacket.Provides) = apply {
            this.provides = provides
        }

        fun setRole(role: Role) = apply {
            this.role = role
        }

        fun setTiebreaker(tiebreaker: UUID) = apply {
            this.tiebreaker = tiebreaker
        }

        fun setHash(hash: ByteString) = apply {
            this.hashVal = hash
        }

        fun setRemove(remove: List<UUID>) = apply {
            this.remove.addAll(remove)
        }

        fun setforceUke(force: Map<UUID, UpgradePacket>) = apply {
            this.forceUke.putAll(force)
        }

        fun setBand(band: Int) = apply {
            this.band = band
        }

        fun build(): ElectLeaderPacket {
            require((!(provides == null || tiebreaker == null)) || hashVal != null) { "both tiebreaker and provides must be set" }
            return if (enableHashing) {
                ElectLeaderPacket(ElectLeader.newBuilder().setValHash(hashFromBuilder()).build())
            } else {
                val builder = ElectLeader.Body.newBuilder()
                .setSalt(ByteString.copyFrom(salt))
                    .setProvides(providesToVal(provides!!))
                    .setTiebreakerVal(protoUUIDfromUUID(tiebreaker!!))
                    .setRole(this.role)
                    .setBand(this.band)
                    .addAllRemoveLuid(this.remove.map { v -> protoUUIDfromUUID(v) })
                    .addAllForceLuid(forceUke.map { v -> ScatterProto.ExtraUke.newBuilder()
                        .setLuid(protoUUIDfromUUID(v.key))
                        .setUpgrade(v.value.packet).build()
                    })
                ElectLeaderPacket(
                    ElectLeader.newBuilder()
                        .setSender(protoUUIDfromUUID(sender))
                        .setValBody(
                            builder
                                .build()
                        )
                        .build()
                )
            }
        }
    }

    companion object {
        fun uuidToBytes(uuid: UUID): ByteArray {
            val uuidBuffer = ByteBuffer.allocate(16)
            uuidBuffer.putLong(uuid.mostSignificantBits)
            uuidBuffer.putLong(uuid.leastSignificantBits)
            return uuidBuffer.array()
        }

        fun newBuilder(sender: UUID): Builder {
            return Builder(sender)
        }

        class Parser :
            ScatterSerializable.Companion.Parser<ElectLeader, ElectLeaderPacket>(ElectLeader.parser())

        fun parser(): Parser {
            return Parser()
        }

    }
}