package net.ballmerlabs.uscatterbrain.network.proto

import com.google.protobuf.ByteString
import com.goterl.lazysodium.interfaces.GenericHash
import net.ballmerlabs.sbproto.SbPacket
import proto.Scatterbrain.ElectLeader

import java.nio.ByteBuffer
import java.util.UUID
import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import proto.Scatterbrain.MessageType

/**
 * wrapper class for ElectLeader protobuf message
 *
 * @property tieBreak tiebreak value
 * @property provides AdvertisePacket provides value
 * @property isHashed true if this packet only contains a hash value
 * @property hash hash value if this packet is hashed
 */
@SbPacket(messageType = MessageType.ELECT_LEADER)
class ElectLeaderPacket(
    packet: ElectLeader
) : ScatterSerializable<ElectLeader>(packet, MessageType.ELECT_LEADER) {
    private fun hashFromPacket(): ByteArray {
        return if (isHashed) {
            packet.valHash.toByteArray()
        } else {
            val hashbytes = ByteArray(GenericHash.BYTES)
            var bytes = ByteString.EMPTY
            bytes.concat(packet.valBody.salt)
            bytes = bytes.concat(ByteString.copyFrom(
                ElectLeaderPacket.Companion.uuidToBytes(
                    tieBreak
                )
            ))
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
        get() = packet.sender.toUuid()
    val tieBreak: UUID
        get() = UUID(
            packet.valBody.tiebreakerVal.upper, packet.valBody.tiebreakerVal.lower
        )

    val upgrade: Optional<UpgradePacket>
        get() = if (packet.valBody.maybeUpgradeCase == ElectLeader.Body.MaybeUpgradeCase.UPGRADE) {
            Optional.of(UpgradePacket(packet.valBody.upgrade))
        } else {
            Optional.empty()
        }

    val provides: Provides
        get() = AdvertisePacket.Companion.valToProvides(
            packet.valBody.provides
        )

    val isHashed: Boolean
        get() = packet.valCase == ElectLeader.ValCase.VAL_HASH

    val hash: ByteArray
        get() = packet.valHash.toByteArray()

    override fun validate(): Boolean {
        return hash.size <= MAX_HASH
    }

    data class Builder(
        val sender: UUID?,
        var enableHashing: Boolean = false,
        var hashVal: ByteString? = null,
        var provides: Provides? = null,
        var tiebreaker: UUID? = null,
        var upgrade: UpgradePacket? = null
    ) {
        private val salt: ByteArray = ByteArray(GenericHash.BYTES)


        private fun hashFromBuilder(): ByteString {
            val hashbytes = ByteArray(GenericHash.BYTES)
            var bytes = ByteString.EMPTY
            bytes.concat(ByteString.copyFrom(salt))
            bytes = bytes.concat(ByteString.copyFrom(
                ElectLeaderPacket.Companion.uuidToBytes(
                    tiebreaker!!
                )
            ))
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

        fun setProvides(provides: Provides) = apply {
            this.provides = provides
        }

        fun setTiebreaker(tiebreaker: UUID) = apply {
            this.tiebreaker = tiebreaker
        }

        fun setHash(hash: ByteString) = apply {
            this.hashVal = hash
        }

        fun setUpgrade(upgrade: UpgradePacket) = apply {
            this.upgrade = upgrade
        }

        fun build(): ElectLeaderPacket {
            require((!(provides == null || tiebreaker == null)) || hashVal != null) { "both tiebreaker and provides must be set" }
            return if (enableHashing) {
                ElectLeaderPacket(
                    ElectLeader.newBuilder().setValHash(hashFromBuilder())
                        //.setType(ScatterProto.MessageType.ELECT_LEADER)
                        .build()
                )
            } else {
                val builder = ElectLeader.Body.newBuilder().setSalt(ByteString.copyFrom(salt))
                    .setProvides(
                        providesToVal(
                            provides!!
                        )
                    ).setTiebreakerVal(tiebreaker?.toProto())
                val u = upgrade
                if (u != null) {
                    builder.setUpgrade(u.packet)
                }
                ElectLeaderPacket(
                    ElectLeader.newBuilder().setSender(sender?.toProto()).setValBody(
                        builder.build()
                    )
                        //.setType(ScatterProto.MessageType.ELECT_LEADER)
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

        fun newBuilder(sender: UUID? = null): Builder {
            return Builder(sender)
        }
    }
}