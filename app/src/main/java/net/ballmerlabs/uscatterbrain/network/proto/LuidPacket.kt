package net.ballmerlabs.uscatterbrain.network.proto

import com.google.protobuf.ByteString
import com.goterl.lazysodium.interfaces.GenericHash
import net.ballmerlabs.sbproto.SbPacket
import proto.Scatterbrain.Luid

import java.nio.ByteBuffer
import java.util.UUID
import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.util.hashAsUUID
import proto.Scatterbrain.MessageType

fun getHashUuid(uuid: UUID?): UUID? {
    return if(uuid == null)
        null
    else
        bytes2uuid(LuidPacket.calculateHashFromUUID(uuid))
}

/**
 * Wrapper class for Luid protobuf message
 * @property isHashed true if this packet has only a hash value
 * @property luidVal identifier of remote peer
 * @property hash hash of this packet
 * @property protoVersion Scatterbrain protocol version (used for version checks)
 * @property hashAsUUID hash of this packet represented as UUID
 */
@SbPacket(messageType = MessageType.LUID)
class LuidPacket (
    packet: Luid
) : ScatterSerializable<Luid>(packet, MessageType.LUID) {
    val isHashed: Boolean
    get() = packet.valCase == Luid.ValCase.VAL_HASH

    val luidVal: UUID = packet.valUuid.toUuid()
    
    val hash: ByteArray
        get() = if (isHashed) {
            packet.valHash.toByteArray()
        } else {
            calculateHashFromUUID(luidVal)
        }

    val protoVersion: Int
        get() = if (!isHashed) {
            -1
        } else {
            packet.valHash.protoversion
        }

    override fun validate(): Boolean {
        return hash.size <= MAX_HASH
    }

    //note: this only is safe because crypto_generichash_BYTES_MIN is 16
    private val hashAsUUID: UUID
        get() {
            val h = packet.valHash.hash.toByteArray()
            return if (!isHashed)
                getHashUuid(luidVal)!!
            else
                hashAsUUID(h)
        }

    fun verifyHash(p: LuidPacket): Boolean {
        return when {
            p.isHashed == isHashed -> {
                false
            }
            isHashed -> {
                val hash = calculateHashFromUUID(p.luidVal)
                hash.contentEquals(hash)
            }
            else -> {
                val hash = calculateHashFromUUID(luidVal)
                hash.contentEquals(p.hash)
            }
        }
    }

    data class Builder(
            var uuid: UUID? = null,
            var enablehash: Boolean = false,
            var version: Int = -1
    ) {
        fun enableHashing(protoversion: Int) = apply {
            enablehash = true
            version = protoversion
        }

        fun setLuid(uuid: UUID?) = apply {
            this.uuid = uuid
        }

        fun build(): LuidPacket {
            requireNotNull(uuid) { "uuid required" }
            val p = if (enablehash) {
                val h = Luid.hashed.newBuilder()
                        .setHash(ByteString.copyFrom(calculateHashFromUUID(uuid!!)))
                        .setProtoversion(version)
                        .build()
                Luid.newBuilder()
                        .setValHash(h)
                    //.setType(MessageType.LUID)
                        .build()
            } else {
                val u = uuid?.toProto()
                Luid.newBuilder()
                        .setValUuid(u)
                        .build()
            }
            return LuidPacket(p)
        }

    }

    companion object {
        fun calculateHashFromUUID(uuid: UUID): ByteArray {
            val hashbytes = ByteArray(GenericHash.BYTES)
            val uuidBuffer = ByteBuffer.allocate(16)
            uuidBuffer.putLong(uuid.mostSignificantBits)
            uuidBuffer.putLong(uuid.leastSignificantBits)
            val uuidbytes = uuidBuffer.array()
            LibsodiumInterface.sodium.crypto_generichash(
                    hashbytes,
                    hashbytes.size,
                    uuidbytes,
                    uuidbytes.size.toLong(),
                    null,
                    0
            )
            return hashbytes
        }

        fun newBuilder(): Builder {
            return Builder()
        }
    }
}