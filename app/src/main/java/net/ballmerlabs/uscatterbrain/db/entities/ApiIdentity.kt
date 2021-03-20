package net.ballmerlabs.uscatterbrain.db.entities

import com.google.protobuf.ByteString
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import net.ballmerlabs.scatterbrainsdk.Identity
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import java.util.*

class ApiIdentity protected constructor(builder: Builder) : Identity (
        builder.mPubKeymap,
        builder.mPubKeymap[ScatterbrainApi.PROTOBUF_PRIVKEY_KEY],
        builder.name,
        builder.sig,
        builder.fingerprint,
        builder.privkey != null
) {
    private val privatekey: ByteArray? = builder.privkey
    val privateKey: ByteArray?
        get() = privatekey

    class KeyPair(val secretkey: ByteArray?, val publickey: ByteArray?)

    class Builder {
        var sig: ByteArray? = null
        val mPubKeymap: MutableMap<String?, ByteArray?> = HashMap()
        var name: String? = null
        private var pubkey: ByteArray? = null
        val privkey: ByteArray? = null
        private var signPair: KeyPair? = null
        var fingerprint: String? = null
        private fun sumBytes(): ByteString {
            var result = ByteString.EMPTY
            result = result.concat(ByteString.copyFromUtf8(name))
            val sortedKeys: SortedSet<String?> = TreeSet(mPubKeymap.keys)
            for (key in sortedKeys) {
                result = result.concat(ByteString.copyFromUtf8(key))
                val k = mPubKeymap[key] ?: throw ConcurrentModificationException()
                result = result.concat(ByteString.copyFrom(k))
            }
            return result
        }

        fun getPubkeyFingerprint(): String? {
            val fingeprint = ByteArray(GenericHash.BYTES)
            LibsodiumInterface.sodium.crypto_generichash(
                    fingeprint,
                    fingeprint.size,
                    pubkey,
                    pubkey!!.size.toLong(),
                    null,
                    0
            )
            return LibsodiumInterface.base64enc(fingeprint)
        }

        /**
         * Sign ed 25519 boolean.
         *
         * @param secretkey the secretkey
         * @return the boolean
         */
        @Synchronized
        private fun signEd25519(secretkey: ByteArray?): Boolean {
            if (secretkey!!.size != Sign.SECRETKEYBYTES) return false
            val messagebytes = sumBytes()
            val signature = ByteArray(Sign.ED25519_BYTES)
            val p = PointerByReference(Pointer.NULL).pointer
            return if (LibsodiumInterface.sodium.crypto_sign_detached(signature,
                            p, messagebytes.toByteArray(), messagebytes.size().toLong(), secretkey) == 0) {
                sig = signature
                true
            } else {
                false
            }
        }

        fun setName(name: String): Builder {
            this.name = name
            return this
        }

        fun sign(keyPair: KeyPair): Builder {
            signPair = keyPair
            return this
        }

        fun setSig(sig: ByteArray): Builder {
            this.sig = sig
            return this
        }

        fun addKeys(keys: Map<String, ByteArray>): Builder {
            mPubKeymap.putAll(keys!!)
            return this
        }

        fun build(): ApiIdentity {
            requireNotNull(name) { "name should be non-null" }
            require(!(sig == null && signPair == null)) { "sig should be set" }
            require(!(sig != null && signPair != null)) { "cannot sign and set sig simultaneously" }
            if (signPair != null) {
                signEd25519(signPair!!.secretkey)
                mPubKeymap[ScatterbrainApi.PROTOBUF_PRIVKEY_KEY] = signPair!!.publickey
                pubkey = signPair!!.publickey
            } else {
                require(mPubKeymap.containsKey(ScatterbrainApi.PROTOBUF_PRIVKEY_KEY)) { "key map does not contain scatterbrain pubkey" }
                pubkey = mPubKeymap[ScatterbrainApi.PROTOBUF_PRIVKEY_KEY]
            }
            fingerprint = getPubkeyFingerprint()
            return ApiIdentity(this)
        }
    }

    companion object {
        fun newPrivateKey(): KeyPair {
            val sec = ByteArray(Sign.SECRETKEYBYTES)
            val pub = ByteArray(Sign.PUBLICKEYBYTES)
            LibsodiumInterface.sodium.crypto_sign_keypair(pub, sec)
            return KeyPair(sec, pub)
        }

        fun newBuilder(): Builder {
            return Builder()
        }
    }

}