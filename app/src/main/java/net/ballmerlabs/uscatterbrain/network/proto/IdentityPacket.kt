package net.ballmerlabs.uscatterbrain.network.proto

import com.google.protobuf.ByteString
import com.goterl.lazysodium.interfaces.GenericHash
import com.goterl.lazysodium.interfaces.Sign
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import net.ballmerlabs.sbproto.SbPacket
import proto.Scatterbrain
import java.util.SortedSet
import java.util.TreeMap
import java.util.TreeSet
import java.util.UUID
import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.util.hashAsUUID
import proto.Scatterbrain.MessageType

/**
 * wrapper class for Identity protobuf message
 * @property name user-chosen human readable name
 * @property sig ed25519 signature of all fields
 * @property pubkey Scatterbrain official ed25519 public key
 * @property isEnd true if this is the last packet in a stream
 * @property uuid public key fingerprint as uuid
 * @property fingerprint public key fingerprint as string
 * @property keymap associative array of user-defined keys in any format
 */
@SbPacket(messageType = MessageType.IDENTITY)
data class IdentityPacket(private val p: Scatterbrain.Identity) :
        ScatterSerializable<Scatterbrain.Identity>(p, MessageType.IDENTITY),
        MutableMap<String, ByteString> {
    val name: String
        get() = packet.`val`.givenname

    private val sig
        get() = packet.`val`.sig
    val pubkey: ByteArray? = packet.`val`.keysMap[PROTOBUF_PRIVKEY_KEY]?.toByteArray()

    val isEnd: Boolean
        get() = packet.end

    override fun validate(): Boolean {
        return name.length <= MAX_FILENAME && sig.size() <= MAX_SIG &&
                (pubkey?.size?:0) <= MAX_KEY
    }

    private fun initHash(): ByteArray? {
        val pubkey = pubkey ?: return null
        return if (isEnd) {
            null
        } else {
            val fingeprint = ByteArray(GenericHash.BYTES)
            LibsodiumInterface.sodium.crypto_generichash(
                    fingeprint,
                    fingeprint.size,
                    pubkey,
                    pubkey.size.toLong(),
                    null,
                    0
            )
            fingeprint
        }
    }

    private val hash: ByteArray? = initHash()

    val uuid: UUID? = if (isEnd || hash == null) null else hashAsUUID(hash)

    val fingerprint: String? = if(isEnd || hash == null) null else LibsodiumInterface.base64enc(hash)

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
    fun verifyed25519(pubkey: ByteArray): Boolean {
        if (isEnd) {
            return false
        }
        if (pubkey.size != Sign.PUBLICKEYBYTES) return false
        val messagebytes = sumBytes()
        return LibsodiumInterface.sodium.crypto_sign_verify_detached(sig.toByteArray(),
                messagebytes!!.toByteArray(),
                messagebytes.size().toLong(),
                pubkey) == 0
    }

    val keymap: Map<String, ByteString>
        get() = packet.`val`.keysMap

    fun getSig(): ByteArray {
        return sig.toByteArray()
    }

    data class Builder(
            var pubkeyMap: MutableMap<String, ByteString> = TreeMap(),
            var scatterbrainPubkey: ByteArray? = null,
            var generateKeypair: Boolean = false,
            var name: String? = null,
            var sig: ByteArray? = null,
            var gone: Boolean = false,
            var secretkey: ByteArray? = null
            ) {

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
            pubkeyMap[PROTOBUF_PRIVKEY_KEY] = pubkey
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
        private fun signEd25519(): ByteArray {
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
                    Scatterbrain.Identity.newBuilder()
                            .setEnd(true)
                            .build()
                else
                    Scatterbrain.Identity.newBuilder()
                        //.setType(MessageType.IDENTITY)
                            .setVal(
                                    Scatterbrain.Identity.Body.newBuilder()
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
            var result = 31 * pubkeyMap.hashCode()
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
        fun newBuilder(): Builder {
            return Builder()
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