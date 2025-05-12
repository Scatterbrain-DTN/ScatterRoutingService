package net.ballmerlabs.uscatterbrain.network

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.Sodium
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.GenericHash
import com.goterl.lazysodium.interfaces.Hash
import com.goterl.lazysodium.interfaces.KeyExchange
import com.goterl.lazysodium.interfaces.SecretBox
import net.ballmerlabs.scatterbrainsdk.internal.SbApp
import java.math.BigInteger
import java.nio.ByteBuffer

/**
 * Singleton interface to libsodium/lazysodium over JNA
 */
object LibsodiumInterface {
    private var mSodiumInstance: LazySodiumAndroid? = null

    const val MERKLE_HASH_SIZE = GenericHash.BLAKE2B_BYTES_MIN

    private fun checkSodium() {
        if (mSodiumInstance == null) {
            mSodiumInstance = LazySodiumAndroid(SodiumAndroid())
        }
    }

    fun fingerprint(key: ByteArray): ByteArray {
        val out = ByteArray(GenericHash.BLAKE2B_BYTES_MIN)
        if (sodium.crypto_generichash(out, out.size, key, key.size.toLong(), null, 0) != 0 ) {
            throw IllegalStateException("failed to hash")
        }

        return out
    }

    fun merkleHash(key: ByteArray): ByteArray {
        val out = ByteArray(MERKLE_HASH_SIZE)
        if (sodium.crypto_generichash(out, out.size, key, key.size.toLong(), null, 0) != 0 ) {
            throw IllegalStateException("failed to hash")
        }

        return out
    }

    fun merkleHash(key: List<ByteArray>): ByteArray {
        val out = ByteArray(MERKLE_HASH_SIZE)
        val state = ByteArray(sodium.crypto_generichash_statebytes())
        if (sodium.crypto_generichash_init(state, null, 0, out.size) != 0 ) {
            throw IllegalStateException("failed to init")
        }

        for (h in key) {
            if (sodium.crypto_generichash_update(state, h, h.size.toLong()) != 0) {
                throw IllegalStateException("failed to update hash")
            }
        }

        if (sodium.crypto_generichash_final(state, out, out.size) != 0) {
            throw IllegalStateException("failed to finalize hash")
        }

        return out
    }

    val sodium: Sodium
        get() {
            checkSodium()
            return mSodiumInstance!!.sodium
        }

    fun base64enc(data: ByteArray): String {
        return android.util.Base64.encode(data, android.util.Base64.DEFAULT).decodeToString()
    }

    fun base64encUrl(data: ByteArray): String {
        return android.util.Base64.encode(data, android.util.Base64.URL_SAFE).decodeToString()
    }

    fun base64dec(data: String): ByteArray {
        return android.util.Base64.decode(data, android.util.Base64.DEFAULT)
    }

    fun base64decUrl(data: String): ByteArray {
        return android.util.Base64.decode(data, android.util.Base64.URL_SAFE)
    }
    fun secretboxKey(): ByteArray {
        val key = ByteArray(SecretBox.KEYBYTES)
        sodium.crypto_secretbox_keygen(key)
        return key
    }
}

fun ByteArray.b64(): String {
    return LibsodiumInterface.base64enc(this)
}

fun String.b64(): ByteArray {
    return LibsodiumInterface.base64dec(this)
}

fun ByteArray.fingerprint(): ByteArray {
    return LibsodiumInterface.fingerprint(this)
}

fun ByteArray.compare(byteArray: ByteArray): Int {
    val self = BigInteger(this)
    val compare = BigInteger(byteArray)
    return self.compareTo(compare)
}
