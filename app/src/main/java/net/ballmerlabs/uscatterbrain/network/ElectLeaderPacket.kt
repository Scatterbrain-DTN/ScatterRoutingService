package net.ballmerlabs.uscatterbrain.network

import com.google.protobuf.ByteString
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import net.ballmerlabs.uscatterbrain.ScatterProto
import net.ballmerlabs.uscatterbrain.ScatterProto.ElectLeader
import java.nio.ByteBuffer
import java.util.*

/**
 * wrapper class for ElectLeader protobuf message
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

    val tieBreak: UUID
        get() = UUID(
                packet.valBody.tiebreakerVal.upper,
                packet.valBody.tiebreakerVal.lower
        )
    
    val provides: AdvertisePacket.Provides
        get() = AdvertisePacket.valToProvides(packet.valBody.provides)
    
    override val type: PacketType
        get() = PacketType.TYPE_ELECT_LEADER

    val isHashed: Boolean
        get() = packet.valCase == ElectLeader.ValCase.VAL_HASH

    val hash: ByteArray
        get() = packet.valHash.toByteArray()

    data class Builder(
            var enableHashing: Boolean = false,
            var hashVal: ByteString? = null,
            var provides: AdvertisePacket.Provides? = null,
            var tiebreaker: UUID? = null,
    ) {
        private val salt: ByteArray = ByteArray(GenericHash.BYTES)



        private fun hashFromBuilder(): ByteString {
            val hashbytes = ByteArray(GenericHash.BYTES)
            var bytes = ByteString.EMPTY
            bytes.concat(ByteString.copyFrom(salt))
            bytes = bytes.concat(ByteString.copyFrom(uuidToBytes(tiebreaker)))
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

        fun setTiebreaker(tiebreaker: UUID) = apply {
            this.tiebreaker = tiebreaker
        }

        fun setHash(hash: ByteString) = apply {
            this.hashVal = hash
        }

        fun build(): ElectLeaderPacket {
            require((!(provides == null || tiebreaker == null)) || hashVal != null) { "both tiebreaker and provides must be set" }
            val builder = ElectLeader.newBuilder()
            return if (enableHashing) {
                ElectLeaderPacket(ElectLeader.newBuilder().setValHash(hashFromBuilder()).build())
            } else {
                ElectLeaderPacket(
                        ElectLeader.newBuilder()
                                .setValBody(
                                        ElectLeader.Body.newBuilder()
                                                .setSalt(ByteString.copyFrom(salt))
                                                .setProvides(providesToVal(provides!!))
                                                .setTiebreakerVal(protoUUIDfromUUID(tiebreaker))
                                                .build()
                                )
                                .build()
                )
            }
        }
    }

    companion object {
        fun uuidToBytes(uuid: UUID?): ByteArray {
            val uuidBuffer = ByteBuffer.allocate(16)
            uuidBuffer.putLong(uuid!!.mostSignificantBits)
            uuidBuffer.putLong(uuid.leastSignificantBits)
            return uuidBuffer.array()
        }

        fun newBuilder(): Builder {
            return Builder()
        }
        class Parser : ScatterSerializable.Companion.Parser<ElectLeader, ElectLeaderPacket>(ElectLeader.parser())
        fun parser(): Parser {
            return Parser()
        }

    }
}