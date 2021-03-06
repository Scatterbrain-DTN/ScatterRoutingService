package net.ballmerlabs.uscatterbrain.network

import android.util.Log
import com.google.protobuf.ByteString
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import net.ballmerlabs.uscatterbrain.ScatterProto.Luid
import net.ballmerlabs.uscatterbrain.ScatterProto.Luid.hashed
import net.ballmerlabs.uscatterbrain.db.hashAsUUID
import java.nio.ByteBuffer
import java.util.*

/**
 * Wrapper class for Luid protobuf message
 */
class LuidPacket (packet: Luid) : ScatterSerializable<Luid>(packet) {
    val isHashed: Boolean
    get() = packet.valCase == Luid.ValCase.VAL_HASH

    val luidVal: UUID
        get() = protoUUIDtoUUID(packet.valUuid)
    
    val hash: ByteArray
        get() = if (isHashed) {
            packet.valHash.toByteArray()
        } else {
            ByteArray(0)
        }

    val protoVersion: Int
        get() = if (!isHashed) {
            -1
        } else {
            packet.valHash.protoversion
        }

    //note: this only is safe because crypto_generichash_BYTES_MIN is 16
    val hashAsUUID: UUID?
        get() {
            val h = packet.valHash.hash.toByteArray()
            return if (!isHashed)
                null
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

    val valCase: Luid.ValCase
        get() = packet.valCase

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
                Log.e("debug", "hashed packet size ${h.hash.size()}")
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
        private fun calculateHashFromUUID(uuid: UUID): ByteArray {
            val hashbytes = ByteArray(GenericHash.BYTES)
            Log.e("debug", "hash size ${hashbytes.size}")
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