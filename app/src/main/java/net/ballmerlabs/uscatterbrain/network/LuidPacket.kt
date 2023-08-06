package net.ballmerlabs.uscatterbrain.network

import com.google.protobuf.ByteString
import com.goterl.lazysodium.interfaces.GenericHash
import net.ballmerlabs.uscatterbrain.ScatterProto.Luid
import net.ballmerlabs.uscatterbrain.ScatterProto.Luid.hashed
import net.ballmerlabs.uscatterbrain.db.hashAsUUID
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl
import java.nio.ByteBuffer
import java.util.UUID

fun getHashUuid(uuid: UUID?): UUID? {
    return if(uuid == null)
        null
    else
        BluetoothLERadioModuleImpl.bytes2uuid(LuidPacket.calculateHashFromUUID(uuid))
}

/**
 * Wrapper class for Luid protobuf message
 * @property isHashed true if this packet has only a hash value
 * @property luidVal identifier of remote peer
 * @property hash hash of this packet
 * @property protoVersion Scatterbrain protocol version (used for version checks)
 * @property hashAsUUID hash of this packet represented as UUID
 */
class LuidPacket (packet: Luid) : ScatterSerializable<Luid>(packet) {
    val isHashed: Boolean
    get() = packet.valCase == Luid.ValCase.VAL_HASH

    val luidVal: UUID = protoUUIDtoUUID(packet.valUuid)
    
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

    override val type: PacketType
        get() = PacketType.TYPE_LUID

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
                val h = hashed.newBuilder()
                        .setHash(ByteString.copyFrom(calculateHashFromUUID(uuid!!)))
                        .setProtoversion(version)
                        .build()
                Luid.newBuilder()
                        .setValHash(h)
                        .build()
            } else {
                val u = protoUUIDfromUUID(uuid!!)
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

        class Parser : ScatterSerializable.Companion.Parser<Luid, LuidPacket>(Luid.parser())
        fun parser(): Parser {
            return Parser()
        }
    }
}