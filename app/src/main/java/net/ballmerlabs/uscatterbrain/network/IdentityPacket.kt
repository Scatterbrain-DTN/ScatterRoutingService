package net.ballmerlabs.uscatterbrain.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.github.davidmoten.rx2.Bytes
import com.google.protobuf.ByteString
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.uscatterbrain.ScatterProto
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable.PacketType
import java.io.*
import java.net.ProtocolException
import java.security.GeneralSecurityException
import java.util.*
import java.util.concurrent.atomic.AtomicReference

/**
 * wrapper class for Identity protobuf message
 */
class IdentityPacket private constructor(builder: Builder) :
        ScatterSerializable,
        MutableMap<String, ByteString> {
    private var mPubKeymap: MutableMap<String, ByteString> = TreeMap()
    private lateinit var mKeystorePrefs: SharedPreferences
    private val mCtx: Context = builder.context
    private val mIdentity = AtomicReference<ScatterProto.Identity>()
    val name: String?
    private val sig = AtomicReference(ByteString.EMPTY)
    val pubkey: ByteArray?
    override var luid: UUID? = null
        private set


    init {
        val sig = builder.getSig()
        if (sig != null) {
            this.sig.set(ByteString.copyFrom(sig))
        }
        pubkey = builder.scatterbrainPubkey
        this.name = builder.name
        initKeyStore()
        if (builder.ismGenerateKeypair()) {
            generateKeyPair()
        }
        if (builder.gone) {
            mIdentity.set(ScatterProto.Identity.newBuilder()
                    .setEnd(true)
                    .build())
        } else {
            mPubKeymap[ScatterbrainApi.PROTOBUF_PRIVKEY_KEY] = ByteString.copyFrom(pubkey)
            regenIdentity()
        }
    }


    private fun regenIdentity() {
        val body = ScatterProto.Identity.Body.newBuilder()
                .setGivenname(name)
                .setSig(sig.get())
                .putAllKeys(mPubKeymap)
                .build()
        mIdentity.set(ScatterProto.Identity.newBuilder()
                .setVal(body)
                .setEnd(false)
                .build())
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    private fun initKeyStore() {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        mKeystorePrefs = EncryptedSharedPreferences.create(
                ScatterbrainApi.KEYSTORE_ID,
                masterKeyAlias,
                mCtx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val isEnd: Boolean
        get() = mIdentity.get().messageCase == ScatterProto.Identity.MessageCase.END

    val fingerprint: String
        get() {
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

    fun sumBytes(): ByteString? {
        if (isEnd) {
            return null
        }
        var result = ByteString.EMPTY
        result = result.concat(ByteString.copyFromUtf8(name))
        val sortedKeys: SortedSet<String> = TreeSet(mPubKeymap.keys)
        for (key in sortedKeys) {
            result = result.concat(ByteString.copyFromUtf8(key))
            val `val` = mPubKeymap[key] ?: return null
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
        return LibsodiumInterface.sodium.crypto_sign_verify_detached(sig.get()!!.toByteArray(),
                messagebytes!!.toByteArray(),
                messagebytes.size().toLong(),
                pubkey) == 0
    }

    override val type: PacketType
        get() = PacketType.TYPE_IDENTITY

    override fun tagLuid(luid: UUID?) {
        this.luid = luid
    }

    /**
     * Sign ed 25519 boolean.
     *
     * @param secretkey the secretkey
     * @return the boolean
     */
    @Synchronized
    fun signEd25519(secretkey: ByteArray): Boolean {
        if (secretkey.size != Sign.SECRETKEYBYTES) return false
        val messagebytes = sumBytes()
        val sig = ByteArray(Sign.ED25519_BYTES)
        val p = PointerByReference(Pointer.NULL).pointer
        return if (LibsodiumInterface.sodium.crypto_sign_detached(sig,
                        p, messagebytes!!.toByteArray(), messagebytes.size().toLong(), secretkey) == 0) {
            this.sig.set(ByteString.copyFrom(sig))
            regenIdentity()
            true
        } else {
            false
        }
    }

    override val bytes: ByteArray
        get() {
            val os = ByteArrayOutputStream()
            return try {
                CRCProtobuf.writeToCRC(mIdentity.get(), os)
                os.toByteArray()
            } catch (e: IOException) {
                byteArrayOf(0) //this should be unreachable
            }
        }

    override val byteString: ByteString
        get() = ByteString.copyFrom(bytes)

    override fun writeToStream(os: OutputStream): Completable {
        return Completable.fromAction { CRCProtobuf.writeToCRC(mIdentity.get(), os) }
    }

    override fun writeToStream(fragsize: Int): Flowable<ByteArray> {
        return Bytes.from(ByteArrayInputStream(bytes), fragsize)
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    private fun generateKeyPair() {
        val privkey = ByteArray(Sign.ED25519_SECRETKEYBYTES)
        if (pubkey!!.size != Sign.ED25519_PUBLICKEYBYTES) {
            throw IOException("public key length mismatch")
        }
        LibsodiumInterface.sodium.crypto_sign_keypair(pubkey, privkey)
        mPubKeymap[ScatterbrainApi.PROTOBUF_PRIVKEY_KEY] = ByteString.copyFrom(pubkey)
        val secretKeyBase64 = LibsodiumInterface.base64enc(privkey)
        val fingerprint = LibsodiumInterface.base64enc(pubkey)
        mKeystorePrefs.edit()
                .putString(fingerprint, secretKeyBase64)
                .apply()
        signEd25519(privkey)
    }

    val keymap: Map<String, ByteString>
        get() = mPubKeymap.toMap()

    fun getSig(): ByteArray {
        return sig.get()!!.toByteArray()
    }

    class Builder(val context: Context) {
        var mPubKeymap: MutableMap<String, ByteString> = TreeMap()
        var scatterbrainPubkey: ByteArray? = null
            private set
        private var mGenerateKeypair = false
        var name: String? = null
            private set
        private var mSig: ByteArray? = null
        var gone = false

        fun ismGenerateKeypair(): Boolean {
            return mGenerateKeypair
        }

        fun getSig(): ByteArray? {
            return mSig
        }

        fun setEnd(): Builder {
            gone = true
            return this
        }

        fun setEnd(end: Boolean): Builder {
            gone = end
            return this
        }

        fun setName(name: String): Builder {
            this.name = name
            return this
        }

        fun setSig(sig: ByteArray): Builder {
            mSig = sig
            return this
        }

        fun setScatterbrainPubkey(pubkey: ByteString): Builder {
            scatterbrainPubkey = pubkey.toByteArray()
            return this
        }

        fun build(): IdentityPacket? {
            if (!gone) {
                if (scatterbrainPubkey == null && !mGenerateKeypair) {
                    return null
                }
                if (scatterbrainPubkey != null && mGenerateKeypair) {
                    return null
                }
                if (name == null) {
                    return null
                }
            }
            return try {
                IdentityPacket(this)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    }

    override fun isEmpty(): Boolean {
        return mPubKeymap.isEmpty()
    }

    override fun containsKey(key: String): Boolean {
        return mPubKeymap.containsKey(key)
    }

    override fun containsValue(value: ByteString): Boolean {
        return mPubKeymap.containsValue(value)
    }

    override operator fun get(key: String): ByteString? {
        return mPubKeymap[key]
    }

    override fun put(key: String, value: ByteString): ByteString? {
        return mPubKeymap.put(key, value)
    }

    override fun remove(key: String): ByteString? {
        return mPubKeymap.remove(key)
    }

    override fun putAll(from: Map<out String, ByteString>) {
        mPubKeymap.putAll(from)
    }

    override fun clear() {
        mPubKeymap.clear()
    }

    companion object {

        private fun builderFromIs(inputstream: InputStream, context: Context) : Builder {
            val identity = CRCProtobuf.parseFromCRC(ScatterProto.Identity.parser(), inputstream)
            val builder = Builder(context)
            if (identity!!.messageCase == ScatterProto.Identity.MessageCase.VAL) {
                builder.mPubKeymap = identity.getVal().keysMap
                builder.setSig(identity.getVal().sig.toByteArray())
                builder.setName(identity.getVal().givenname)
                val scatterbrainKey = identity.getVal().keysMap[ScatterbrainApi.PROTOBUF_PRIVKEY_KEY]
                        ?: throw ProtocolException("scatterbrain key not in map")
                builder.setScatterbrainPubkey(scatterbrainKey)
            } else {
                builder.setEnd()
            }
            return builder
        }

        fun parseFrom(`is`: InputStream, ctx: Context): Single<IdentityPacket> {
            return Single.fromCallable { IdentityPacket(builderFromIs(`is`, ctx)) }
        }

        fun parseFrom(flowable: Observable<ByteArray>, ctx: Context): Single<IdentityPacket> {
            val observer = InputStreamObserver(512) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer, ctx).doFinally { observer.close() }
        }

        fun parseFrom(flowable: Flowable<ByteArray>, ctx: Context): Single<IdentityPacket> {
            val observer = InputStreamFlowableSubscriber(512) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer, ctx).doFinally { observer.close() }
        }

        fun newBuilder(ctx: Context): Builder {
            return Builder(ctx)
        }
    }

    override val size: Int
        get() = mPubKeymap.size
    override val entries: MutableSet<MutableMap.MutableEntry<String, ByteString>>
        get() = mPubKeymap.entries
    override val keys: MutableSet<String>
        get() = mPubKeymap.keys
    override val values: MutableCollection<ByteString>
        get() = mPubKeymap.values
}