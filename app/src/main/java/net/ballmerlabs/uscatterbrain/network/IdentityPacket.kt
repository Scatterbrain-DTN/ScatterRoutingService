package net.ballmerlabs.uscatterbrain.network

import android.content.Context
import com.google.protobuf.ByteString
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.uscatterbrain.ScatterProto
import net.ballmerlabs.uscatterbrain.db.hashAsUUID
import java.util.*

/**
 * wrapper class for Identity protobuf message
 */
class IdentityPacket(packet: ScatterProto.Identity) :
        ScatterSerializable<ScatterProto.Identity>(packet),
        MutableMap<String, ByteString> {
    val name: String
        get() = packet.`val`.givenname

    private val sig
        get() = packet.`val`.sig
    val pubkey: ByteArray
        get() = packet.`val`.keysMap[ScatterbrainApi.PROTOBUF_PRIVKEY_KEY]!!.toByteArray()

    val isEnd: Boolean
        get() = packet.end

    val uuid: UUID
    get() = hashAsUUID(hash)


    private val hash: ByteArray
    get() {
        val fingeprint = ByteArray(GenericHash.BYTES)
        LibsodiumInterface.sodium.crypto_generichash(
                fingeprint,
                fingeprint.size,
                pubkey,
                pubkey.size.toLong(),
                null,
                0
        )
        return fingeprint
    }

    val fingerprint: String
        get() = LibsodiumInterface.base64enc(hash)

    private fun sumBytes(): ByteString? {
        var result = ByteString.EMPTY
        result = result.concat(ByteString.copyFromUtf8(name))
        val sorted: SortedSet<String> = TreeSet(packet.`val`.keysMap.keys)
        for (key in sorted) {
            result = result.concat(ByteString.copyFromUtf8(key))
            val `val` = packet.`val`.keysMap[key] ?: return null
            result = result.concat(`val`)
        }
        return result
    }

    /**
     * Verifyed 25519 boolean.
     *
     * @param pubkey the pubkey
     * @return the boolean
     */
    fun verifyed25519(pubkey: ByteArray?): Boolean {
        if (isEnd) {
            return false
        }
        if (pubkey!!.size != Sign.PUBLICKEYBYTES) return false
        val messagebytes = sumBytes()
        return LibsodiumInterface.sodium.crypto_sign_verify_detached(sig.toByteArray(),
                messagebytes!!.toByteArray(),
                messagebytes.size().toLong(),
                pubkey) == 0
    }

    override val type: PacketType
        get() = PacketType.TYPE_IDENTITY


    val keymap: Map<String, ByteString>
        get() = packet.`val`.keysMap

    fun getSig(): ByteArray {
        return sig.toByteArray()
    }

    data class Builder(
            val context: Context,
            var pubkeyMap: MutableMap<String, ByteString> = TreeMap(),
            var scatterbrainPubkey: ByteArray? = null,
            var generateKeypair: Boolean = false,
            var name: String? = null,
            var sig: ByteArray? = null,
            var gone: Boolean = false,
            var secretkey: ByteArray? = null
            ) {

        fun ismGenerateKeypair(): Boolean {
            return generateKeypair
        }

        fun setEnd() = apply {
            gone = true
        }

        fun setEnd(end: Boolean) = apply {
            gone = end
        }

        fun setName(name: String) = apply {
            this.name = name
        }

        fun setSig(sig: ByteArray) = apply {
            this.sig = sig
        }

        fun setScatterbrainPubkey(pubkey: ByteString) = apply {
            scatterbrainPubkey = pubkey.toByteArray()
            pubkeyMap[ScatterbrainApi.PROTOBUF_PRIVKEY_KEY] = pubkey
        }

        fun sign(secretkey: ByteArray) = apply {
            this.secretkey = secretkey
        }



        private fun sumBytes(): ByteString {
            if (gone) {
                throw IllegalStateException("tried to sign an end packet")
            }
            var result = ByteString.EMPTY
            result = result.concat(ByteString.copyFromUtf8(name))
            val sorted: SortedSet<String> = TreeSet(pubkeyMap.keys)
            for (key in sorted) {
                result = result.concat(ByteString.copyFromUtf8(key))
                val `val` = pubkeyMap[key] ?: throw IllegalStateException("pubkey $key missing")
                result = result.concat(`val`)
            }
            return result
        }

        /**
         * Sign ed 25519 boolean.
         *
         * @return the boolean
         */
        fun signEd25519(): ByteArray {
            if (secretkey!!.size != Sign.SECRETKEYBYTES) throw IllegalStateException("invalid key length")
            val messagebytes = sumBytes()
            val sig = ByteArray(Sign.ED25519_BYTES)
            val p = PointerByReference(Pointer.NULL).pointer
            return if (LibsodiumInterface.sodium.crypto_sign_detached(sig,
                            p, messagebytes.toByteArray(), messagebytes.size().toLong(), secretkey) == 0) {
                sig
            } else {
                throw IllegalStateException("failed to sign")
            }
        }

        fun build(): IdentityPacket? {
            if (!gone) {
                if (secretkey == null && sig == null) {
                    throw IllegalStateException("failed to sign, secret key not set")
                }
                if (scatterbrainPubkey == null && !generateKeypair) {
                    return null
                }
                if (scatterbrainPubkey != null && generateKeypair) {
                    return null
                }
                if (name == null) {
                    return null
                }
            }
            return try {
                val packet = if (gone)
                    ScatterProto.Identity.newBuilder()
                            .setEnd(true)
                            .build()
                else
                    ScatterProto.Identity.newBuilder()
                            .setVal(
                                    ScatterProto.Identity.Body.newBuilder()
                                            .setGivenname(name)
                                            .setSig(ByteString.copyFrom(sig?: signEd25519()))
                                            .putAllKeys(pubkeyMap)
                                            .build()
                            )
                            .build()
                IdentityPacket(packet)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Builder

            if (context != other.context) return false
            if (pubkeyMap != other.pubkeyMap) return false
            if (scatterbrainPubkey != null) {
                if (other.scatterbrainPubkey == null) return false
                if (!scatterbrainPubkey.contentEquals(other.scatterbrainPubkey)) return false
            } else if (other.scatterbrainPubkey != null) return false
            if (generateKeypair != other.generateKeypair) return false
            if (name != other.name) return false
            if (sig != null) {
                if (other.sig == null) return false
                if (!sig.contentEquals(other.sig)) return false
            } else if (other.sig != null) return false
            if (gone != other.gone) return false

            return true
        }

        override fun hashCode(): Int {
            var result = context.hashCode()
            result = 31 * result + pubkeyMap.hashCode()
            result = 31 * result + (scatterbrainPubkey?.contentHashCode() ?: 0)
            result = 31 * result + generateKeypair.hashCode()
            result = 31 * result + (name?.hashCode() ?: 0)
            result = 31 * result + (sig?.contentHashCode() ?: 0)
            result = 31 * result + gone.hashCode()
            return result
        }

    }

    override fun isEmpty(): Boolean {
        return packet.`val`.keysMap.isEmpty()
    }

    override fun containsKey(key: String): Boolean {
        return packet.`val`.keysMap.containsKey(key)
    }

    override fun containsValue(value: ByteString): Boolean {
        return packet.`val`.keysMap.containsValue(value)
    }

    override operator fun get(key: String): ByteString? {
        return packet.`val`.keysMap[key]
    }

    override fun put(key: String, value: ByteString): ByteString? {
        return packet.`val`.keysMap.put(key, value)
    }

    override fun remove(key: String): ByteString? {
        return packet.`val`.keysMap.remove(key)
    }

    override fun putAll(from: Map<out String, ByteString>) {
        packet.`val`.keysMap.putAll(from)
    }

    override fun clear() {
        packet.`val`.keysMap.clear()
    }

    companion object {
        fun newBuilder(ctx: Context): Builder {
            return Builder(ctx)
        }
        class Parser : ScatterSerializable.Companion.Parser<ScatterProto.Identity, IdentityPacket>(ScatterProto.Identity.parser())
        fun parser(): Parser {
            return Parser()
        }

    }

    override val size: Int
        get() = packet.`val`.keysMap.size
    override val entries: MutableSet<MutableMap.MutableEntry<String, ByteString>>
        get() = packet.`val`.keysMap.entries
    override val keys: MutableSet<String>
        get() = packet.`val`.keysMap.keys
    override val values: MutableCollection<ByteString>
        get() = packet.`val`.keysMap.values
}