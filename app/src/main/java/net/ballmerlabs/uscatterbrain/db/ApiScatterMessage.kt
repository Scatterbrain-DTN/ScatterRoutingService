package net.ballmerlabs.uscatterbrain.db

import com.google.protobuf.ByteString
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

class ApiScatterMessage : ScatterMessage {
    private val secretkey = AtomicReference<ByteArray?>()

    private constructor(builder: Builder) : super(builder) {
        secretkey.set(builder.privatekey)
    }

    private constructor(message: ScatterMessage) : super(superToBuilder(message)) {}
    private constructor(message: ScatterMessage, privateKey: ByteArray?) : super(superToBuilder(message)) {
        secretkey.set(privateKey)
    }

    private fun sumBytes(hashes: List<ByteString?>): ByteString {
        var messagebytes = ByteString.EMPTY
        messagebytes = messagebytes.concat(ByteString.copyFrom(fromFingerprint))
        messagebytes = messagebytes.concat(ByteString.copyFrom(toFingerprint))
        messagebytes = messagebytes.concat(ByteString.copyFromUtf8(application))
        messagebytes = messagebytes.concat(ByteString.copyFromUtf8(this.extension))
        messagebytes = messagebytes.concat(ByteString.copyFromUtf8(mime))
        messagebytes = messagebytes.concat(ByteString.copyFromUtf8(filename))
        var td: Byte = 0
        if (toDisk()) td = 1
        val toDiskBytes = ByteString.copyFrom(ByteBuffer.allocate(1).order(ByteOrder.BIG_ENDIAN).put(td).array())
        messagebytes = messagebytes.concat(toDiskBytes)
        for (hash in hashes) {
            messagebytes = messagebytes.concat(hash)
        }
        return messagebytes
    }

    /**
     * Sign ed 25519 boolean.
     *
     * @param hashes
     * @return the boolean
     */
    @Synchronized
    fun signEd25519(hashes: List<ByteString?>) {
        val pk = secretkey.get() ?: throw IllegalStateException("secret key not set")
        check(pk.size == Sign.SECRETKEYBYTES) { "secret key wrong length" }
        val messagebytes = sumBytes(hashes)
        val localsig = ByteArray(Sign.ED25519_BYTES)
        val p = PointerByReference(Pointer.NULL).pointer
        if (LibsodiumInterface.sodium.crypto_sign_detached(localsig,
                        p, messagebytes.toByteArray(), messagebytes.size().toLong(), pk) == 0) {
            sig.set(localsig)
        } else {
            secretkey.set(null)
            throw IllegalStateException("crypto_sign_detached failed")
        }
        secretkey.set(null)
    }

    fun signable(): Boolean {
        return secretkey.get() != null && secretkey.get()!!.size == Sign.SECRETKEYBYTES
    }

    class Builder : ScatterMessage.Builder() {
        var privatekey: ByteArray? = null
        fun sign(keyPair: ApiIdentity.KeyPair, fingerprint: String?): Builder {
            privatekey = keyPair.secretkey!!
            this.fingerprint = fingerprint
            return this
        }

        override fun build(): ApiScatterMessage {
            verify()
            return ApiScatterMessage(this)
        }
    }

    companion object {
        private fun superToBuilder(message: ScatterMessage): Builder {
            val builder = newBuilder()
            if (message.toDisk()) {
                builder
                        .setFile(
                                message.fileDescriptor,
                                message.extension,
                                message.mime,
                                message.filename
                        )
            } else {
                builder.setBody(message.body)
            }
            builder
                    .setApplication(message.application)
                    .setFrom(message.fromFingerprint)
                    .setTo(message.toFingerprint)
            return builder
        }

        fun fromApi(message: ScatterMessage): ApiScatterMessage {
            return ApiScatterMessage(message)
        }

        fun fromApi(message: ScatterMessage, pair: ApiIdentity.KeyPair?): ApiScatterMessage {
            return ApiScatterMessage(message, pair!!.secretkey)
        }

        fun newBuilder(): Builder {
            return Builder()
        }
    }
}